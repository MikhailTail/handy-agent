package dev.mikhailtail.handyagent.tools

import dev.mikhailtail.handyagent.kernel.api.Tool
import dev.mikhailtail.handyagent.kernel.api.ToolContext
import dev.mikhailtail.handyagent.kernel.api.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 内置工具集。
 *
 * 命名与语义对齐 cc-haha 的 `src/tools/`（Read / Write / Edit / Glob / Grep / Bash），
 * 这样模型看到的世界跟桌面版一致 —— 换宿主不该换掉工具名，否则既有的提示词与
 * 用户习惯全部作废。
 *
 * **只读工具不需要审批**（见 [Tool.isReadOnly]）。这条是体验底线：
 * 读文件也要点确认的话，用户会很快放弃使用。
 */

// ─── 公共小工具 ───────────────────────────────────────────────────────────────

private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, v) }
}

private fun str(value: String): JsonElement = JsonPrimitive(value)

private fun JsonObject.strOf(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull()

private fun JsonObject.boolOf(key: String): Boolean? =
    this[key]?.jsonPrimitive?.contentOrNull()?.toBooleanStrictOrNull()

private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

/** 参数缺失时统一的报错形态 —— 让模型能看懂并自己纠正。 */
private fun missing(param: String) = ToolResult(
    content = str("参数 `$param` 缺失或类型不对"),
    isError = true,
)

private fun schemaObject(
    properties: Map<String, JsonObject>,
    required: List<String>,
): JsonObject = buildJsonObject {
    put("type", str("object"))
    put("properties", buildJsonObject { properties.forEach { (k, v) -> put(k, v) } })
    put("required", buildJsonArray { required.forEach { add(str(it)) } })
}

private fun prop(type: String, description: String): JsonObject =
    obj("type" to str(type), "description" to str(description))

// ─── Read ────────────────────────────────────────────────────────────────────

/**
 * 读文件。
 *
 * 带行号返回：模型经常要引用"第 N 行"来配合 Edit，不给行号它就得到处重新数，
 * 既慢又容易数错。
 */
object ReadTool : Tool {
    override val name = "Read"
    override val description = """
        读取文件内容，返回带行号的文本。

        读长文件时用 offset/limit 分段读，不要一次拉几百 KB 进上下文。
    """.trimIndent()

    override val inputSchema = schemaObject(
        mapOf(
            "file_path" to prop("string", "文件的绝对路径"),
            "offset" to prop("integer", "从第几行开始读（1 起，可选）"),
            "limit" to prop("integer", "最多读多少行（可选，默认 2000）"),
        ),
        required = listOf("file_path"),
    )

    override val isReadOnly = true

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val path = input.strOf("file_path") ?: return missing("file_path")
        if (!ctx.files.exists(path)) return ToolResult(str("文件不存在：$path"), isError = true)
        if (ctx.files.isDirectory(path)) return ToolResult(str("这是一个目录，不是文件：$path"), isError = true)

        return runCatching {
            val lines = ctx.files.readText(path).lines()
            val offset = (input.strOf("offset")?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val limit = (input.strOf("limit")?.toIntOrNull() ?: DEFAULT_LIMIT).coerceAtLeast(1)
            val slice = lines.drop(offset - 1).take(limit)
            val numbered = slice.mapIndexed { i, line -> "${offset + i}\t$line" }.joinToString("\n")
            val truncated = lines.size > offset - 1 + slice.size
            ToolResult(
                str(
                    buildString {
                        append(numbered)
                        if (truncated) {
                            append("\n\n[已截断：文件共 ${lines.size} 行，本次读到 ${offset + slice.size - 1} 行]")
                        }
                    },
                ),
            )
        }.getOrElse { ToolResult(str("读取失败：${it.message}"), isError = true) }
    }

    private const val DEFAULT_LIMIT = 2000
}

// ─── Write ───────────────────────────────────────────────────────────────────

object WriteTool : Tool {
    override val name = "Write"
    override val description = """
        把内容写入文件（整份覆盖）。父目录不存在会自动创建。

        改已有文件请优先用 Edit —— 整份覆盖会把你没读到的部分一起抹掉。
    """.trimIndent()

    override val inputSchema = schemaObject(
        mapOf(
            "file_path" to prop("string", "文件的绝对路径"),
            "content" to prop("string", "要写入的完整内容"),
        ),
        required = listOf("file_path", "content"),
    )

    override val isReadOnly = false
    override val isDestructive = true    // 覆盖会丢内容

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val path = input.strOf("file_path") ?: return missing("file_path")
        val content = input.strOf("content") ?: return missing("content")
        return runCatching {
            val existed = ctx.files.exists(path)
            ctx.files.writeText(path, content)
            ToolResult(str(if (existed) "已覆盖 $path" else "已创建 $path"))
        }.getOrElse { ToolResult(str("写入失败：${it.message}"), isError = true) }
    }
}

