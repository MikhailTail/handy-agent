package dev.mikhailtail.handyagent.kernel

import dev.mikhailtail.handyagent.kernel.api.LlmClient
import dev.mikhailtail.handyagent.kernel.api.LlmEvent
import dev.mikhailtail.handyagent.kernel.api.LlmException
import dev.mikhailtail.handyagent.kernel.api.LlmRequest
import dev.mikhailtail.handyagent.kernel.api.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Anthropic Messages API 的流式客户端。
 *
 * **为什么手写 SSE 而不用现成 SDK**：用户的 provider（DeepSeek / Kimi）走的是
 * Anthropic 的 `/v1/messages` 兼容端点，报文格式简单且稳定；引一个 SDK 会带来
 * 与 Kotlin/Android 的兼容负担，而这点解析量（读 `event:`/`data:` 行）自己写更可控。
 *
 * 用 `HttpURLConnection` 而不是 Ktor Client：这是 `:kernel` 里唯一的网络调用，
 * 为它引一整套客户端框架（含引擎与协程适配）不划算。将来若出现第二种 provider
 * 且协议差异大，再抽 HTTP 层也不迟。
 */
class AnthropicLlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    /**
     * `authStrategy` 来自 providers.json：
     * - `auth_token` → `Authorization: Bearer <key>`
     * - `api_key`    → `x-api-key: <key>`
     * （DeepSeek 用前者、Kimi 用后者 —— 这是实测两份配置看到的差别，不能想当然。）
     */
    private val useBearerAuth: Boolean,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : LlmClient {

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val body = buildRequestBody(request)
        val conn = openConnection(body)

        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw LlmException(
                    "模型返回 $status：${detail.take(400)}",
                    status = status,
                )
            }
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                emitSseEvents(reader)
            }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequestBody(request: LlmRequest): String = buildJsonObject {
        put("model", JsonPrimitive(request.model))
        put("max_tokens", JsonPrimitive(request.maxTokens))
        // 必须显式要流式；聚合端点会一次性返回，那样就没有逐字效果了。
        put("stream", JsonPrimitive(true))
        request.system?.let { put("system", JsonPrimitive(it)) }
        request.temperature?.let { put("temperature", JsonPrimitive(it)) }
        // 字段名必须是 snake_case 的 input_schema（在 toolsToApiSchema 里组装）；
        // 写成 camelCase 会被端点静默忽略，表现为"模型从不调用工具"。
        request.tools?.let { put("tools", it) }
        put("messages", kotlinx.serialization.json.JsonArray(request.messages))
    }.toString()

    private fun openConnection(body: String): HttpURLConnection {
        val url = URL("${baseUrl.trimEnd('/')}/v1/messages")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        // 读超时给足：推理模型首字可能要等很久，而且流式期间不能因为"没数据"就断。
        conn.readTimeout = 300_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        if (useBearerAuth) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        } else {
            conn.setRequestProperty("x-api-key", apiKey)
        }
        // Anthropic 兼容端点普遍认这个版本头。
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        return conn
    }

    /**
     * SSE 解析。
     *
     * Anthropic 的事件流形如：
     * ```
     * event: content_block_delta
     * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你"}}
     * ```
     * 我们只认 `data:` 负载（`event:` 行的信息在负载的 `type` 里都有）。
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<LlmEvent>.emitSseEvents(
        reader: BufferedReader,
    ) {
        var line = reader.readLine()
        while (line != null) {
            if (line.startsWith(DATA_PREFIX)) {
                val payload = line.removePrefix(DATA_PREFIX).trim()
                if (payload.isNotEmpty() && payload != "[DONE]") {
                    runCatching { json.parseToJsonElement(payload).jsonObject }
                        .getOrNull()
                        ?.let { handleEvent(it) }
                }
            }
            line = reader.readLine()
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<LlmEvent>.handleEvent(
        event: JsonObject,
    ) {
        when (event["type"]?.jsonPrimitive?.content) {
            "content_block_start" -> {
                val block = event["content_block"]?.jsonObject ?: return
                if (block["type"]?.jsonPrimitive?.content == "tool_use") {
                    emit(
                        LlmEvent.ToolUseStart(
                            id = block["id"]?.jsonPrimitive?.content.orEmpty(),
                            name = block["name"]?.jsonPrimitive?.content.orEmpty(),
                        ),
                    )
                }
            }

            "content_block_delta" -> {
                val delta = event["delta"]?.jsonObject ?: return
                when (delta["type"]?.jsonPrimitive?.content) {
                    "text_delta" ->
                        delta["text"]?.jsonPrimitive?.content?.let { emit(LlmEvent.TextDelta(it)) }

                    "thinking_delta" ->
                        delta["thinking"]?.jsonPrimitive?.content?.let {
                            emit(LlmEvent.ThinkingDelta(it))
                        }

                    // 工具参数是分片 JSON，交给内核攒齐。
                    "input_json_delta" ->
                        delta["partial_json"]?.jsonPrimitive?.content?.let {
                            emit(LlmEvent.ToolInputDelta(it))
                        }
                }
            }

            "message_delta" -> {
                // stop_reason 与 output_tokens 在这一类事件里；input_tokens 在 message_start。
                val delta = event["delta"]?.jsonObject
                val usage = event["usage"]?.jsonObject
                emit(
                    LlmEvent.MessageStop(
                        stopReason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull(),
                        usage = usage?.toTokenUsage(),
                    ),
                )
            }

            "error" -> {
                val err = event["error"]?.jsonObject
                throw LlmException(
                    err?.get("message")?.jsonPrimitive?.content ?: "模型流返回错误",
                )
            }
        }
    }

    private companion object {
        const val DATA_PREFIX = "data:"
    }
}

private fun JsonObject.toTokenUsage(): TokenUsage = TokenUsage(
    inputTokens = this["input_tokens"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 0,
    outputTokens = this["output_tokens"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 0,
    cacheReadTokens = this["cache_read_input_tokens"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull(),
    cacheCreationTokens =
        this["cache_creation_input_tokens"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull(),
)

private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
