package dev.mikhailtail.handyagent.core.loop

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.ChatRequest
import dev.mikhailtail.handyagent.core.model.StopReason
import dev.mikhailtail.handyagent.core.provider.LlmProvider
import dev.mikhailtail.handyagent.core.provider.ProviderConfig
import dev.mikhailtail.handyagent.core.provider.ProviderEvent
import dev.mikhailtail.handyagent.core.provider.ProviderKind
import dev.mikhailtail.handyagent.core.provider.TokenUsage
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

        /**
         * 一轮「可选的说明文字 + 若干 tool_use」，stop 固定为 TOOL_USE。
         *
         * [idPrefix] 存在的理由：默认 id 是 `call_$i`，**每轮都从 0 重新开始**，于是多轮脚本里
         * 不同轮次的工具会撞同一个 id —— 界面按 id 回填结果时就会把上一轮的卡片改错。
         * 真实 API 的 tool id 是全局唯一的，所以多轮脚本应当给每轮不同的前缀。
         */
        fun tools(
            vararg calls: Pair<String, Json>,
            text: String? = null,
            /** 含分隔符；最终 id 形如 `call_0`。多轮脚本请给每轮不同前缀。 */
            idPrefix: String = "call_",
        ): List<ProviderEvent> {
            val blocks = ArrayList<Block>()
            if (text != null) blocks.add(Block.Text(text))
            val ids = calls.indices.map { "$idPrefix$it" }
            calls.forEachIndexed { i, (name, input) ->
                blocks.add(Block.ToolUse(ids[i], name, input))
            }
            val events = ArrayList<ProviderEvent>()
            if (text != null) events.add(ProviderEvent.TextDelta(text))
            calls.forEachIndexed { i, (name, _) ->
                events.add(ProviderEvent.ToolCallStarted(i, ids[i], name))
            }
            events.add(ProviderEvent.Completed(blocks, StopReason.TOOL_USE, TokenUsage(10, 5)))
            return events
        }

        fun failure(message: String): List<ProviderEvent> = listOf(ProviderEvent.Failure(message))
    }
}