// ─── Edit ────────────────────────────────────────────────────────────────────

/**
 * 精确替换。
 *
 * 要求 `old_string` 在文件中**唯一**：不唯一就直接失败并告知出现次数，而不是替换第一处。
 * 这是刻意的保守 —— 悄悄改了错的地方，比报错难查得多。
 */
object EditTool : Tool {
    override val name = "Edit"
    override val description = """
        在文件中把 old_string 替换成 new_string。

        old_string 必须在文件里**只出现一次**，否则会失败。要改多处请多次调用，
        或先用 Read 看清上下文把 old_string 写长一点。
    """.trimIndent()

    override val inputSchema = schemaObject(
        mapOf(
            "file_path" to prop("string", "文件的绝对路径"),
            "old_string" to prop("string", "要被替换的原文（必须唯一）"),
            "new_string" to prop("string", "替换后的内容"),
        ),
        required = listOf("file_path", "old_string", "new_string"),
    )

    override val isReadOnly = false

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val path = input.strOf("file_path") ?: return missing("file_path")
        val oldStr = input.strOf("old_string") ?: return missing("old_string")
        val newStr = input.strOf("new_string") ?: return missing("new_string")

        return runCatching {
            val text = ctx.files.readText(path)
            val count = text.windowed(oldStr.length).count { it == oldStr }
            when {
                count == 0 -> ToolResult(str("未找到要替换的内容。请先 Read 确认原文。"), isError = true)
                count > 1 -> ToolResult(
                    str("old_string 出现了 $count 次，不唯一。请扩大上下文让它只匹配一处。"),
                    isError = true,
                )
                else -> {
                    ctx.files.writeText(path, text.replace(oldStr, newStr))
                    ToolResult(str("已修改 $path"))
                }
            }
        }.getOrElse { ToolResult(str("编辑失败：${it.message}"), isError = true) }
    }
}

// ─── Glob ────────────────────────────────────────────────────────────────────

object GlobTool : Tool {
    override val name = "Glob"
    override val description = """
        按文件名模式查找文件。支持 *、**、? 三个通配符。

        例：`**/*.kt`、`src/**/Test*.java`
    """.trimIndent()

    override val inputSchema = schemaObject(
        mapOf("pattern" to prop("string", "glob 模式")),
        required = listOf("pattern"),
    )

    override val isReadOnly = true

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = input.strOf("pattern") ?: return missing("pattern")
        return runCatching {
            val hits = ctx.files.glob(pattern, ctx.workDir)
            ToolResult(
                str(
                    when {
                        hits.isEmpty() -> "没有匹配的文件"
                        else -> buildString {
                            append(hits.joinToString("\n"))
                            if (hits.size >= RealFileHost.MAX_GLOB_RESULTS) {
                                append("\n\n[结果已达上限 ${RealFileHost.MAX_GLOB_RESULTS}，请把模式写得更具体]")
                            }
                        }
                    },
                ),
            )
        }.getOrElse { ToolResult(str("查找失败：${it.message}"), isError = true) }
    }
}

// ─── Grep ────────────────────────────────────────────────────────────────────

object GrepTool : Tool {
    override val name = "Grep"
    override val description = """
        在文件内容里按正则搜索，返回匹配的行。

        比 Bash 里的 grep 更方便：结果带文件名与行号，可以直接喂给 Read。
    """.trimIndent()

