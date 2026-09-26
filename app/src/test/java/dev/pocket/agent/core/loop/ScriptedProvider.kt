package dev.pocket.agent.core.loop

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.model.Block
import dev.pocket.agent.core.model.ChatRequest
import dev.pocket.agent.core.model.StopReason
import dev.pocket.agent.core.provider.LlmProvider
import dev.pocket.agent.core.provider.ProviderConfig
import dev.pocket.agent.core.provider.ProviderEvent
import dev.pocket.agent.core.provider.ProviderKind
import dev.pocket.agent.core.provider.TokenUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 脚本化假 LLM：第 n 次 stream() 回放第 n 个脚本；超出后重复最后一个脚本。
 * 记录每次请求，便于断言「回填给模型的内容是否真的包含工具结果」。
 */
class ScriptedProvider(
    private val steps: List<List<ProviderEvent>>,
) : LlmProvider {

    constructor(vararg steps: List<ProviderEvent>) : this(steps.toList())

    private var index = 0

    val requests = ArrayList<ChatRequest>()
    val callCount: Int get() = requests.size

    /** 非空时，每次 stream() 会先在此挂起，用于测试并发串行化 / 取消。 */
    var gate: CompletableDeferred<Unit>? = null

    override val id: String = "scripted"
    override val displayName: String = "Scripted"

    override val config: ProviderConfig = ProviderConfig(
        id = "scripted",
        name = "Scripted",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = "http://localhost",
        apiKey = "test",
        defaultModel = "scripted-model",
    )

    override fun stream(req: ChatRequest): Flow<ProviderEvent> = flow {
        requests.add(req)
        gate?.await()
        val step = steps.getOrElse(index) { steps.last() }
        if (index < steps.size) index++
        for (e in step) emit(e)
    }

    override suspend fun listModels(): List<String> = listOf(config.defaultModel)

    companion object {
        fun text(content: String, stop: StopReason = StopReason.END_TURN): List<ProviderEvent> = listOf(
            ProviderEvent.TextDelta(content),
            ProviderEvent.Completed(listOf(Block.Text(content)), stop, TokenUsage(10, 5)),
        )

        /** 一轮「可选的说明文字 + 若干 tool_use」，stop 固定为 TOOL_USE。 */
        fun tools(vararg calls: Pair<String, Json>, text: String? = null): List<ProviderEvent> {
            val blocks = ArrayList<Block>()
            if (text != null) blocks.add(Block.Text(text))
            calls.forEachIndexed { i, (name, input) ->
                blocks.add(Block.ToolUse("call_$i", name, input))
            }
            val events = ArrayList<ProviderEvent>()
            if (text != null) events.add(ProviderEvent.TextDelta(text))
            calls.forEachIndexed { i, (name, _) ->
                events.add(ProviderEvent.ToolCallStarted(i, "call_$i", name))
            }
            events.add(ProviderEvent.Completed(blocks, StopReason.TOOL_USE, TokenUsage(10, 5)))
            return events
        }

        fun failure(message: String): List<ProviderEvent> = listOf(ProviderEvent.Failure(message))
    }
}
