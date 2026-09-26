package dev.pocket.agent.ui.export

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.model.StopReason
import dev.pocket.agent.core.provider.TokenUsage
import dev.pocket.agent.ui.timeline.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    private fun tool(
        name: String = "write_file",
        status: TimelineItem.ToolCall.Status = TimelineItem.ToolCall.Status.OK,
        output: String = "ok",
    ) = TimelineItem.ToolCall(
        id = 2,
        toolUseId = "t1",
        name = name,
        input = Json.parse("""{"path":"a.txt","content":"hi"}"""),
        status = status,
        output = output,
        durationMs = 12,
    )

    @Test
    fun `renders the whole conversation in order`() {
        val md = Transcript.markdown(
            listOf(
                TimelineItem.UserText(0, "写个文件"),
                TimelineItem.AssistantText(1, "好的", reasoning = "先看看目录"),
                tool(),
                TimelineItem.RunSummary(3, StopReason.END_TURN, TokenUsage(10, 5), iterations = 2),
            ),
            title = "会话",
            generatedAt = "2026-09-22 14:30",
        )

        assertTrue(md.startsWith("# 会话\n"))
        assertTrue(md.contains("> 导出时间：2026-09-22 14:30"))
        assertTrue(md.indexOf("## 我") < md.indexOf("## 助手"))
        assertTrue(md.indexOf("## 助手") < md.indexOf("### 工具 · write_file"))
        assertTrue(md.contains("先看看目录"))
        assertTrue(md.contains("\"path\": \"a.txt\""))
        assertTrue(md.contains("本轮结束：END_TURN · 迭代 2 · 用量 10 in / 5 out"))
    }

    @Test
    fun `tool status is spelled out for every terminal state`() {
        listOf(
            TimelineItem.ToolCall.Status.RUNNING to "执行中",
            TimelineItem.ToolCall.Status.OK to "成功",
            TimelineItem.ToolCall.Status.ERROR to "失败",
            TimelineItem.ToolCall.Status.DENIED to "已拒绝",
        ).forEach { (status, label) ->
            val md = Transcript.markdown(listOf(tool(status = status)))
            assertTrue("$status 应显示为 $label", md.contains("工具 · write_file · $label"))
        }
    }

    @Test
    fun `huge tool output is clipped with an explicit marker`() {
        val long = "x".repeat(Transcript.MAX_TOOL_OUTPUT + 500)
        val md = Transcript.markdown(listOf(tool(output = long)))

        assertTrue(md.contains("已截断，原文 ${long.length} 字符"))
        assertFalse(md.contains(long))
    }

    @Test
    fun `unfinished assistant turn is marked instead of looking complete`() {
        val md = Transcript.markdown(
            listOf(TimelineItem.AssistantText(0, "半句", streaming = true)),
        )
        assertTrue(md.contains("生成中，记录到此为止"))
    }

    @Test
    fun `empty timeline still produces a readable file`() {
        val md = Transcript.markdown(emptyList())
        assertEquals("# Pocket Agent 对话记录\n\n_（本次会话没有内容）_\n", md)
    }

    @Test
    fun `notes and errors survive as quotes`() {
        val md = Transcript.markdown(
            listOf(
                TimelineItem.Note(0, TimelineItem.Note.Kind.COMPACTION, "压缩了 4 轮"),
                TimelineItem.Note(1, TimelineItem.Note.Kind.ERROR, "断线\n重试失败"),
            ),
        )
        assertTrue(md.contains("> **上下文压缩** 压缩了 4 轮"))
        assertTrue("备注不应打断引用块", md.contains("> **错误** 断线 重试失败"))
    }

    @Test
    fun `file name carries the timestamp`() {
        assertEquals("pocket-agent-20260922-143005.md", Transcript.fileName("20260922-143005"))
    }
}