    override val inputSchema = schemaObject(
        mapOf(
            "pattern" to prop("string", "正则表达式"),
            "path" to prop("string", "搜索的根目录（可选，默认工作目录）"),
            "glob" to prop("string", "只搜匹配该模式的文件（可选）"),
        ),
        required = listOf("pattern"),
    )

    override val isReadOnly = true

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = input.strOf("pattern") ?: return missing("pattern")
        val root = input.strOf("path") ?: ctx.workDir
        val fileGlob = input.strOf("glob")

        return runCatching {
            val regex = Regex(pattern)
            val filePattern = fileGlob?.let { RealFileHost.globToRegex(it) }
            val hits = mutableListOf<String>()

            File(root).walkTopDown()
                .filter { it.isFile }
                .filter { filePattern == null || filePattern.matches(it.name) }
                .forEach { file ->
                    runCatching {
                        file.readLines().forEachIndexed { i, line ->
                            if (hits.size < MAX_HITS && regex.containsMatchIn(line)) {
                                hits += "${file.absolutePath}:${i + 1}: ${line.trim().take(200)}"
                            }
                        }
                    }
                }

            ToolResult(
                str(
                    when {
                        hits.isEmpty() -> "没有匹配"
                        else -> hits.joinToString("\n") +
                            if (hits.size >= MAX_HITS) "\n\n[结果已达上限 $MAX_HITS]" else ""
                    },
                ),
            )
        }.getOrElse { ToolResult(str("搜索失败：${it.message}"), isError = true) }
    }

    private const val MAX_HITS = 200
}

// ─── Bash ────────────────────────────────────────────────────────────────────

/**
 * 执行 shell 命令。
 *
 * 描述里必须写清 Android 的限制 —— 它没有 bash，`/system/bin/sh` 是 mksh。
 * 不写清楚的话，模型会写出 `[[ ]]`、数组、`<(...)` 这类语法然后反复失败。
 */
object BashTool : Tool {
    override val name = "Bash"
    override val description = """
        执行 shell 命令并返回输出。

        注意运行环境：Android 上是 /system/bin/sh（mksh），**没有 bash 扩展语法**
        —— 不要用 [[ ]]、数组、进程替换、$'...'。确实需要时先试 `command -v bash`。

        命令默认在工作目录下执行；需要别的目录请自己 cd。
    """.trimIndent()

    override val inputSchema = schemaObject(
        mapOf(
            "command" to prop("string", "要执行的命令"),
            "timeout" to prop("integer", "超时毫秒数（可选，默认 120000）"),
        ),
        required = listOf("command"),
    )

    override val isReadOnly = false
    override val isDestructive = true    // 命令能删任何东西

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val command = input.strOf("command") ?: return missing("command")
        val timeout = input.strOf("timeout")?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS

        val result = ctx.shell.run(command, ctx.workDir, timeout)
        val body = buildString {
            if (result.stdout.isNotBlank()) append(result.stdout)
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("[stderr]\n").append(result.stderr)
            }
            if (isEmpty()) append("（无输出）")
            append("\n[退出码 ${result.exitCode}]")
        }
        return ToolResult(str(body), isError = result.exitCode != 0)
    }

    private const val DEFAULT_TIMEOUT_MS = 120_000L
}

/**
 * 默认工具集。
 *
 * 顺序无所谓（模型按名字选），但保持与 cc-haha 一致的排列便于对照。
 */
val BUILTIN_TOOLS: List<Tool> = listOf(
    ReadTool, WriteTool, EditTool, GlobTool, GrepTool, BashTool,
)

/** 按名字查找。 */
fun toolByName(name: String): Tool? = BUILTIN_TOOLS.firstOrNull { it.name == name }

/**
 * 把工具集转成 Anthropic 请求体里的 `tools` 数组。
 *
 * 字段名是 `input_schema`（snake_case）—— 写成 camelCase 会被端点静默忽略，
 * 表现为"模型从来不调用工具"，很难往这上面想。
 */
fun toolsToApiSchema(tools: List<Tool> = BUILTIN_TOOLS): JsonElement = buildJsonArray {
    tools.forEach { tool ->
        add(
            buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                put("description", JsonPrimitive(tool.description))
                put("input_schema", tool.inputSchema)
            },
        )
    }
}
