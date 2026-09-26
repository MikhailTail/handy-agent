package dev.pocket.agent.core.provider

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.model.Block
import dev.pocket.agent.core.model.ChatRequest
import dev.pocket.agent.core.model.Message
import dev.pocket.agent.core.model.ReasoningEffort
import dev.pocket.agent.core.model.Role
import dev.pocket.agent.core.model.StopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loop 9：上下文压缩用的真实模型摘要（替代此前的「只截断不总结」）。 */
class ProviderSummarizerTest {

    /** 记录请求、按脚本回放事件的极简假 provider。 */
    private class FakeProvider(
        private val events: (ChatRequest) -> List<ProviderEvent>,
    ) : LlmProvider {
        val requests = ArrayList<ChatRequest>()
        override val id = "fake"
        override val displayName = "Fake"
        override val config = ProviderConfig(
            id = "fake",
            name = "Fake",
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "http://localhost",
            apiKey = "k",
            defaultModel = "fake-model",
        )

        override fun stream(req: ChatRequest): Flow<ProviderEvent> = flow {
            requests.add(req)
            events(req).forEach { emit(it) }
        }

        override suspend fun listModels(): List<String> = listOf(config.defaultModel)
    }

    private fun textOnly(content: String) = FakeProvider {
        listOf(
            ProviderEvent.TextDelta(content),
            ProviderEvent.Completed(listOf(Block.Text(content)), StopReason.END_TURN, TokenUsage(5, 5)),
        )
    }

    private val history = listOf(
        Message.user("add a feature to src/App.kt"),
        Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "read", Json.obj("path" to Json.Str("src/App.kt"))))),
        Message.toolResults(listOf(Block.ToolResult("t1", "fun main() {}"))),
        Message.assistant("done"),
        Message.user("now add tests"),
    )

    @Test
    fun `streams deltas and returns the trimmed summary`() = runBlocking {
        val provider = textOnly("  Files touched: src/App.kt  ")
        val summarizer = ProviderSummarizer(provider = { provider })

        val summary = summarizer.summarize(history)

        assertEquals("Files touched: src/App.kt", summary)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `falls back to completed text when the provider emits no deltas`() = runBlocking {
        val provider = FakeProvider {
            listOf(
                ProviderEvent.Completed(listOf(Block.Text("from completed")), StopReason.END_TURN),
            )
        }
        val summarizer = ProviderSummarizer(provider = { provider })

        assertEquals("from completed", summarizer.summarize(history))
    }

    @Test
    fun `request collapses history into one user message with tools disabled`() = runBlocking {
        val provider = textOnly("x")
        ProviderSummarizer(provider = { provider }).summarize(history)

        val req = provider.requests.single()
        assertEquals("fake-model", req.model)
        assertEquals(1, req.messages.size)
        assertEquals(Role.USER, req.messages.single().role)
        assertTrue("no tools should be advertised", req.tools.isEmpty())
        assertEquals(ReasoningEffort.OFF, req.effort)
        // 转录必须带上关键事实：路径与工具名
        assertTrue(req.messages.single().text.contains("src/App.kt"))
        assertTrue(req.messages.single().text.contains("read"))
    }

    @Test
    fun `long tool output is truncated inside the transcript`() = runBlocking {
        val provider = textOnly("x")
        val huge = "y".repeat(10_000)
        val msgs = listOf(
            Message.user("go"),
            Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "bash", Json.obj()))),
            Message.toolResults(listOf(Block.ToolResult("t1", huge))),
        )
        ProviderSummarizer(provider = { provider }).summarize(msgs)

        val sent = provider.requests.single().messages.single().text
        assertFalse("huge tool output must not be forwarded verbatim", sent.contains(huge))
        assertTrue(sent.length < 3_000)
    }

    @Test
    fun `summary longer than the cap is clipped`() = runBlocking {
        val provider = textOnly("z".repeat(50_000))
        val summary = ProviderSummarizer(provider = { provider }).summarize(history)
        assertEquals(8_000, summary.length)
    }

    @Test
    fun `empty history short-circuits without touching the provider`() = runBlocking {
        val provider = textOnly("never")
        assertEquals("", ProviderSummarizer(provider = { provider }).summarize(emptyList()))
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `no provider throws so the caller can degrade instead of hanging`(): Unit = runBlocking {
        val summarizer = ProviderSummarizer(provider = { null })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { summarizer.summarize(history) }
        }
        Unit
    }

    @Test
    fun `provider failure surfaces as an exception`() {
        val provider = FakeProvider { listOf(ProviderEvent.Failure("boom")) }
        val summarizer = ProviderSummarizer(provider = { provider })
        assertThrows(IllegalStateException::class.java) {
            runBlocking { summarizer.summarize(history) }
        }
    }
}
