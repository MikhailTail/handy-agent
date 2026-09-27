package dev.mikhailtail.handyagent.kernel.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * 一次模型调用的输入。
 *
 * 刻意保持"接近 wire format"：`messages` 就是 Anthropic Messages API 的那个数组，
 * 中间不做自定义模型转换。理由与 cc-haha 一致 —— 它的内核直接组装 API 报文，
 * 我们若在中间插一层模型，每次上游格式演进都要跟着改两处，而且容易在转换中丢失字段
 * （转录的 `content` 透传就吃过这个亏）。
 */
data class LlmRequest(
    val model: String,
    val system: String?,
    val messages: List<JsonElement>,
    val maxTokens: Int = 8192,
    val temperature: Double? = null,
    /** 工具定义（Anthropic 的 `tools` 数组）。为空时不带该字段。 */
    val tools: JsonElement? = null,
)

/**
 * 流式事件。
 *
 * 命名与 Anthropic 的 SSE 事件对应，但做了归一：调用方只关心"文本来了 / 思考来了 /
 * 工具要调用了 / 结束了"，不必知道 `content_block_delta` 这类 wire 细节。
 */
sealed interface LlmEvent {

    /** 文本增量。 */
    data class TextDelta(val text: String) : LlmEvent

    /** 思考（reasoning）增量。DeepSeek / Kimi 的 Anthropic 兼容端点都会发。 */
    data class ThinkingDelta(val text: String) : LlmEvent

    /** 一次工具调用的开始。 */
    data class ToolUseStart(val id: String, val name: String) : LlmEvent

    /** 工具参数的分片 JSON（Anthropic 是 `input_json_delta`，需要自己拼完整）。 */
    data class ToolInputDelta(val partialJson: String) : LlmEvent

    /**
     * 一轮结束。
     *
     * **注意 [stopReason] 不可靠** —— cc-haha 明确记录了这一点，它判断"还要不要继续"
     * 靠的是本轮有没有出现 `tool_use` 块，而不是这个字段（见 `query.ts` 的注释）。
     * 这里保留它只为诊断，**不要拿它做控制流**。
     */
    data class MessageStop(
        val stopReason: String?,
        val usage: TokenUsage?,
    ) : LlmEvent
}

/** 对齐 cc-haha `ServerMessage` 的 `TokenUsage`（注意是 snake_case 的四个字段）。 */
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int? = null,
    val cacheCreationTokens: Int? = null,
)

/**
 * 模型客户端。
 *
 * 做成接口是为了让 `:kernel` 能在电脑上被**脚本化模型**驱动做单测 ——
 * 这是移植期最重要的生产力来源，无需真实 API、无需设备。
 */
interface LlmClient {
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

/** 调用失败。带上 HTTP 状态，便于区分"重试有用"（429/5xx）与"重试无用"（401/400）。 */
class LlmException(
    message: String,
    val status: Int? = null,
    val retryable: Boolean = status == null || status == 429 || status >= 500,
    cause: Throwable? = null,
) : Exception(message, cause)
