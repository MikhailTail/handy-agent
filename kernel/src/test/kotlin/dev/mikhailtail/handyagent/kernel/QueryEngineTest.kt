package dev.mikhailtail.handyagent.kernel

import dev.mikhailtail.handyagent.kernel.api.LlmClient
import dev.mikhailtail.handyagent.kernel.api.LlmEvent
import dev.mikhailtail.handyagent.kernel.api.LlmException
import dev.mikhailtail.handyagent.kernel.api.LlmRequest
import dev.mikhailtail.handyagent.kernel.api.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用**脚本化模型**驱动主循环 —— 不需要真实 API、不需要设备。
 *
 * 这是 `:kernel` 保持纯 JVM 的意义所在：内核行为可以在电脑上被穷举验证，
 * 而模型只是被喂进来的事件序列。
 */
class QueryEngineTest {

    /** 按脚本吐事件的假模型。 */
    private class ScriptedLlm(private val script: List<LlmEvent>) : LlmClient {
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            script.forEach { emit(it) }
        }
    }

    private class FailingLlm(private val error: LlmException) : LlmClient {
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow { throw error }
    }

    private fun req() = LlmRequest(model = "test-model", system = null, messages = emptyList())

    @Test
    fun `streams text deltas through and assembles the assistant message`() = runTest {
        val engine = QueryEngine(
            ScriptedLlm(
                listOf(
                    LlmEvent.TextDelta("你"),
                    LlmEvent.TextDelta("好"),
                    LlmEvent.MessageStop("end_turn", TokenUsage(inputTokens = 10, outputTokens = 2)),
                ),
            ),
        )

        val events = engine.run(req()).toList()

        // 增量必须原样透传 —— 前端靠它做逐字渲染，攒起来再发就没有流式效果了。
        assertEquals(
            listOf("你", "好"),
            events.filterIsInstance<EngineEvent.TextDelta>().map { it.text },
        )

        val done = events.filterIsInstance<EngineEvent.TurnComplete>().single()
        val content = done.assistantMessage.jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("你好", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(10, done.usage?.inputTokens)
    }

    /** 思考内容要单独成块，不能混进正文 —— 前端把它们渲染在不同区域。 */
    @Test
    fun `thinking becomes its own block and stays out of the text block`() = runTest {
        val engine = QueryEngine(
            ScriptedLlm(
                listOf(
                    LlmEvent.ThinkingDelta("让我想想"),
                    LlmEvent.TextDelta("答案是 42"),
                    LlmEvent.MessageStop("end_turn", null),
                ),
            ),
        )

        val blocks = engine.run(req()).toList()
            .filterIsInstance<EngineEvent.TurnComplete>().single()
            .assistantMessage.jsonObject["content"]!!.jsonArray

        // 顺序按 cc-haha 的习惯：thinking 在 text 之前。
        assertEquals(listOf("thinking", "text"), blocks.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("让我想想", blocks[0].jsonObject["thinking"]!!.jsonPrimitive.content)
        assertEquals("答案是 42", blocks[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    /**
     * 工具参数是分片 JSON，必须攒齐再解析。
     *
     * 这条是 Anthropic 流式的真实形态：`input` 会被切成若干 `partial_json` 片段发过来，
     * 任何一个片段单独看都不是合法 JSON。
     */
    @Test
    fun `fragmented tool input is reassembled into valid json`() = runTest {
        val engine = QueryEngine(
            ScriptedLlm(
                listOf(
                    LlmEvent.ToolUseStart("call_1", "Bash"),
                    LlmEvent.ToolInputDelta("{\"comm"),
                    LlmEvent.ToolInputDelta("and\":\"ls\"}"),
                    LlmEvent.MessageStop("tool_use", null),
                ),
            ),
        )

        val tool = engine.run(req()).toList()
            .filterIsInstance<EngineEvent.TurnComplete>().single()
            .assistantMessage.jsonObject["content"]!!.jsonArray
            .single { it.jsonObject["type"]!!.jsonPrimitive.content == "tool_use" }

        assertEquals("call_1", tool.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Bash", tool.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("ls", tool.jsonObject["input"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    /** 参数没攒完就断了（网络中断）时，不能因此丢掉整条消息。 */
    @Test
    fun `incomplete tool input degrades to an empty object instead of losing the message`() = runTest {
        val engine = QueryEngine(
            ScriptedLlm(
                listOf(
                    LlmEvent.ToolUseStart("call_1", "Bash"),
                    LlmEvent.ToolInputDelta("{\"comm"),
                    LlmEvent.MessageStop("tool_use", null),
                ),
            ),
        )

        val tool = engine.run(req()).toList()
            .filterIsInstance<EngineEvent.TurnComplete>().single()
            .assistantMessage.jsonObject["content"]!!.jsonArray
            .single()

        assertTrue(tool.jsonObject["input"]!!.jsonObject.isEmpty(), "残缺 JSON 应退化为空对象")
    }

    /** 失败要转成事件而不是异常穿透 —— 上层据此决定是否给用户"重试"按钮。 */
    @Test
    fun `retryable failures surface as events not exceptions`() = runTest {
        val engine = QueryEngine(FailingLlm(LlmException("限流", status = 429)))

        val events = engine.run(req()).toList()

        val failure = events.filterIsInstance<EngineEvent.Failure>().single()
        assertEquals(true, failure.retryable)
        assertTrue(events.none { it is EngineEvent.TurnComplete })
    }

    @Test
    fun `client errors are marked non retryable`() = runTest {
        val engine = QueryEngine(FailingLlm(LlmException("鉴权失败", status = 401)))

        val failure = engine.run(req()).toList().filterIsInstance<EngineEvent.Failure>().single()

        assertEquals(false, failure.retryable)
    }
}
