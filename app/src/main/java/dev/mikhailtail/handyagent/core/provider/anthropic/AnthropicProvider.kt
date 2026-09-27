package dev.mikhailtail.handyagent.core.provider.anthropic

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.ChatRequest
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.ReasoningEffort
import dev.mikhailtail.handyagent.core.model.Role
import dev.mikhailtail.handyagent.core.model.StopReason
import dev.mikhailtail.handyagent.core.model.ToolSpec
import dev.mikhailtail.handyagent.core.provider.HttpEngine
import dev.mikhailtail.handyagent.core.provider.HttpRequest
import dev.mikhailtail.handyagent.core.provider.ImageResolver
import dev.mikhailtail.handyagent.core.provider.LlmProvider
import dev.mikhailtail.handyagent.core.provider.ProviderConfig
import dev.mikhailtail.handyagent.core.provider.ProviderEvent
import dev.mikhailtail.handyagent.core.provider.TokenUsage
import dev.mikhailtail.handyagent.core.provider.sse.SseParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Anthropic Messages API (`POST /v1/messages`, SSE)。
 *
 * 推理强度 → `thinking.budget_tokens`；开启 thinking 时 Anthropic 要求
 * `max_tokens > budget_tokens` 且禁止自定义 temperature，这里自动处理。
 */
