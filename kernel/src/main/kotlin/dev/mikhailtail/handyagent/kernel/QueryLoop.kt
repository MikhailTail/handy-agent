package dev.mikhailtail.handyagent.kernel

import dev.mikhailtail.handyagent.kernel.api.LlmClient
import dev.mikhailtail.handyagent.kernel.api.LlmEvent
import dev.mikhailtail.handyagent.kernel.api.LlmException
import dev.mikhailtail.handyagent.kernel.api.LlmRequest
import dev.mikhailtail.handyagent.kernel.api.TokenUsage
import dev.mikhailtail.handyagent.kernel.api.Tool
import dev.mikhailtail.handyagent.kernel.api.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * 内核对外发出的事件。
 */
sealed interface EngineEvent {
    data class TextDelta(val text: String) : EngineEvent
    data class ThinkingDelta(val text: String) : EngineEvent

    /** 模型开始调用某个工具（参数还在流式传输中）。 */
    data class ToolUseStart(val id: String, val name: String) : EngineEvent

    /** 工具已执行完，带结果。[isError] 用于界面上标红。 */
    data class ToolFinished(
        val id: String,
        val name: String,
        val output: String,
        val isError: Boolean,
    ) : EngineEvent

    /** 一轮结束。[assistantMessage] 是要写进转录的完整 assistant 消息。 */
    data class TurnComplete(
        val assistantMessage: JsonElement,
        val usage: TokenUsage?,
        val stopReason: String?,
    ) : EngineEvent

    /** 整个用户回合结束（可能经过多轮工具调用）。 */
    data class RunComplete(val turns: Int) : EngineEvent

    /**
     * 上下文被压缩了。
     *
     * 必须让用户看见 —— 界面上要出现一条"对话已压缩"的边界提示。
     * 悄悄丢掉历史会让人困惑："我刚才说的它怎么不记得了"。
     */
    data class Compacted(
        val tokensBefore: Int,
        val tokensAfter: Int,
        val replacedMessages: Int,
        val summary: String,
    ) : EngineEvent

    data class Failure(val message: String, val retryable: Boolean) : EngineEvent
}

/**
 * Agent 主循环 —— 对应 cc-haha 的 `src/query.ts` 里的 `queryLoop`。
 *
 * **退出信号是本轮有没有出现 `tool_use` 块，不是 `stop_reason`。**
 * cc-haha 源码里明确记了这一点：不同厂商的兼容实现在 stop_reason 上不一致
 * （有的返回了 tool_use 却报 end_turn），以"有没有工具要调"为准语义最稳。
 *
 * 每一轮的形状：
 * ```
 * 调模型 → 收到 assistant（可能含 tool_use）
 *   ├─ 没有 tool_use → 结束整个回合
 *   └─ 有 tool_use   → 逐个审批+执行 → 把 assistant 与 tool_result 追加进上下文 → 下一轮
 * ```
 */
