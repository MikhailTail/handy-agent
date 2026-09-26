package dev.pocket.agent.ui.timeline

import dev.pocket.agent.core.json.Json

/**
 * 工具卡片标题栏的一行摘要。
 *
 * 纯函数：把一次工具调用的入参压成「动词 + 目标」的人话，让用户不必展开 JSON
 * 就知道 Agent 在干什么。认不出的工具退回 [JsonPretty.oneLine]。
 */
object ToolSummary {

    private const val MAX = 96

    fun describe(name: String, input: Json): String {
        val raw = when (name) {
            "read" -> path(input)?.let { "读取 $it" + range(input) }
            "write" -> path(input)?.let { "写入 $it（${lineCount(input.str("content"))} 行）" }
            "edit" -> path(input)?.let { "编辑 $it" + replaceAll(input) }
            "ls" -> path(input)?.let { "列出 $it" } ?: "列出 workspace"
            "glob" -> input.str("pattern")?.let { "匹配 $it" + inPath(input) }
            "grep" -> input.str("pattern")?.let { "搜索 “$it”" + inPath(input) }
            "bash" -> (input.str("command") ?: input.str("cmd"))?.let { "运行 $it" }
            "todo" -> todoSummary(input)
            "skill" -> input.str("name")?.let { "加载技能 $it" }
            else -> null
        }
        val text = raw ?: dev.pocket.agent.ui.text.JsonPretty.oneLine(input)
        return if (text.length <= MAX) text else text.take(MAX - 1) + "…"
    }

    private fun path(input: Json): String? = input.str("path")?.takeIf { it.isNotBlank() }

    private fun range(input: Json): String {
        val offset = input.int("offset")
        val limit = input.int("limit")
        return when {
            offset == null && limit == null -> ""
            else -> "（offset=${offset ?: 1}, limit=${limit ?: "默认"}）"
        }
    }

    private fun replaceAll(input: Json): String =
        if (input.bool("replace_all") == true) "（全部替换）" else ""

    private fun inPath(input: Json): String =
        input.str("path")?.takeIf { it.isNotBlank() }?.let { " @ $it" } ?: ""

    private fun lineCount(content: String?): Int =
        if (content.isNullOrEmpty()) 0 else content.count { it == '\n' } + 1

    private fun todoSummary(input: Json): String {
        val items = input.array("items").ifEmpty { input.array("todos") }
        if (items.isEmpty()) return "清空待办"
        val pending = items.count { it.str("status") != "completed" }
        return "更新待办（${items.size} 项，$pending 项未完成）"
    }
}
