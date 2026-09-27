package dev.mikhailtail.handyagent.ui.timeline

import dev.mikhailtail.handyagent.core.context.CompactionResult
import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.loop.AgentEvent
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.Role
import dev.mikhailtail.handyagent.core.model.StopReason
import dev.mikhailtail.handyagent.core.provider.TokenUsage
import dev.mikhailtail.handyagent.core.tool.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loop 9：UI 时间轴的归约逻辑（纯 Kotlin，可在宿主 JVM 上验证）。 */
class TimelineReducerTest {

    private val reducer = TimelineReducer()

    private fun assistant(text: String, tool: String? = null, id: String = "t1"): AgentEvent.AssistantMessage {
        val blocks = ArrayList<Block>()
        if (text.isNotEmpty()) blocks.add(Block.Text(text))
        if (tool != null) blocks.add(Block.ToolUse(id, tool, Json.obj("path" to Json.Str("a.txt"))))
        return AgentEvent.AssistantMessage(Message(Role.ASSISTANT, blocks), TokenUsage(10, 4))
    }

    @Test
    fun `user message becomes a single bubble`() {
        val items = reducer.user("hello")
        assertEquals(1, items.size)
        assertEquals(TimelineItem.UserText(1, "hello"), items.single())
    }

    @Test
    fun `streaming deltas accumulate into one draft that is not duplicated by the final message`() {
        reducer.apply(AgentEvent.TextDelta("Hel"))
        val mid = reducer.apply(AgentEvent.TextDelta("lo"))
        val draft = mid.single() as TimelineItem.AssistantText
        assertTrue(draft.streaming)
        assertEquals("Hello", draft.text)

        val done = reducer.apply(assistant("Hello"))
        assertEquals("final message must not append a second item", 1, done.size)
        val final = done.single() as TimelineItem.AssistantText
        assertEquals("Hello", final.text)
        assertFalse(final.streaming)
        assertEquals("id must be stable across streaming updates", 1L, final.id)
    }

    @Test
    fun `reasoning deltas are kept separately from the answer`() {
        reducer.apply(AgentEvent.ReasoningDelta("think..."))
        reducer.apply(AgentEvent.TextDelta("answer"))
        val item = reducer.apply(assistant("answer")).single() as TimelineItem.AssistantText
        assertEquals("answer", item.text)
        assertEquals("think...", item.reasoning)
    }

    @Test
    fun `authoritative text wins over streamed deltas`() {
        reducer.apply(AgentEvent.TextDelta("partial"))
        val item = reducer.apply(assistant("partial plus more")).single() as TimelineItem.AssistantText
        assertEquals("partial plus more", item.text)
    }

    @Test
    fun `tool call card is created running then updated in place`() {
        reducer.apply(assistant("reading", tool = "read"))
        reducer.apply(AgentEvent.ToolStarted("t1", "read", Json.obj("path" to Json.Str("a.txt"))))
        val snapshot = reducer.snapshot()
        val running = snapshot.last() as TimelineItem.ToolCall
        assertEquals(TimelineItem.ToolCall.Status.RUNNING, running.status)

        val after = reducer.apply(
            AgentEvent.ToolFinished("t1", "read", ToolOutcome.ok("file body"), durationMs = 42)
        )
        assertEquals("tool completion must not add a row", snapshot.size, after.size)
        val card = after.last() as TimelineItem.ToolCall
        assertEquals(running.id, card.id)
        assertEquals(TimelineItem.ToolCall.Status.OK, card.status)
        assertEquals("file body", card.output)
        assertEquals(42L, card.durationMs)
    }

    @Test
    fun `failed tool result is marked as error`() {
        reducer.apply(AgentEvent.ToolStarted("t1", "bash", Json.obj()))
        val items = reducer.apply(
            AgentEvent.ToolFinished("t1", "bash", ToolOutcome.error("exit=1"), durationMs = 5)
        )
        assertEquals(TimelineItem.ToolCall.Status.ERROR, (items.last() as TimelineItem.ToolCall).status)
    }

    @Test
    fun `denied tool without a start event still gets a card`() {
        val items = reducer.apply(AgentEvent.ToolDenied("t9", "write", "user denied 'write'"))
        val card = items.single() as TimelineItem.ToolCall
        assertEquals(TimelineItem.ToolCall.Status.DENIED, card.status)
        assertEquals("user denied 'write'", card.output)
    }

    @Test
    fun `denied tool after a start event updates the existing card`() {
        reducer.apply(AgentEvent.ToolStarted("t1", "write", Json.obj()))
        val items = reducer.apply(AgentEvent.ToolDenied("t1", "write", "denied"))
        assertEquals(1, items.size)
        assertEquals(TimelineItem.ToolCall.Status.DENIED, (items.single() as TimelineItem.ToolCall).status)
    }

    @Test
    fun `compaction inserts a separator note`() {
        reducer.apply(AgentEvent.TextDelta("hi"))
        val items = reducer.apply(
            AgentEvent.Compacted(
                CompactionResult(
                    messages = emptyList(),
                    summary = "s",
                    compactedMessages = 7,
                    tokensBefore = 9_000,
                    tokensAfter = 2_000,
                )
            )
        )
        assertEquals(2, items.size)
        val note = items.last() as TimelineItem.Note
        assertEquals(TimelineItem.Note.Kind.COMPACTION, note.kind)
        assertTrue(note.text.contains("7"))
        // 压缩分隔线之前的草稿必须收尾，否则后续 delta 会接到旧消息上
        assertFalse((items.first() as TimelineItem.AssistantText).streaming)
    }

    @Test
    fun `completed appends a run summary and closes the draft`() {
        reducer.apply(AgentEvent.TextDelta("done"))
        val items = reducer.apply(AgentEvent.Completed(StopReason.END_TURN, TokenUsage(120, 30), 2))
        assertEquals(2, items.size)
        val summary = items.last() as TimelineItem.RunSummary
        assertEquals(StopReason.END_TURN, summary.stopReason)
        assertEquals(2, summary.iterations)
        assertEquals(TokenUsage(120, 30), summary.usage)
    }

    @Test
    fun `failed and aborted become notes`() {
        assertEquals(
            TimelineItem.Note.Kind.ERROR,
            (reducer.apply(AgentEvent.Failed("boom", 1)).last() as TimelineItem.Note).kind,
        )
        assertEquals(
            TimelineItem.Note.Kind.INFO,
            (reducer.apply(AgentEvent.Aborted(3)).last() as TimelineItem.Note).kind,
        )
    }

    @Test
    fun `two iterations produce two assistant items in order`() {
        reducer.apply(AgentEvent.TextDelta("first"))
        reducer.apply(assistant("first", tool = "read"))
        reducer.apply(AgentEvent.ToolStarted("t1", "read", Json.obj()))
        reducer.apply(AgentEvent.ToolFinished("t1", "read", ToolOutcome.ok("x"), durationMs = 0))
        reducer.apply(AgentEvent.TextDelta("second"))
        reducer.apply(assistant("second"))

        val assistants = reducer.snapshot().filterIsInstance<TimelineItem.AssistantText>()
        assertEquals(2, assistants.size)
        assertEquals("first", assistants[0].text)
        assertEquals("second", assistants[1].text)
        assertTrue(assistants[0].id < assistants[1].id)
    }

    @Test
    fun `clear resets ids and content`() {
        reducer.apply(AgentEvent.TextDelta("x"))
        reducer.clear()
        assertTrue(reducer.snapshot().isEmpty())
        val items = reducer.user("again")
        assertEquals(1L, (items.single() as TimelineItem.UserText).id)
    }
}
