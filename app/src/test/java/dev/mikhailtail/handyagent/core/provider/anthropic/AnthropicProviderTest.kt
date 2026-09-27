package dev.mikhailtail.handyagent.core.provider.anthropic

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.ChatRequest
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.ReasoningEffort
import dev.mikhailtail.handyagent.core.model.StopReason
import dev.mikhailtail.handyagent.core.model.ToolSpec
import dev.mikhailtail.handyagent.core.provider.FakeHttpEngine
import dev.mikhailtail.handyagent.core.provider.ProviderConfig
import dev.mikhailtail.handyagent.core.provider.ProviderEvent
import dev.mikhailtail.handyagent.core.provider.ProviderKind
import dev.mikhailtail.handyagent.core.provider.TokenUsage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AnthropicProviderTest {

    private fun cfg(base: String = "https://api.anthropic.com") = ProviderConfig(
        id = "anthropic",
        name = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = base,
        apiKey = "sk-test",
        defaultModel = "claude-sonnet-4-5",
    )

    private fun provider(engine: FakeHttpEngine, base: String = "https://api.anthropic.com") =
        AnthropicProvider(cfg(base), engine)

    private fun req(
        effort: ReasoningEffort = ReasoningEffort.OFF,
        messages: List<Message> = listOf(Message.user("hi")),
        tools: List<ToolSpec> = emptyList(),
        system: String = "",
        maxTokens: Int = 1_024,
        temperature: Double? = null,
    ) = ChatRequest(
        model = "claude-sonnet-4-5",
        system = system,
        messages = messages,
        tools = tools,
        effort = effort,
        maxTokens = maxTokens,
        temperature = temperature,
    )

    // ------------------------------------------------------------ 流式解析

    @Test
    fun `text deltas accumulate into a single text block`() = runTest {
        val engine = FakeHttpEngine(
            lines = FakeHttpEngine.sse(
                "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":12,"output_tokens":1}}}""",
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
                "message_stop" to """{"type":"message_stop"}""",
            )
        )
        val events = provider(engine).stream(req()).toList()

        assertEquals(listOf("Hel", "lo"), events.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
        val done = events.last() as ProviderEvent.Completed
        assertEquals(StopReason.END_TURN, done.stopReason)
        assertEquals(TokenUsage(12, 2), done.usage)
        assertEquals(listOf(Block.Text("Hello")), done.blocks)
    }

    @Test
    fun `thinking deltas keep signature for replay`() = runTest {
        val engine = FakeHttpEngine(
            lines = FakeHttpEngine.sse(
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"let me "}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"think"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG"}}""",
            )
        )
        val events = provider(engine).stream(req(effort = ReasoningEffort.MEDIUM)).toList()
        assertEquals("let me think", events.filterIsInstance<ProviderEvent.ReasoningDelta>().joinToString("") { it.text })

        val done = events.last() as ProviderEvent.Completed
        val r = done.blocks.single() as Block.Reasoning
        assertEquals("let me think", r.text)
        assertEquals("SIG", r.signature)
    }

    @Test
    fun `tool use input json shards are accumulated`() = runTest {
        val engine = FakeHttpEngine(
            lines = FakeHttpEngine.sse(
                "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"a.txt\"}"}}""",
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
            )
        )
        val events = provider(engine).stream(req()).toList()

        val started = events.filterIsInstance<ProviderEvent.ToolCallStarted>().single()
        assertEquals("read_file", started.name)
        assertEquals("toolu_1", started.id)

        val done = events.last() as ProviderEvent.Completed
        assertEquals(StopReason.TOOL_USE, done.stopReason)
        val call = done.blocks.single() as Block.ToolUse
        assertEquals("toolu_1", call.id)
        assertEquals("a.txt", call.input.str("path"))
    }

    @Test
    fun `malformed tool json degrades to empty object without crashing`() = runTest {
        val engine = FakeHttpEngine(
            lines = FakeHttpEngine.sse(
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"bash"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{oops"}}""",
            )
        )
        val done = provider(engine).stream(req()).toList().last() as ProviderEvent.Completed
        val call = done.blocks.single() as Block.ToolUse
        assertTrue(call.input is Json.Obj)
        assertEquals("bash", call.name)
    }

    @Test
    fun `error event becomes Failure`() = runTest {
        val engine = FakeHttpEngine(
            lines = FakeHttpEngine.sse("error" to """{"type":"error","error":{"message":"overloaded"}}""")
        )
        val events = provider(engine).stream(req()).toList()
        val f = events.last() as ProviderEvent.Failure
        assertEquals("overloaded", f.message)
        assertFalse(events.any { it is ProviderEvent.Completed })
    }

    @Test
    fun `transport exception becomes Failure not crash`() = runTest {
        val engine = FakeHttpEngine(failWith = IOException("boom"))
        val events = provider(engine).stream(req()).toList()
        assertTrue(events.single() is ProviderEvent.Failure)
    }

    @Test
    fun `stop reason defaults to tool_use when tool blocks present`() = runTest {
        val engine = FakeHttpEngine(
            lines = FakeHttpEngine.sse(
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"bash"}}"""
            )
        )
        val done = provider(engine).stream(req()).toList().last() as ProviderEvent.Completed
        assertEquals(StopReason.TOOL_USE, done.stopReason)
    }

    @Test
    fun `comment and ping lines are ignored`() = runTest {
        val engine = FakeHttpEngine(
            lines = FakeHttpEngine.sse("ping" to """{"type":"ping"}""") + listOf(": keep-alive", "")
        )
        val done = provider(engine).stream(req()).toList().last() as ProviderEvent.Completed
        assertEquals(StopReason.END_TURN, done.stopReason)
        assertNull(done.blocks.firstOrNull())
    }

    // ------------------------------------------------------------ 请求体映射

    @Test
    fun `request body maps system tools and reasoning budget`() {
        val engine = FakeHttpEngine()
        val body = provider(engine).buildBody(
            req(
                effort = ReasoningEffort.HIGH,
                system = "you are pocket agent",
                tools = listOf(ToolSpec("read_file", "read", Json.obj("type" to Json.of("object")))),
                temperature = 0.7,
            )
        )
        assertEquals("claude-sonnet-4-5", body.str("model"))
        assertEquals("you are pocket agent", body.str("system"))
        assertEquals(true, body.bool("stream"))
        assertEquals(24_576, body.obj("thinking")?.int("budget_tokens"))
        assertEquals("enabled", body.obj("thinking")?.str("type"))
        // thinking 开启时不得发送 temperature
        assertNull(body.double("temperature"))
        // max_tokens 必须大于 budget
        assertTrue(body.int("max_tokens")!! > 24_576)
        assertEquals("read_file", body.array("tools").single().str("name"))
        assertEquals("object", body.array("tools").single().obj("input_schema")?.str("type"))
    }

    @Test
    fun `temperature is sent when thinking is off`() {
        val body = provider(FakeHttpEngine()).buildBody(req(temperature = 0.3))
        assertEquals(0.3, body.double("temperature")!!, 1e-9)
        assertNull(body.obj("thinking"))
    }

    @Test
    fun `signed thinking is replayed and unsigned is dropped`() {
        val messages = listOf(
            Message(
                role = dev.mikhailtail.handyagent.core.model.Role.ASSISTANT,
                blocks = listOf(
                    Block.Reasoning("signed thought", signature = "S1"),
                    Block.Reasoning("unsigned thought"),
                    Block.Text("answer"),
                ),
            )
        )
        val body = provider(FakeHttpEngine()).buildBody(req(messages = messages))
        val content = body.array("messages").single().array("content")
        assertEquals(listOf("thinking", "text"), content.mapNotNull { it.str("type") })
        assertEquals("S1", content.first().str("signature"))
    }

    @Test
    fun `tool results are ordered before text within a user message`() {
        val messages = listOf(
            Message(
                role = dev.mikhailtail.handyagent.core.model.Role.USER,
                blocks = listOf(
                    Block.Text("here is the output"),
                    Block.ToolResult("toolu_1", "file contents"),
                ),
            )
        )
        val content = provider(FakeHttpEngine())
            .buildBody(req(messages = messages))
            .array("messages").single().array("content")
        assertEquals("tool_result", content.first().str("type"))
        assertEquals("toolu_1", content.first().str("tool_use_id"))
    }

    @Test
    fun `redacted thinking is replayed as redacted_thinking`() {
        val messages = listOf(
            Message(
                role = dev.mikhailtail.handyagent.core.model.Role.ASSISTANT,
                blocks = listOf(Block.Reasoning("", null, "ENCRYPTED")),
            )
        )
        val content = provider(FakeHttpEngine()).buildBody(req(messages = messages))
            .array("messages").single().array("content")
        assertEquals("redacted_thinking", content.single().str("type"))
        assertEquals("ENCRYPTED", content.single().str("data"))
    }

    @Test
    fun `empty assistant message is filtered out of the payload`() {
        val messages = listOf(Message.user("hi"), Message(dev.mikhailtail.handyagent.core.model.Role.ASSISTANT, emptyList()))
        val body = provider(FakeHttpEngine()).buildBody(req(messages = messages))
        assertEquals(1, body.array("messages").size)
    }

    @Test
    fun `headers carry api key and version and url is messages endpoint`() = runTest {
        val engine = FakeHttpEngine(lines = emptyList())
        provider(engine).stream(req()).toList()
        val sent = engine.lastRequest
        assertEquals("https://api.anthropic.com/v1/messages", sent.url)
        assertEquals("sk-test", sent.headers["x-api-key"])
        assertEquals("2023-06-01", sent.headers["anthropic-version"])
        assertEquals("text/event-stream", sent.headers["accept"])
    }

    @Test
    fun `trailing slash in base url does not double up`() = runTest {
        val engine = FakeHttpEngine()
        provider(engine, base = "https://proxy.local/anthropic/").stream(req()).toList()
        assertEquals("https://proxy.local/anthropic/v1/messages", engine.lastRequest.url)
    }

    @Test
    fun `listModels parses ids and swallows failures`() = runTest {
        val ok = FakeHttpEngine(executeResponse = """{"data":[{"id":"claude-a"},{"id":"claude-b"}]}""")
        assertEquals(listOf("claude-a", "claude-b"), provider(ok).listModels())
        assertEquals("/v1/models?limit=100", ok.lastRequest.url.removePrefix("https://api.anthropic.com"))

        val bad = FakeHttpEngine(failWith = IOException("no network"))
        assertTrue(provider(bad).listModels().isEmpty())
    }
}
