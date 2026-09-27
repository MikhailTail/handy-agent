package dev.mikhailtail.handyagent.core.model

import dev.mikhailtail.handyagent.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageBoundariesTest {

    private fun toolUseTurn(id: String) =
        Message(Role.ASSISTANT, listOf(Block.ToolUse(id, "read", Json.obj())))

    private fun toolResultTurn(id: String) =
        Message.toolResults(listOf(Block.ToolResult(id, "ok")))

    /** u1, a(tool_use), u2(tool_result), a2 */
    private val history = listOf(
        Message.user("hi"),
        toolUseTurn("t1"),
        toolResultTurn("t1"),
        Message.assistant("done"),
    )

    @Test
    fun `a cut that would split tool use from its result moves left`() {
        // 想切在 2 处 → 会把 tool_result 留给右侧，tool_use 孤零零留在左侧
        assertEquals(1, MessageBoundaries.snapToValidCut(history, 2))
    }

    @Test
    fun `a cut at a natural boundary is kept as is`() {
        assertEquals(3, MessageBoundaries.snapToValidCut(history, 3))
        assertEquals(4, MessageBoundaries.snapToValidCut(history, 4))
    }

    @Test
    fun `requests outside the range are clamped`() {
        assertEquals(0, MessageBoundaries.snapToValidCut(history, -5))
        assertEquals(4, MessageBoundaries.snapToValidCut(history, 99))
        assertEquals(0, MessageBoundaries.snapToValidCut(emptyList(), 3))
    }

    @Test
    fun `consecutive tool result turns are all stepped over`() {
        val twoCalls = listOf(
            Message.user("hi"),
            toolUseTurn("t1"),
            toolResultTurn("t1"),
            toolUseTurn("t2"),
            toolResultTurn("t2"),
        )
        // 切在 4 会让 t2 的 tool_use 没有结果，必须左移到 3（保留到 t1 的结果为止，配对完整）
        assertEquals(3, MessageBoundaries.snapToValidCut(twoCalls, 4))
    }

    @Test
    fun `a plain user turn is recognised as a safe boundary`() {
        assertEquals(1, MessageBoundaries.snapToValidCut(history, 1))
    }
}
