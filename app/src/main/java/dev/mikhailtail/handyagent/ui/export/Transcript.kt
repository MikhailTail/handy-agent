package dev.mikhailtail.handyagent.ui.export

import dev.mikhailtail.handyagent.ui.text.JsonPretty
import dev.mikhailtail.handyagent.ui.timeline.TimelineItem

/**
 * 把时间轴渲染成一份 Markdown 对话记录。
 *
 * 与 UI 无关的纯函数：输入是不可变的 [TimelineItem] 快照，输出是文本，
 * 因此「导出的记录和屏幕上看到的一致」这件事可以被单测钉住（见 TranscriptTest）。
 *
 * 大段工具输出会被截断 —— 导出的是**记录**，不是日志转储；截断处显式标注，
 * 不让人误以为原文就这么短。
 */
object Transcript {

    const val MAX_TOOL_OUTPUT = 4_000

    fun markdown(
        items: List<TimelineItem>,
        title: String = "Handy Agent 对话记录",
        generatedAt: String = "",
    ): String {
        val out = StringBuilder()
        out.append("# ").append(title).append('\n')
        if (generatedAt.isNotBlank()) out.append("\n> 导出时间：").append(generatedAt).append('\n')
        out.append('\n')

        if (items.isEmpty()) {
            out.append("_（本次会话没有内容）_\n")
            return out.toString()
        }

        for (item in items) {
            when (item) {
                is TimelineItem.UserText -> {
                    out.append("## 我\n\n").append(item.text.trim()).append("\n\n")
                }

                is TimelineItem.AssistantText -> {
                    out.append("## 助手\n\n")
                    val body = item.text.trim()
                    out.append(if (body.isEmpty()) "_（无正文）_" else body).append("\n\n")
                    if (item.streaming) out.append("> _生成中，记录到此为止_\n\n")
                    val thinking = item.reasoning.trim()
                    if (thinking.isNotEmpty()) {
                        out.append("<details><summary>思考过程</summary>\n\n")
                            .append(thinking).append("\n\n</details>\n\n")
                    }
                }

                is TimelineItem.ToolCall -> {
                    out.append("### 工具 · ").append(item.name).append(" · ")
                        .append(statusLabel(item.status))
                    if (item.durationMs > 0) out.append("（").append(item.durationMs).append(" ms）")
                    out.append("\n\n")
                    out.append("```json\n")
                        .append(JsonPretty.pretty(item.input))
                        .append("\n```\n\n")
                    val output = item.output
                    if (output.isNotBlank()) {
                        out.append("```\n")
                            .append(clip(output))
                            .append("\n```\n\n")
                    }
                }

                is TimelineItem.Note -> {
                    out.append("> **").append(noteLabel(item.kind)).append("** ")
                        .append(item.text.replace("\n", " ")).append("\n\n")
                }

                is TimelineItem.RunSummary -> {
                    out.append("---\n\n")
                        .append("> 本轮结束：").append(item.stopReason.name)
                        .append(" · 迭代 ").append(item.iterations)
                        .append(" · 用量 ").append(item.usage.inputTokens)
                        .append(" in / ").append(item.usage.outputTokens).append(" out\n\n")
                }
            }
        }
        return out.toString()
    }

    /** 默认导出文件名：`pocket-agent-<时间戳>.md`（与 workspace 导出一致的命名风格）。 */
    fun fileName(timestamp: String): String = "pocket-agent-$timestamp.md"

    private fun clip(text: String): String =
        if (text.length <= MAX_TOOL_OUTPUT) text
        else text.take(MAX_TOOL_OUTPUT) + "\n…（已截断，原文 ${text.length} 字符）"

    private fun statusLabel(status: TimelineItem.ToolCall.Status): String = when (status) {
        TimelineItem.ToolCall.Status.RUNNING -> "执行中"
        TimelineItem.ToolCall.Status.OK -> "成功"
        TimelineItem.ToolCall.Status.ERROR -> "失败"
        TimelineItem.ToolCall.Status.DENIED -> "已拒绝"
    }

    private fun noteLabel(kind: TimelineItem.Note.Kind): String = when (kind) {
        TimelineItem.Note.Kind.INFO -> "提示"
        TimelineItem.Note.Kind.COMPACTION -> "上下文压缩"
        TimelineItem.Note.Kind.ERROR -> "错误"
    }
}
