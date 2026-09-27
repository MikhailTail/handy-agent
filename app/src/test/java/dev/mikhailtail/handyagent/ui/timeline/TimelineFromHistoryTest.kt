package dev.mikhailtail.handyagent.ui.timeline

import dev.mikhailtail.handyagent.core.context.COMPACTION_MARKER
import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 从落盘历史重建时间轴。切换会话、冷启动恢复都走这条路径，
 * 如果重建得不对，用户看到的就是「会话打开了但内容是空的 / 工具卡片永远转圈」。
 */
class TimelineFromHistoryTest {

    @Test
    fun `an empty history yields an empty timeline`() {
        assertTrue(TimelineReducer.fromHistory(emptyList()).isEmpty())
    }

    @Test
    fun `user and assistant turns become settled text items`() {
        val items = TimelineReducer.fromHistory(
            listOf(Message.user("你好"), Message.assistant("在的"))
        )

        assertEquals(2, items.size)
        assertEquals("你好", (items[0] as TimelineItem.UserText).text)
        val reply = items[1] as TimelineItem.AssistantText
        assertEquals("在的", reply.text)
        assertFalse("重建的条目不应是流式草稿", reply.streaming)
    }

    @Test
    fun `a tool card is filled in from its matching result`() {
        val items = TimelineReducer.fromHistory(
            listOf(
                Message.user("读一下"),
                Message(
                    Role.ASSISTANT,
                    listOf(Block.ToolUse("t1", "read", Json.obj("path" to Json.of("a.txt")))),
                ),
                Message.toolResults(listOf(Block.ToolResult("t1", "文件内容"))),
            )
        )

        val card = items.filterIsInstance<TimelineItem.ToolCall>().single()
        assertEquals("read", card.name)
        assertEquals(TimelineItem.ToolCall.Status.OK, card.status)
        assertEquals("文件内容", card.output)
        // 纯 tool_result 的那条 USER 消息不该再冒出一个用户气泡
        assertEquals(1, items.count { it is TimelineItem.UserText })
    }

    @Test
    fun `an error result marks the card as errored`() {
        val items = TimelineReducer.fromHistory(
            listOf(
                Message(
                    Role.ASSISTANT,
                    listOf(Block.ToolUse("t1", "bash", Json.obj())),
                ),
                Message.toolResults(listOf(Block.ToolResult("t1", "命令失败", isError = true))),
            )
        )
        assertEquals(TimelineItem.ToolCall.Status.ERROR, items.filterIsInstance<TimelineItem.ToolCall>().single().status)
    }

    @Test
    fun `a tool use without a result stays running rather than pretending to be done`() {
        val items = TimelineReducer.fromHistory(
            listOf(
                Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "read", Json.obj()))),
            )
        )
        assertEquals(
            TimelineItem.ToolCall.Status.RUNNING,
            items.filterIsInstance<TimelineItem.ToolCall>().single().status,
        )
    }

    @Test
    fun `a compaction summary renders as a note and not as a user bubble`() {
        val items = TimelineReducer.fromHistory(
            listOf(Message.user("$COMPACTION_MARKER\n早期对话已折叠"))
        )

        val note = items.single() as TimelineItem.Note
        assertEquals(TimelineItem.Note.Kind.COMPACTION, note.kind)
        assertTrue(note.text.contains("早期对话已折叠"))
    }

    @Test
    fun `item ids are unique and start at one`() {
        val items = TimelineReducer.fromHistory(
            listOf(
                Message.user("a"),
                Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "read", Json.obj()))),
                Message.toolResults(listOf(Block.ToolResult("t1", "ok"))),
                Message.assistant("b"),
            )
        )
        val ids = items.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(1L, ids.first())
    }

    @Test
    fun `restoring into the reducer keeps appending without id collisions`() {
        val reducer = TimelineReducer()
        reducer.replaceAll(
            TimelineReducer.fromHistory(listOf(Message.user("旧"), Message.assistant("话")))
        )

        val after = reducer.user("新的一句")

        val ids = after.map { it.id }
        assertEquals("重建后的 id 必须继续递增，不能与既有条目撞 key", ids.distinct(), ids)
        assertEquals(3, after.size)
    }
}
