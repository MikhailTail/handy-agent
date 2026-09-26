package dev.pocket.agent.core.provider.openai

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.model.Block
import dev.pocket.agent.core.model.ChatRequest
import dev.pocket.agent.core.model.Message
import dev.pocket.agent.core.model.Role
import dev.pocket.agent.core.model.StopReason
import dev.pocket.agent.core.model.ToolSpec
import dev.pocket.agent.core.provider.HttpEngine
import dev.pocket.agent.core.provider.HttpRequest
import dev.pocket.agent.core.provider.LlmProvider
import dev.pocket.agent.core.provider.ProviderConfig
import dev.pocket.agent.core.provider.ProviderEvent
import dev.pocket.agent.core.provider.TokenUsage
import dev.pocket.agent.core.provider.ToolCallAccumulator
import dev.pocket.agent.core.provider.sse.SseParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * OpenAI Chat Completions 兼容后端（OpenAI / DeepSeek / Moonshot / vLLM / Ollama ...）。
 *
 * 推理强度 → `reasoning_effort`（OFF 时整个字段不发，避免被严格服务端拒绝）。
 * 工具调用分片在 `delta.tool_calls[]`，按 index 聚合。
 */
class OpenAiCompatProvider(
    override val config: ProviderConfig,
    private val http: HttpEngine,
    /** 部分自建网关不认识 `stream_options`，可在构造时关掉。 */
    private val sendStreamOptions: Boolean = true,
) : LlmProvider {

    override val id: String get() = config.id
    override val displayName: String get() = config.name

    override fun stream(req: ChatRequest): Flow<ProviderEvent> = flow {
        val parser = SseParser()
        val acc = ToolCallAccumulator()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var stopReason = StopReason.UNKNOWN
        var usage = TokenUsage.EMPTY
        var failure: String? = null
        var done = false

        try {
            val body = buildBody(req).encode()
            http.streamLines(
                HttpRequest(url = chatUrl(), headers = headers(), body = body)
            ).collect { line ->
                if (done) return@collect
                val ev = parser.feedLine(line) ?: return@collect
                val payload = ev.data.trim()
                if (payload.isEmpty()) return@collect
                if (payload == "[DONE]") { done = true; return@collect }

                val data = Json.parseOrNull(payload) ?: return@collect

                data.obj("error")?.let {
                    failure = it.str("message") ?: payload.take(400)
                    return@collect
                }

                data.obj("usage")?.let { u ->
                    usage = TokenUsage(
                        inputTokens = u.int("prompt_tokens") ?: usage.inputTokens,
                        outputTokens = u.int("completion_tokens") ?: usage.outputTokens,
                    )
                }

                val choice = data.array("choices").firstOrNull()?.let { it as? Json.Obj } ?: return@collect
                choice.str("finish_reason")?.let { stopReason = StopReason.fromOpenAi(it) }

                val delta = choice.obj("delta") ?: return@collect

                delta.str("content")?.takeIf { it.isNotEmpty() }?.let {
                    text.append(it)
                    emit(ProviderEvent.TextDelta(it))
                }

                (delta.str("reasoning_content") ?: delta.str("reasoning"))?.takeIf { it.isNotEmpty() }?.let {
                    reasoning.append(it)
                    emit(ProviderEvent.ReasoningDelta(it))
                }

                // 新版：tool_calls[] 分片；旧版：function_call 单对象
                for (tc in delta.array("tool_calls")) {
                    val index = tc.int("index") ?: 0
                    val fn = tc.obj("function")
                    val started = acc.accept(index, tc.str("id"), fn?.str("name"), fn?.str("arguments"))
                    if (started != null) {
                        emit(ProviderEvent.ToolCallStarted(started.index, started.id, started.name))
                    }
                }
                delta.obj("function_call")?.let { fn ->
                    val started = acc.accept(0, null, fn.str("name"), fn.str("arguments"))
                    if (started != null) {
                        emit(ProviderEvent.ToolCallStarted(started.index, started.id, started.name))
                    }
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

        val built = acc.build()
        val blocks = ArrayList<Block>()
        if (reasoning.isNotEmpty()) blocks.add(Block.Reasoning(reasoning.toString()))
        if (text.isNotEmpty()) blocks.add(Block.Text(text.toString()))
        blocks.addAll(built.calls)

        val reason = if (stopReason == StopReason.UNKNOWN) {
            if (built.calls.isNotEmpty()) StopReason.TOOL_USE else StopReason.END_TURN
        } else stopReason
        emit(ProviderEvent.Completed(blocks, reason, usage))
    }

    override suspend fun listModels(): List<String> = try {
        val body = http.execute(
            HttpRequest(url = "${config.baseUrl.trimEnd('/')}/models", method = "GET", headers = headers())
        )
        Json.parseOrNull(body)?.array("data")?.mapNotNull { it.str("id") }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private fun chatUrl(): String = "${config.baseUrl.trimEnd('/')}/chat/completions"

    private fun headers(): Map<String, String> = buildMap {
        put("content-type", "application/json")
        put("accept", "text/event-stream")
        if (config.apiKey.isNotEmpty()) put("authorization", "Bearer ${config.apiKey}")
        putAll(config.extraHeaders)
    }

    // ---------------------------------------------------------------- 请求体

    fun buildBody(req: ChatRequest): Json {
        val messages = ArrayList<Json>()
        if (req.system.isNotBlank()) {
            messages.add(Json.obj("role" to Json.of("system"), "content" to Json.of(req.system)))
        }
        req.messages.filter { it.role != Role.SYSTEM && it.blocks.isNotEmpty() }
            .forEach { appendMessage(messages, it) }

        val fields = LinkedHashMap<String, Json>()
        fields["model"] = Json.of(req.model)
        fields["messages"] = Json.arr(messages)
        fields["stream"] = Json.of(true)
        if (sendStreamOptions) {
            fields["stream_options"] = Json.obj("include_usage" to Json.of(true))
        }
        if (req.tools.isNotEmpty()) {
            fields["tools"] = Json.arr(req.tools.map { toolJson(it) })
            fields["tool_choice"] = Json.of("auto")
        }
        req.effort.openAiEffort?.let { fields["reasoning_effort"] = Json.of(it) }
        req.temperature?.let { fields["temperature"] = Json.of(it) }
        return Json.Obj(fields)
    }

    private fun appendMessage(out: MutableList<Json>, m: Message) {
        if (m.role == Role.ASSISTANT) {
            val text = m.text
            val calls = m.toolUses
            if (text.isEmpty() && calls.isEmpty()) return
            val o = LinkedHashMap<String, Json>()
            o["role"] = Json.of("assistant")
            o["content"] = if (text.isEmpty()) Json.Null else Json.of(text)
            if (calls.isNotEmpty()) {
                o["tool_calls"] = Json.arr(calls.map { c ->
                    Json.obj(
                        "id" to Json.of(c.id),
                        "type" to Json.of("function"),
                        "function" to Json.obj(
                            "name" to Json.of(c.name),
                            "arguments" to Json.of(c.input.encode()),
                        ),
                    )
                })
            }
            out.add(Json.Obj(o))
            return
        }

        // USER：纯工具结果 → 一条一个 role:"tool"；混合内容 → 先 user 文本，再逐个 tool。
        val text = m.text
        val results = m.toolResults
        if (text.isNotEmpty()) {
            out.add(Json.obj("role" to Json.of("user"), "content" to Json.of(text)))
        }
        for (r in results) {
            out.add(
                Json.obj(
                    "role" to Json.of("tool"),
                    "tool_call_id" to Json.of(r.toolUseId),
                    "content" to Json.of(r.content),
                )
            )
        }
        if (text.isEmpty() && results.isEmpty()) {
            out.add(Json.obj("role" to Json.of("user"), "content" to Json.of("")))
        }
    }

    private fun toolJson(t: ToolSpec): Json = Json.obj(
        "type" to Json.of("function"),
        "function" to Json.obj(
            "name" to Json.of(t.name),
            "description" to Json.of(t.description),
            "parameters" to t.inputSchema,
        ),
    )
}