class QueryEngine(
    private val llm: LlmClient,
    private val tools: List<Tool> = emptyList(),
    private val permissions: PermissionPipeline? = null,
    private val toolContext: ToolContext? = null,
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
    /** 上下文压缩器。为空则不压缩（例如单轮测试）。 */
    private val compactor: ContextCompactor? = null,
) {

    fun run(request: LlmRequest): Flow<EngineEvent> = flow {
        val messages = request.messages.toMutableList()
        val toolsByName = tools.associateBy { it.name }
        var turns = 0
        var consecutiveCompactFailures = 0
        // 上一轮 API 回传的真实 input_tokens。**决策优先用它而不是估算值**：
        // 估算器在中文上高估了约 3.5 倍（实测 99000 估算 vs 28577 实际），
        // 若拿估算值当判据，会在上下文远没满时就开始压缩 —— 每次压缩都是一次
        // 真实的模型调用，白花钱还可能丢掉本不必丢的历史。
        var lastRealInputTokens: Int? = null

        while (true) {
            turns++

            // 每轮开始前检查是否需要压缩。放在这里而不是只在开头检查一次：
            // 工具轮会把上下文推高（一次命令输出可能几万 token）。
            val usedTokens = lastRealInputTokens ?: TokenEstimator.estimateAll(messages)
            if (compactor != null && compactor.needed(usedTokens)) {
                when (val result = runCatching { compactor.compact(request.model, messages, request.system) }
                    .getOrNull()) {
                    null -> {
                        // 压缩失败不中止对话 —— 让这一轮先跑下去，下一轮再试。
                        // 但连续失败要停手：在一个必然失败的压缩上反复烧钱没有意义。
                        consecutiveCompactFailures++
                        if (consecutiveCompactFailures >= CompactThresholds.MAX_CONSECUTIVE_FAILURES) {
                            emit(
                                EngineEvent.Failure(
                                    "上下文压缩连续失败 $consecutiveCompactFailures 次，已停止重试。",
                                    retryable = false,
                                ),
                            )
                            return@flow
                        }
                    }

                    else -> {
                        consecutiveCompactFailures = 0
                        messages.clear()
                        messages.addAll(result.messages)
                        emit(
                            EngineEvent.Compacted(
                                tokensBefore = result.tokensBefore,
                                tokensAfter = result.tokensAfter,
                                replacedMessages = result.replacedCount,
                                summary = result.summary,
                            ),
                        )
                    }
                }
            }

            // 失败路径已在 collectTurn 内部 emit 过失败事件并返回 null，这里直接收工。
            val turn = collectTurn(request, messages) ?: return@flow

            // 记下这一轮的真实用量，供下一轮判断是否需要压缩。
            turn.complete.usage?.let { usage ->
                // input + cache 才是完整上下文；只算 input 会漏掉命中缓存的那部分，
                // 于是"看起来还很空"，压缩永远不触发。
                lastRealInputTokens = usage.inputTokens + (usage.cacheReadTokens ?: 0)
            }

            emit(turn.complete)

            val calls = turn.toolCalls
            if (calls.isEmpty()) {
                // 本轮没有工具调用 → 整个回合结束。这就是退出信号。
                emit(EngineEvent.RunComplete(turns))
                return@flow
            }

            if (turns >= maxTurns) {
                emit(
                    EngineEvent.Failure(
                        "已达到单回合最大工具轮数（$maxTurns），停止以免失控",
                        retryable = false,
                    ),
                )
                return@flow
            }

            // 助手这轮说了什么，原样进上下文（含 tool_use 块，tool_result 要与它配对）。
            messages += turn.assistantMessage

            val results = buildJsonArray {
                for (call in calls) {
                    val outcome = executeToolCall(call, toolsByName)
                    emit(EngineEvent.ToolFinished(call.id, call.name, outcome.text, outcome.isError))
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(call.id))
                            put("content", JsonPrimitive(outcome.text))
                            if (outcome.isError) put("is_error", JsonPrimitive(true))
                        },
                    )
                }
            }

            // 所有结果合成**一条 user 消息**：Anthropic 要求每个 tool_use 都有对应的
            // tool_result，且必须在下一条 user 消息里 —— 拆成多条会被 400 拒掉。
            messages += buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", results)
            }
        }
    }

    /** 跑一轮模型：把流事件发出去，同时攒出完整的 assistant 消息与工具调用。 */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<EngineEvent>.collectTurn(
        request: LlmRequest,
        messages: List<JsonElement>,
    ): TurnResult? {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var usage: TokenUsage? = null
        var stopReason: String? = null

        try {
            llm.stream(request.copy(messages = messages)).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> {
                        text.append(event.text)
                        emit(EngineEvent.TextDelta(event.text))
                    }

                    is LlmEvent.ThinkingDelta -> {
                        thinking.append(event.text)
                        emit(EngineEvent.ThinkingDelta(event.text))
                    }

                    is LlmEvent.ToolUseStart -> {
                        calls += ToolCall(event.id, event.name, StringBuilder())
                        emit(EngineEvent.ToolUseStart(event.id, event.name))
                    }

                    // 参数是分片 JSON，中途任何一片单独都不是合法 JSON，必须攒齐再解析。
                    is LlmEvent.ToolInputDelta -> calls.lastOrNull()?.json?.append(event.partialJson)

                    is LlmEvent.MessageStop -> {
                        usage = event.usage
                        stopReason = event.stopReason
                    }
                }
            }
        } catch (e: LlmException) {
            emit(EngineEvent.Failure(e.message ?: "模型调用失败", e.retryable))
            return null
        }

        val assistant = buildAssistantMessage(text, thinking, calls)
        return TurnResult(
            assistantMessage = assistant,
            complete = EngineEvent.TurnComplete(assistant, usage, stopReason),
            toolCalls = calls,
        )
    }

    private suspend fun executeToolCall(call: ToolCall, toolsByName: Map<String, Tool>): ToolOutcome {
        val tool = toolsByName[call.name]
            ?: return ToolOutcome("未知工具：${call.name}", isError = true)

        val input = call.parsedInput()

        val pipeline = permissions
        val ctx = toolContext
        if (pipeline != null && ctx != null) {
            val decision = pipeline.authorize(tool, input, UUID.randomUUID().toString())
            if (decision is ApprovalDecision.Denied) {
                // 拒绝也要回 tool_result —— 不回的话模型会以为工具卡住了，反复重试同一个调用。
                return ToolOutcome("用户拒绝了这次调用：${decision.message}", isError = true)
            }
        }

        if (ctx == null) return ToolOutcome("工具上下文未就绪", isError = true)

        return runCatching { tool.execute(input, ctx) }
            .fold(
                onSuccess = { ToolOutcome(it.content.toPlainText(), it.isError) },
                onFailure = { ToolOutcome("工具执行异常：${it.message}", isError = true) },
            )
    }
}