class AnthropicProvider(
    override val config: ProviderConfig,
    private val http: HttpEngine,
    private val apiVersion: String = "2023-06-01",
    /** 图片载荷解析；默认不解析，历史中无图时行为与改造前完全一致。 */
    private val images: ImageResolver = ImageResolver.NONE,
) : LlmProvider {

    override val id: String get() = config.id
    override val displayName: String get() = config.name

    override fun stream(req: ChatRequest): Flow<ProviderEvent> = flow {
        val parser = SseParser()
        val buffers = LinkedHashMap<Int, BlockBuf>()
        var stopReason = StopReason.UNKNOWN
        var usage = TokenUsage.EMPTY
        var failure: String? = null

        try {
            val body = buildBody(req).encode()
            http.streamLines(
                HttpRequest(url = messagesUrl(), headers = headers(), body = body)
            ).collect { line ->
                val ev = parser.feedLine(line) ?: return@collect
                val data = Json.parseOrNull(ev.data) ?: return@collect
                val type = ev.event ?: data.str("type") ?: return@collect
                when (type) {
                    "message_start" -> {
                        val u = data.obj("message")?.obj("usage")
                        if (u != null) usage = TokenUsage(u.int("input_tokens") ?: 0, u.int("output_tokens") ?: 0)
                    }

                    "content_block_start" -> {
                        val index = data.int("index") ?: 0
                        val cb = data.obj("content_block")
                        if (cb != null) when (cb.str("type")) {
                            "text" -> buffers[index] = BlockBuf.TextBuf().also {
                                cb.str("text")?.let { t -> it.sb.append(t) }
                            }
                            "thinking" -> buffers[index] = BlockBuf.ThinkBuf().also {
                                cb.str("thinking")?.let { t -> it.sb.append(t) }
                                it.signature = cb.str("signature")
                            }
                            "redacted_thinking" -> buffers[index] = BlockBuf.RedactedBuf(cb.str("data").orEmpty())
                            "tool_use" -> {
                                val callId = cb.str("id") ?: "call_$index"
                                val name = cb.str("name").orEmpty()
                                buffers[index] = BlockBuf.ToolBuf(callId, name)
                                if (name.isNotEmpty()) emit(ProviderEvent.ToolCallStarted(index, callId, name))
                            }
                        }
                    }

                    "content_block_delta" -> {
                        val index = data.int("index") ?: 0
                        val delta = data.obj("delta")
                        if (delta != null) when (delta.str("type")) {
                            "text_delta" -> {
                                val t = delta.str("text").orEmpty()
                                if (t.isNotEmpty()) {
                                    (buffers.getOrPut(index) { BlockBuf.TextBuf() } as? BlockBuf.TextBuf)?.sb?.append(t)
                                    emit(ProviderEvent.TextDelta(t))
                                }
                            }
                            "thinking_delta" -> {
                                val t = delta.str("thinking").orEmpty()
                                if (t.isNotEmpty()) {
                                    (buffers.getOrPut(index) { BlockBuf.ThinkBuf() } as? BlockBuf.ThinkBuf)?.sb?.append(t)
                                    emit(ProviderEvent.ReasoningDelta(t))
                                }
                            }
                            "signature_delta" -> {
                                val s = delta.str("signature")
                                if (s != null) (buffers[index] as? BlockBuf.ThinkBuf)?.signature = s
                            }
                            "input_json_delta" -> {
                                val p = delta.str("partial_json").orEmpty()
                                if (p.isNotEmpty()) (buffers[index] as? BlockBuf.ToolBuf)?.sb?.append(p)
                            }
                        }
                    }

                    "message_delta" -> {
                        data.obj("delta")?.str("stop_reason")?.let { stopReason = StopReason.fromAnthropic(it) }
                        data.obj("usage")?.let {
                            usage = usage.copy(outputTokens = it.int("output_tokens") ?: usage.outputTokens)
                        }
                    }

                    "error" -> {
                        failure = data.obj("error")?.str("message") ?: ev.data.take(400)
                    }

                    else -> Unit // ping / message_stop / content_block_stop / 未知识别
                }
            }
            parser.flush()?.let { ev ->
                Json.parseOrNull(ev.data)?.let { d ->
                    if (d.str("type") == "error") failure = d.obj("error")?.str("message") ?: failure
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(ProviderEvent.Failure(e.message ?: e.toString(), e))
            return@flow
        }

        val fail = failure
        if (fail != null) {
            emit(ProviderEvent.Failure(fail, null))
            return@flow
        }

        val blocks = buffers.entries.sortedBy { it.key }.mapNotNull { (_, b) -> b.toBlock() }
        val reason = if (stopReason == StopReason.UNKNOWN) {
            if (blocks.any { it is Block.ToolUse }) StopReason.TOOL_USE else StopReason.END_TURN
        } else stopReason
        emit(ProviderEvent.Completed(blocks, reason, usage))
    }

    override suspend fun listModels(): List<String> = try {
        val body = http.execute(
            HttpRequest(
                url = "${config.baseUrl.trimEnd('/')}/v1/models?limit=100",
                method = "GET",
                headers = headers(),
            )
        )
        Json.parseOrNull(body)?.array("data")?.mapNotNull { it.str("id") }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private fun messagesUrl(): String = "${config.baseUrl.trimEnd('/')}/v1/messages"

    private fun headers(): Map<String, String> = buildMap {
        put("content-type", "application/json")
        put("accept", "text/event-stream")
        put("x-api-key", config.apiKey)
        put("anthropic-version", apiVersion)
        putAll(config.extraHeaders)
    }

    // ---------------------------------------------------------------- 请求体

    fun buildBody(req: ChatRequest): Json {
        val budget = req.effort.anthropicBudget
        val maxTokens = if (budget > 0) maxOf(req.maxTokens, budget + 4_096) else req.maxTokens

        val fields = LinkedHashMap<String, Json>()
        fields["model"] = Json.of(req.model)
        fields["max_tokens"] = Json.of(maxTokens)
        fields["stream"] = Json.of(true)
        if (req.system.isNotBlank()) fields["system"] = Json.of(req.system)
        fields["messages"] = Json.arr(
            req.messages
                .filter { it.role != Role.SYSTEM && it.blocks.isNotEmpty() }
                .map { messageJson(it) }
        )
        if (req.tools.isNotEmpty()) fields["tools"] = Json.arr(req.tools.map { toolJson(it) })

        if (budget > 0) {
            fields["thinking"] = Json.obj(
                "type" to Json.of("enabled"),
                "budget_tokens" to Json.of(budget),
            )
        } else if (req.temperature != null) {
            fields["temperature"] = Json.of(req.temperature.coerceIn(0.0, 1.0))
        }
        return Json.Obj(fields)
    }

    private fun toolJson(t: ToolSpec): Json = Json.obj(
        "name" to Json.of(t.name),
        "description" to Json.of(t.description),
        "input_schema" to t.inputSchema,
    )

    private fun messageJson(m: Message): Json {
        val content = ArrayList<Json>()
        for (b in m.blocks) {
            when (b) {
                is Block.Text -> if (b.text.isNotEmpty()) content.add(
                    Json.obj("type" to Json.of("text"), "text" to Json.of(b.text))
                )

                is Block.Reasoning -> when {
                    b.redactedData != null -> content.add(
                        Json.obj("type" to Json.of("redacted_thinking"), "data" to Json.of(b.redactedData))
                    )
                    // 没有 signature 的 thinking 无法回传，只能丢弃（否则 API 400）。
                    b.signature != null -> content.add(
                        Json.obj(
                            "type" to Json.of("thinking"),
                            "thinking" to Json.of(b.text),
                            "signature" to Json.of(b.signature),
                        )
                    )
                    else -> Unit
                }

                is Block.ToolUse -> content.add(
                    Json.obj(
                        "type" to Json.of("tool_use"),
                        "id" to Json.of(b.id),
                        "name" to Json.of(b.name),
                        "input" to b.input,
                    )
                )

                is Block.Image -> imageJson(b.ref)?.let { content.add(it) }

                is Block.ToolResult -> content.add(toolResultJson(b))
            }
        }
        // 工具结果必须排在 text 之前（Anthropic 的硬性要求）。
        val sorted = content.sortedBy { if (it.str("type") == "tool_result") 0 else 1 }
        return Json.obj(
            "role" to Json.of(if (m.role == Role.ASSISTANT) "assistant" else "user"),
            "content" to Json.arr(sorted),
        )
    }

    /** 单张图 → Anthropic 的 image block；解析失败返回 null，由调用方跳过。 */
    private fun imageJson(ref: ImageRef): Json? {
        val data = images.resolve(ref) ?: return null
        return Json.obj(
            "type" to Json.of("image"),
            "source" to Json.obj(
                "type" to Json.of("base64"),
                "media_type" to Json.of(data.mediaType),
                "data" to Json.of(data.base64),
            ),
        )
    }

    /**
     * tool_result 的 content：无图时保持字符串（与改造前逐字一致，不打扰既有测试），
     * 有图时才升级为 [text, image...] 数组。
     */
    private fun toolResultJson(b: Block.ToolResult): Json {
        val parts = b.images.mapNotNull { imageJson(it) }
        val inner: Json = if (parts.isEmpty()) {
            Json.of(b.content)
        } else {
            val arr = ArrayList<Json>(parts.size + 1)
            if (b.content.isNotEmpty()) {
                arr.add(Json.obj("type" to Json.of("text"), "text" to Json.of(b.content)))
            }
            arr.addAll(parts)
            Json.arr(arr)
        }
        return Json.obj(
            "type" to Json.of("tool_result"),
            "tool_use_id" to Json.of(b.toolUseId),
            "content" to inner,
            "is_error" to Json.of(b.isError),
        )
    }

    // ---------------------------------------------------------------- 块缓冲

    private sealed class BlockBuf {
        class TextBuf : BlockBuf() { val sb = StringBuilder() }
        class ThinkBuf : BlockBuf() {
            val sb = StringBuilder()
            var signature: String? = null
        }
        class ToolBuf(val id: String, val name: String) : BlockBuf() { val sb = StringBuilder() }
        class RedactedBuf(val data: String) : BlockBuf()

        fun toBlock(): Block? = when (this) {
            is TextBuf -> if (sb.isEmpty()) null else Block.Text(sb.toString())
            is ThinkBuf -> if (sb.isEmpty() && signature == null) null
            else Block.Reasoning(sb.toString(), signature)
            is ToolBuf -> Block.ToolUse(id, name, Json.parseOrNull(sb.toString()) ?: Json.Obj(emptyMap()))
            is RedactedBuf -> Block.Reasoning("", null, data)
        }
    }
}
