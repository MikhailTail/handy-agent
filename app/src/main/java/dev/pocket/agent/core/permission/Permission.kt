package dev.pocket.agent.core.permission

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.model.ToolSpec
import dev.pocket.agent.core.sandbox.PathJail
import dev.pocket.agent.core.util.DiffEngine
import dev.pocket.agent.core.util.DiffLine
import dev.pocket.agent.core.util.DiffStats
import java.io.File

/**
 * 单工具的审批策略。
 * - [ALWAYS] 直接放行
 * - [ASK]    挂起并把 [PermissionRequest] 抛给 UI
 * - [NEVER]  直接拒绝（回填给模型的是 isError 结果，而不是异常）
 */
enum class PermissionMode { ALWAYS, ASK, NEVER }

/** UI 里的「记住本次会话」选项。 */
enum class RememberScope { NONE, SESSION }

data class PermissionRequest(
    val toolName: String,
    val input: Json,
    /** 人类可读的一句话说明，例如 "write src/a.kt (12 lines)"。 */
    val summary: String,
    /** 需要审批的落盘类操作会带上 unified diff 与结构化行，供红绿渲染。 */
    val diffLines: List<DiffLine> = emptyList(),
    val diffText: String = "",
    val stats: DiffStats = DiffStats(0, 0),
    val readOnly: Boolean = false,
) {
    val hasDiff: Boolean get() = diffLines.isNotEmpty()
}

/** UI 的裁决结果。 */
data class PermissionVerdict(val allowed: Boolean, val scope: RememberScope = RememberScope.NONE) {
    companion object {
        fun allowOnce() = PermissionVerdict(true)
        fun allowSession() = PermissionVerdict(true, RememberScope.SESSION)
        fun denyOnce() = PermissionVerdict(false)
        fun denySession() = PermissionVerdict(false, RememberScope.SESSION)
    }
}

/**
 * 审批人。UI 层实现它（弹 sheet），单测用脚本化假实现。
 * 返回 null 表示「无法决策」（如 UI 已销毁）→ broker 按拒绝处理，绝不默认放行。
 */
fun interface PermissionApprover {
    suspend fun approve(request: PermissionRequest): PermissionVerdict?
}

/**
 * 可换绑的审批人。
 *
 * 存在的理由：`AgentRuntime` 是进程级惰性单例，装配它时 Compose 界面还没起来
 * （弹窗行为属于 UI，不能塞进 core）。于是装配期先装这个中继，UI 就绪后把
 * [target] 指向自己；UI 销毁时置回 null。
 *
 * 未绑定（或 target 返回 null）时 [approve] 返回 null，broker 按**拒绝**处理——
 * 与「没有审批人就绝不静默放行」的既有约定一致，而不是退化成自动批准。
 */
class PermissionRelay : PermissionApprover {

    @Volatile
    var target: PermissionApprover? = null

    val bound: Boolean get() = target != null

    override suspend fun approve(request: PermissionRequest): PermissionVerdict? =
        target?.approve(request)
}

/**
 * 审批结果。denied 时 [reason] 会作为 tool_result 回填，让模型知道被拒绝并可另寻他法。
 */
data class PermissionOutcome(
    val allowed: Boolean,
    val reason: String = "",
    /** true 表示本次调用被记住，同会话后续同类调用不再询问。 */
    val remembered: Boolean = false,
) {
    companion object {
        val AUTO = PermissionOutcome(true, "auto-allowed (read-only)")
        fun denied(reason: String) = PermissionOutcome(false, reason)
    }
}

/**
 * 审批中枢。纯 Kotlin，无 android.* 依赖。
 *
 * 决策顺序：NEVER(拒绝) → readOnly 自动放行 → ALWAYS(放行) → ASK(问 UI) → 默认拒绝。
 * 「默认拒绝」是刻意的 fail-closed：没有审批人时绝不能静默执行写操作。
 */