private class TurnResult(
    val assistantMessage: JsonElement,
    val complete: EngineEvent.TurnComplete,
    val toolCalls: List<ToolCall>,
)

private class ToolCall(val id: String, val name: String, val json: StringBuilder) {
    fun parsedInput(): JsonObject =
        if (json.isEmpty()) {
            JsonObject(emptyMap())
        } else {
            runCatching { Json.parseToJsonElement(json.toString()) as? JsonObject }
                .getOrNull() ?: JsonObject(emptyMap())
        }
}

private data class ToolOutcome(val text: String, val isError: Boolean)

/**
 * 把 tool_result 的内容压成纯文本。
 *
 * Anthropic 允许 content 是 block 数组，但工具结果在这里都是人/模型可读的文本，
 * 转一次让转录与事件流都简单。将来有返回图片的工具（如截图）再扩展。
 */
private fun JsonElement.toPlainText(): String = when (this) {
    is JsonPrimitive -> content
    is JsonArray -> joinToString("\n") { it.toPlainText() }
    is JsonObject -> toString()
    else -> toString()
}

/**
 * 组装要写进转录的 assistant 消息。
 *
 * 形态必须与 cc-haha 落盘的一致（`{role, content:[...blocks]}`）—— 转录是双方共用的，
 * 桌面端也要能读回。block 顺序按 cc-haha 的习惯：thinking → text → tool_use。
 */
private fun buildAssistantMessage(
    text: StringBuilder,
    thinking: StringBuilder,
    calls: List<ToolCall>,
): JsonElement = buildJsonObject {
    put("role", JsonPrimitive("assistant"))
    put(
        "content",
        buildJsonArray {
            if (thinking.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("thinking"))
                        put("thinking", JsonPrimitive(thinking.toString()))
                    },
                )
            }
            if (text.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(text.toString()))
                    },
                )
            }
            for (call in calls) {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("tool_use"))
                        put("id", JsonPrimitive(call.id))
                        put("name", JsonPrimitive(call.name))
                        // 参数可能因上游中断而没攒完；解析不出就给空对象，
                        // 让转录保持可读而不是整行作废。
                        put("input", call.parsedInput())
                    },
                )
            }
        },
    )
}

/** 便利：把文本包成一条 user 消息（Anthropic Messages API 的形态）。 */
fun userTextMessage(text: String): JsonElement = buildJsonObject {
    put("role", JsonPrimitive("user"))
    put(
        "content",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(text))
                },
            )
        },
    )
}

const val DEFAULT_MAX_TURNS = 40
