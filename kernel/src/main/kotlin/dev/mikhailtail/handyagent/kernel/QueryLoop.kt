package dev.mikhailtail.handyagent.kernel

import dev.mikhailtail.handyagent.kernel.api.LlmClient
import dev.mikhailtail.handyagent.kernel.api.LlmEvent
import dev.mikhailtail.handyagent.kernel.api.LlmRequest
import dev.mikhailtail.handyagent.kernel.api.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 内核对外发出的事件。
 *
 * 前三个是 [LlmEvent] 的直通，[TurnComplete] 是内核补的：一轮结束后要把"这一轮产生了
 * 什么"交给上层去落盘，而单看流事件是拼不出完整消息的（文本散在多个 delta 里，
 * 工具参数是分片 JSON）。
 */
sealed interface EngineEvent {
    data class TextDelta(val text: String) : EngineEvent
    data class ThinkingDelta(val text: String) : EngineEvent
    data class ToolUseStart(val id: String, val name: String) : EngineEvent

    /** 一轮结束。[assistantMessage] 是要写进转录的完整 assistant 消息。 */
    data class TurnComplete(
        val assistantMessage: JsonElement,
        val usage: TokenUsage?,
        val stopReason: String?,
    ) : EngineEvent

    /** 出错。[retryable] 由 `LlmException` 判定（429/5xx/网络错误可重试）。 */
    data class Failure(val message: String, val retryable: Boolean) : EngineEvent
}

/**
 * Agent 的主循环 —— 对应 cc-haha 的 `src/query.ts` 里的 `queryLoop`。
 *
 * **退出信号是本轮有没有出现 `tool_use` 块，不是 `stop_reason`。**
 * cc-haha 在源码里明确写了这一点（注释大意：stop_reason 不可靠），我们照搬这条判断。
 * 原因很实际：不同厂商的兼容实现在这点上不一致，有的即使返回了 tool_use 也把
 * stop_reason 报成 `end_turn`，有的相反。以"有没有工具要调"为准，语义最稳。
 *
 * 阶段 2 只跑**单轮**（没有工具注册，所以第一轮必然没有 tool_use）。
 * 阶段 3 接上工具后，`while` 才是完整的 —— 结构先摆在这里，避免那时改形状。
 */
class QueryEngine(private val llm: LlmClient) {

    fun run(request: LlmRequest): Flow<EngineEvent> = flow {
        val textBuf = StringBuilder()
        val thinkingBuf = StringBuilder()
        val toolUses = mutableListOf<ToolUseAccumulator>()
        var usage: TokenUsage? = null
        var stopReason: String? = null

        try {
            llm.stream(request).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> {
                        textBuf.append(event.text)
                        emit(EngineEvent.TextDelta(event.text))
                    }

                    is LlmEvent.ThinkingDelta -> {
                        thinkingBuf.append(event.text)
                        emit(EngineEvent.ThinkingDelta(event.text))
                    }

                    is LlmEvent.ToolUseStart -> {
                        toolUses += ToolUseAccumulator(event.id, event.name)
                        emit(EngineEvent.ToolUseStart(event.id, event.name))
                    }

                    // 工具参数是分片 JSON，必须攒齐再解析 —— 中途的每个分片都不是合法 JSON。
                    is LlmEvent.ToolInputDelta ->
                        toolUses.lastOrNull()?.json?.append(event.partialJson)

                    is LlmEvent.MessageStop -> {
                        usage = event.usage
                        stopReason = event.stopReason
                    }
                }
            }
        } catch (e: dev.mikhailtail.handyagent.kernel.api.LlmException) {
            emit(EngineEvent.Failure(e.message ?: "模型调用失败", e.retryable))
            return@flow
        }

        emit(
            EngineEvent.TurnComplete(
                assistantMessage = buildAssistantMessage(textBuf, thinkingBuf, toolUses),
                usage = usage,
                stopReason = stopReason,
            ),
        )
    }
}

private class ToolUseAccumulator(val id: String, val name: String) {
    val json = StringBuilder()
}

/**
 * 组装要写进转录的 assistant 消息。
 *
 * 形态必须与 cc-haha 落盘的一致：`{ "role": "assistant", "content": [ ...blocks ] }`。
 * 转录是**双方共用**的 —— cc-haha 也要能读回我们写的文件，所以这里不能自创格式。
 *
 * block 的排放顺序按 cc-haha 的习惯：thinking → text → tool_use。
 */
private fun buildAssistantMessage(
    text: StringBuilder,
    thinking: StringBuilder,
    toolUses: List<ToolUseAccumulator>,
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
            for (tool in toolUses) {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("tool_use"))
                        put("id", JsonPrimitive(tool.id))
                        put("name", JsonPrimitive(tool.name))
                        // 参数可能因为上游中断而没攒完；解析不出来时给空对象，
                        // 让转录保持可读而不是整行作废。
                        put("input", tool.json.toString().toJsonOrEmptyObject())
                    },
                )
            }
        },
    )
}

private fun String.toJsonOrEmptyObject(): JsonElement =
    if (isBlank()) JsonObject(emptyMap())
    else runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(this)
    }.getOrElse { JsonObject(emptyMap()) }

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