class PermissionBroker(
    private val approver: PermissionApprover? = null,
    private val previewer: ToolPreviewer = ToolPreviewer(),
) {
    private val modes = LinkedHashMap<String, PermissionMode>()
    private var readOnlyCache: Set<String>? = null

    fun modeOf(toolName: String): PermissionMode? = modes[toolName]

    /** 为整张工具表设定基线（只读工具默认 ALWAYS，其余默认 ASK）。 */
    fun applyDefaults(specs: List<ToolSpec>) {
        readOnlyCache = specs.filter { it.readOnly }.map { it.name }.toSet()
        for (spec in specs) {
            modes[spec.name] = if (spec.readOnly) PermissionMode.ALWAYS else PermissionMode.ASK
        }
    }

    fun setMode(toolName: String, mode: PermissionMode) {
        modes[toolName] = mode
    }

    /** 会话内「记住」：把策略固化为 ALWAYS / NEVER。 */
    fun remember(toolName: String, allowed: Boolean) {
        modes[toolName] = if (allowed) PermissionMode.ALWAYS else PermissionMode.NEVER
    }

    fun snapshot(): Map<String, PermissionMode> = LinkedHashMap(modes)

    /**
     * 对一次工具调用做裁决。
     * @param spec 用于判定 readOnly；未知工具（MCP/插件动态注册）按非只读处理。
     */
    suspend fun check(
        toolName: String,
        input: Json,
        spec: ToolSpec?,
        jail: PathJail,
    ): PermissionOutcome {
        val current = modes[toolName]
            ?: if (spec?.readOnly == true || toolName in (readOnlyCache ?: emptySet())) {
                PermissionMode.ALWAYS
            } else {
                PermissionMode.ASK
            }

        when (current) {
            PermissionMode.ALWAYS -> return if (spec?.readOnly == true) {
                PermissionOutcome.AUTO
            } else {
                PermissionOutcome(true, "allowed by policy (always)")
            }

            PermissionMode.NEVER -> return PermissionOutcome.denied(
                "tool '$toolName' is disabled by policy; do not retry it"
            )

            PermissionMode.ASK -> Unit
        }

        val request = previewer.preview(toolName, input, spec, jail)
        val verdict = approver?.approve(request)
            ?: return PermissionOutcome.denied(
                "tool '$toolName' requires approval but no approver is available"
            )

        if (verdict.scope == RememberScope.SESSION) {
            remember(toolName, verdict.allowed)
        }
        return if (verdict.allowed) {
            PermissionOutcome(true, "approved by user", remembered = verdict.scope == RememberScope.SESSION)
        } else {
            PermissionOutcome.denied(
                if (verdict.scope == RememberScope.SESSION) {
                    "user denied '$toolName' for this session; do not retry"
                } else {
                    "user denied '$toolName'"
                }
            )
        }
    }

    /** 便于 UI 与单测：不询问，仅返回「这一步需不需要问」。 */
    fun requiresApproval(toolName: String, spec: ToolSpec?): Boolean {
        val mode = modes[toolName]
            ?: if (spec?.readOnly == true || toolName in (readOnlyCache ?: emptySet())) {
                PermissionMode.ALWAYS
            } else {
                PermissionMode.ASK
            }
        return mode == PermissionMode.ASK
    }
}

/**
 * 生成审批预览。对 write/edit 会读取当前文件并算出 diff，让用户「所见即所批」。
 * 只读、无副作用，宁可返回粗粒度摘要也不抛异常。
 */
class ToolPreviewer(private val maxDiffLines: Int = 400) {

    fun preview(toolName: String, input: Json, spec: ToolSpec?, jail: PathJail): PermissionRequest {
        val readOnly = spec?.readOnly == true
        return when (toolName) {
            "write" -> writePreview(input, jail, readOnly)
            "edit" -> editPreview(input, jail, readOnly)
            "bash", "shell" -> shellPreview(input, readOnly)
            else -> genericPreview(toolName, input, readOnly)
        }
    }

