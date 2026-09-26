package dev.pocket.agent.core.provider

import dev.pocket.agent.core.model.Block
import dev.pocket.agent.core.model.ChatRequest
import dev.pocket.agent.core.model.StopReason
import kotlinx.coroutines.flow.Flow

enum class ProviderKind { ANTHROPIC, OPENAI_COMPAT }

/** 一个可用的模型后端配置；apiKey 由 CredentialStore 解密后注入，不落日志。 */
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** 可选模型列表（离线静态声明，避免每次启动都打网络）。 */
    val models: List<String> = emptyList(),
) {
    fun redacted(): ProviderConfig = if (apiKey.isEmpty()) this else copy(apiKey = "***")
}

data class TokenUsage(val inputTokens: Int, val outputTokens: Int) {
    val total: Int get() = inputTokens + outputTokens
    companion object { val EMPTY = TokenUsage(0, 0) }
}

/** Provider 流式事件。UI 消费 Delta*，AgentLoop 消费 Completed / Failure。 */
sealed interface ProviderEvent {
    data class TextDelta(val text: String) : ProviderEvent
    data class ReasoningDelta(val text: String) : ProviderEvent
    data class ToolCallStarted(val index: Int, val id: String, val name: String) : ProviderEvent

    /** 一次 assistant 轮次结束：blocks 即完整（含 thinking 签名），可直接作为下一轮上下文。 */
    data class Completed(
        val blocks: List<Block>,
        val stopReason: StopReason,
        val usage: TokenUsage = TokenUsage.EMPTY,
    ) : ProviderEvent

    data class Failure(val message: String, val cause: Throwable? = null) : ProviderEvent
}

/** 所有 LLM 后端的统一抽象。 */
interface LlmProvider {
    val id: String
    val displayName: String
    val config: ProviderConfig

    /** 流式对话。实现必须保证：正常结束发一次 Completed，异常发一次 Failure 后终止。 */
    fun stream(req: ChatRequest): Flow<ProviderEvent>

    /** 探测可用模型；失败返回空列表而非抛异常（设置页需要容错）。 */
    suspend fun listModels(): List<String>
}

/** 可注入的传输层：Android/JVM 用 HttpURLConnection，单测用假引擎。 */
interface HttpEngine {
    /** 逐行返回响应体（SSE 天然按行）。非 2xx 抛 HttpException。 */
    fun streamLines(req: HttpRequest): Flow<String>

    /** 一次性请求，返回完整响应体。 */
    suspend fun execute(req: HttpRequest): String
}

data class HttpRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val connectTimeoutMs: Int = 15_000,
    /** 流式响应整体读超时；过长会挂死 UI，必须设限。 */
    val readTimeoutMs: Int = 300_000,
)

class HttpException(
    val status: Int,
    val errorBody: String,
    val url: String,
) : Exception("HTTP $status from $url: ${errorBody.take(400)}")
