package dev.mikhailtail.handyagent.core.context

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.Role
import dev.mikhailtail.handyagent.core.model.ToolSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {

    private fun user(text: String) = Message.user(text)
    private fun assistant(text: String) = Message.assistant(text)

    private fun assistantToolUse(id: String) = Message(
        Role.ASSISTANT,
        listOf(Block.Text("let me check"), Block.ToolUse(id, "read", Json.obj("path" to Json.Str("a.txt")))),
    )

    private fun toolResult(id: String) = Message.toolResults(
        listOf(Block.ToolResult(id, "file contents"))
    )

    /** 造 N 轮「user → assistant」，每轮文本很长以便触发压缩。 */
    private fun longConversation(turns: Int, chars: Int = 400): List<Message> {
        val msgs = mutableListOf<Message>()
        for (i in 1..turns) {
            msgs.add(user("user turn $i " + "a".repeat(chars)))
            msgs.add(assistant("assistant reply $i " + "b".repeat(chars)))
        }
        return msgs
    }

    private val echoSummarizer = Summarizer { msgs -> "summary of ${msgs.size} messages" }

    // --- 估算 -------------------------------------------------------------

    @Test
    fun `token estimate grows with content and counts tool schemas`() {
        val short = TokenEstimator.estimate("hi")
        val long = TokenEstimator.estimate("x".repeat(3000))
        assertTrue(long > short)

        val tools = listOf(ToolSpec("read", "read a file", Json.obj("type" to Json.Str("object"))))
        val withTools = TokenEstimator.estimateTotal("sys", listOf(user("hello")), tools)
        val withoutTools = TokenEstimator.estimateTotal("sys", listOf(user("hello")), emptyList())
        assertTrue(withTools > withoutTools)
    }

    @Test
    fun `empty input estimates zero`() {
        assertEquals(0, TokenEstimator.estimate(""))
    }

    @Test
    fun `estimate of message includes all block kinds`() {
        val m = Message(
            Role.ASSISTANT,
            listOf(
                Block.Text("hello"),
                Block.Reasoning("thinking hard"),
                Block.ToolUse("t1", "bash", Json.obj("command" to Json.Str("ls -la"))),
            ),
        )
        assertTrue(TokenEstimator.estimate(m) > TokenEstimator.estimate("hello"))
    }

    @Test
    fun `budget derives input limit and trigger`() {
        val b = ContextBudget(maxTokens = 10_000, threshold = 0.8, reserveOutputTokens = 2_000)
        assertEquals(8_000, b.inputLimit)
        assertEquals(6_400, b.triggerTokens)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `budget rejects invalid threshold`() {
        ContextBudget(maxTokens = 1000, threshold = 0.0)
    }

    // --- 切分边界 ---------------------------------------------------------

    @Test
    fun `split point keeps the last N turns`() {
        val msgs = longConversation(turns = 5)
        val cm = ContextManager(ContextBudget(maxTokens = 100_000, keepTurns = 2))
        // 5 轮 → 10 条消息；每轮 2 条，保留最后 2 轮 → 从索引 6 开始
        assertEquals(6, cm.splitPoint(msgs))
    }

    @Test
    fun `short conversation has nothing to compact`() {
        val msgs = longConversation(turns = 2)
        val cm = ContextManager(ContextBudget(maxTokens = 100_000, keepTurns = 3))
        assertEquals(0, cm.splitPoint(msgs))
        assertFalse(cm.shouldCompact("", msgs, emptyList()))
    }

    @Test
    fun `tool result messages do not start a new turn`() {
        val msgs = listOf(
            user("turn 1"),
            assistantToolUse("t1"),
            toolResult("t1"),
            user("turn 2"),
            assistant("done"),
        )
        val cm = ContextManager(ContextBudget(maxTokens = 100_000, keepTurns = 1))
        // 保留最后 1 轮 → 从 "turn 2" 的索引 3 开始
        assertEquals(3, cm.splitPoint(msgs))
    }

    // --- 压缩 -------------------------------------------------------------

    @Test
    fun `compaction preserves the trailing turns verbatim`() = runBlocking {
        val msgs = longConversation(turns = 6)
        val cm = ContextManager(ContextBudget(maxTokens = 10_000, keepTurns = 2, reserveOutputTokens = 0))

        val result = cm.compact(msgs, echoSummarizer, force = true)

        assertTrue(result.didCompact)
        // 最后 4 条（2 轮）原样保留
        assertEquals(msgs.takeLast(4), result.messages.takeLast(4))
        // 头部是一条摘要消息
        assertEquals(1, result.messages.size - 4)
        assertEquals(Role.USER, result.messages.first().role)
        assertTrue(result.messages.first().text.startsWith(COMPACTION_MARKER))
        assertTrue(result.messages.first().text.contains("summary of 8 messages"))
        assertTrue(result.tokensAfter < result.tokensBefore)
    }

    @Test
    fun `compaction never orphans a tool result`() = runBlocking {
        val msgs = listOf(
            user("turn 1 " + "a".repeat(2000)),
            assistantToolUse("t1"),
            toolResult("t1"),
            user("turn 2 " + "b".repeat(2000)),
            assistant("final"),
        )
        val cm = ContextManager(ContextBudget(maxTokens = 10_000, keepTurns = 1, reserveOutputTokens = 0))
        val result = cm.compact(msgs, echoSummarizer, force = true)

        // 保留下来的第一条必须是有文本的用户轮，不能是孤立的 tool_result
        val firstKept = result.messages.first { it.text.startsWith("turn 2") || it.hasToolResult() }
        assertFalse("first kept message must not be a bare tool_result", firstKept.blocks.all { it is Block.ToolResult })
        // tool_use / tool_result 要么都被折叠，要么都保留
        val keptToolUseIds = result.messages.flatMap { it.toolUses }.map { it.id }.toSet()
        val keptToolResultIds = result.messages.flatMap { it.toolResults }.map { it.toolUseId }.toSet()
        assertTrue(keptToolResultIds.containsAll(keptToolUseIds))
    }

    private fun Message.hasToolResult(): Boolean = toolResults.isNotEmpty()

    @Test
    fun `below threshold and not forced is a no-op`() = runBlocking {
        val msgs = longConversation(turns = 6)
        val cm = ContextManager(ContextBudget(maxTokens = 1_000_000, keepTurns = 2, reserveOutputTokens = 0))
        val result = cm.compact(msgs, echoSummarizer)
        assertFalse(result.didCompact)
        assertSame(msgs, result.messages)
    }

    @Test
    fun `failing summarizer degrades to trimming and still compacts`() = runBlocking {
        val msgs = longConversation(turns = 6)
        val cm = ContextManager(ContextBudget(maxTokens = 10_000, keepTurns = 2, reserveOutputTokens = 0))
        val boom = Summarizer { throw RuntimeException("provider down") }

        val result = cm.compact(msgs, boom, force = true)

        assertTrue(result.didCompact)
        assertEquals("", result.summary)
        assertTrue(result.messages.first().text.contains("dropped"))
        assertEquals(msgs.takeLast(4), result.messages.takeLast(4))
    }

    @Test
    fun `blank summary is treated as unavailable`() = runBlocking {
        val msgs = longConversation(turns = 6)
        val cm = ContextManager(ContextBudget(maxTokens = 10_000, keepTurns = 2, reserveOutputTokens = 0))
        val blank = Summarizer { "   " }
        val result = cm.compact(msgs, blank, force = true)
        assertTrue(result.messages.first().text.contains("were dropped"))
    }

    @Test
    fun `repeated compaction stays stable`() = runBlocking {
        var msgs = longConversation(turns = 8)
        val cm = ContextManager(ContextBudget(maxTokens = 10_000, keepTurns = 2, reserveOutputTokens = 0))
        repeat(3) { round ->
            val result = cm.compact(msgs, echoSummarizer, force = true)
            if (!result.didCompact) return@repeat
            msgs = result.messages
            assertTrue("round $round must shrink", result.tokensAfter <= result.tokensBefore)
        }
        // 压缩后仍保有完整尾部
        assertTrue(msgs.size >= 4)
        assertEquals(Role.USER, msgs.first().role)
    }

    @Test
    fun `shouldCompact triggers only past the threshold`() {
        val cm = ContextManager(ContextBudget(maxTokens = 5_000, threshold = 0.5, keepTurns = 1, reserveOutputTokens = 0))
        val small = longConversation(turns = 4, chars = 10)
        val huge = longConversation(turns = 4, chars = 4_000)
        assertFalse(cm.shouldCompact("", small, emptyList()))
        assertTrue(cm.shouldCompact("", huge, emptyList()))
    }
}