    private fun writePreview(input: Json, jail: PathJail, readOnly: Boolean): PermissionRequest {
        val path = input.str("path") ?: return genericPreview("write", input, readOnly)
        val content = input.str("content") ?: ""
        val target = resolveOrNull(path, jail)
            ?: return genericPreview(
                "write", input, readOnly,
                summaryOverride = "write $path (path escapes sandbox — will fail)",
            )
        if (target.isDirectory) {
            return genericPreview("write", input, readOnly, summaryOverride = "write $path (is a directory — will fail)")
        }
        val existing = if (target.isFile) readOrNull(target) else null
        val old = existing ?: ""
        val action = if (existing == null) "create" else "overwrite"
        val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
        return build(
            toolName = "write",
            input = input,
            summary = "$action $path ($lines lines, ${content.length} chars)",
            oldText = old,
            newText = content,
            oldName = if (existing == null) "/dev/null" else path,
            newName = path,
            readOnly = readOnly,
        )
    }

    private fun editPreview(input: Json, jail: PathJail, readOnly: Boolean): PermissionRequest {
        val path = input.str("path") ?: return genericPreview("edit", input, readOnly)
        val oldString = input.str("old_string") ?: ""
        val newString = input.str("new_string") ?: ""
        val replaceAll = input.bool("replace_all") ?: false

        val target = resolveOrNull(path, jail)
            ?: return genericPreview(
                "edit", input, readOnly,
                summaryOverride = "edit $path (path escapes sandbox — will fail)",
            )
        val current = (if (target.isFile) readOrNull(target) else null)
            ?: return genericPreview(
                "edit", input, readOnly,
                summaryOverride = "edit $path (file not found — will fail)",
            )
        if (oldString.isEmpty()) {
            return genericPreview("edit", input, readOnly, summaryOverride = "edit $path (empty match — will fail)")
        }

        val count = countOccurrences(current, oldString)
        // 预览必须与工具的真实行为一致：会被工具拒绝的情况一律「无变更、无 diff」。
        val willFail = count == 0 || (count > 1 && !replaceAll)
        val updated = when {
            willFail -> current
            replaceAll -> current.replace(oldString, newString)
            else -> current.replaceFirst(oldString, newString)
        }
        val action = when {
            count == 0 -> "edit $path (no match — will fail)"
            count > 1 && !replaceAll -> "edit $path (ambiguous: $count matches — will fail)"
            else -> "edit $path ($count replacement${if (count == 1) "" else "s"})"
        }
        return build(
            toolName = "edit",
            input = input,
            summary = action,
            oldText = current,
            newText = updated,
            oldName = path,
            newName = path,
            readOnly = readOnly,
        )
    }

    private fun shellPreview(input: Json, readOnly: Boolean): PermissionRequest {
        val cmd = input.str("command") ?: input.str("cmd") ?: ""
        return PermissionRequest(
            toolName = "bash",
            input = input,
            summary = "run: ${cmd.take(200)}",
            readOnly = readOnly,
        )
    }

    private fun genericPreview(
        toolName: String,
        input: Json,
        readOnly: Boolean,
        summaryOverride: String? = null,
    ): PermissionRequest {
        val encoded = input.encode()
        val summary = summaryOverride
            ?: "$toolName ${encoded.take(200)}${if (encoded.length > 200) "…" else ""}"
        return PermissionRequest(toolName = toolName, input = input, summary = summary, readOnly = readOnly)
    }

    private fun build(
        toolName: String,
        input: Json,
        summary: String,
        oldText: String,
        newText: String,
        oldName: String,
        newName: String,
        readOnly: Boolean,
    ): PermissionRequest {
        val ops = DiffEngine.compute(oldText, newText)
        val stats = DiffEngine.stats(ops)
        // 无变更时不返回「全 context」噪音行：没有红绿就没有可审批的内容。
        val capped = when {
            stats.isEmpty -> emptyList()
            ops.size > maxDiffLines -> ops.take(maxDiffLines)
            else -> ops
        }
        val unified = if (stats.isEmpty) "" else DiffEngine.unified(oldText, newText, oldName, newName)
        return PermissionRequest(
            toolName = toolName,
            input = input,
            summary = summary,
            diffLines = capped,
            diffText = unified,
            stats = stats,
            readOnly = readOnly,
        )
    }

    private fun resolveOrNull(path: String, jail: PathJail): File? = try {
        jail.resolve(path)
    } catch (e: Exception) {
        null
    }

    private fun readOrNull(f: File): String? = try {
        f.readText()
    } catch (e: Exception) {
        null
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }
}
