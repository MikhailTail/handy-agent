package dev.mikhailtail.handyagent.server

import dev.mikhailtail.handyagent.kernel.QueryEngine
import dev.mikhailtail.handyagent.kernel.api.LlmRequest
import dev.mikhailtail.handyagent.kernel.AnthropicLlmClient
import dev.mikhailtail.handyagent.persistence.MessageEntry
import dev.mikhailtail.handyagent.persistence.SessionScanner
import dev.mikhailtail.handyagent.persistence.TranscriptWriter
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 一轮对话的处理 —— 把 WS 收到的 `user_message` 变成"模型流式输出 + 落盘"。
 *
 * 事件与字段名严格对齐 cc-haha 的 `ServerMessage`（`desktop/src/types/chat.ts`，源头是
 * `src/server/ws/events.ts`）。前端据此渲染，名字错一个就是静默不显示。
 */
class ChatHandler(
    private val projectsDir: File,
    private val configDir: File,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scanner = SessionScanner(projectsDir)
    private val writer = TranscriptWriter()

    /** 每个会话至多一轮在跑；`stop_generation` 靠它取消。 */
    private val running = mutableMapOf<String, Job>()

    fun onUserMessage(sessionId: String, session: WebSocketSession, payload: JsonObject) {
        val text = payload["content"]?.jsonPrimitive?.contentOrNull().orEmpty()
        if (text.isBlank()) return

        // 同一会话已有轮次在跑时直接拒绝，而不是排队：排队会让用户看到"发了没反应"。
        if (running[sessionId]?.isActive == true) {
            scope.launch {
                session.sendError("上一轮还在进行中", "TURN_IN_PROGRESS", retryable = true)
            }
            return
        }

        running[sessionId] = scope.launch {
            try {
                runTurn(sessionId, session, text)
            } catch (e: Exception) {
                session.sendError(e.message ?: "对话失败", "TURN_FAILED", retryable = true)
            } finally {
                running.remove(sessionId)
                runCatching { session.sendStatus("idle") }
            }
        }
    }

    fun onStop(sessionId: String) {
        running.remove(sessionId)?.cancel()
    }

    private suspend fun runTurn(sessionId: String, session: WebSocketSession, text: String) {
        val provider = loadActiveProvider(configDir)
            ?: run {
                session.sendError("没有可用的模型供应商，请先在设置里配置", "NO_PROVIDER")
                return
            }
        if (!provider.usable) {
            session.sendError("模型供应商配置不完整（缺 baseUrl / apiKey / model）", "PROVIDER_INCOMPLETE")
            return
        }

        val item = scanner.findSession(sessionId)
        val projectPath = item?.projectPath
            ?: run {
                session.sendError("会话不存在：$sessionId", "SESSION_NOT_FOUND")
                return
            }
        val transcript = File(File(projectsDir, projectPath), "$sessionId.jsonl")
        val cwd = item.projectRoot ?: item.workDir.orEmpty()

        // 1) 先把用户这条落盘，再回放给模型。
        //    顺序反过来的话，用户看到的"已发送"与磁盘状态会不一致：进程被杀就丢了输入。
        writer.appendMessage(
            file = transcript,
            sessionId = sessionId,
            cwd = cwd,
            role = "user",
            content = textBlocks(text),
            parentUuid = writer.lastUuidOf(transcript),
        )

        session.sendStatus("thinking")

        // 2) 组装请求：历史 + 刚写入的这条。
        val history = buildApiMessages(scanner.messagesOf(projectPath, sessionId))
        val engine = QueryEngine(
            AnthropicLlmClient(
                baseUrl = provider.baseUrl,
                apiKey = provider.apiKey,
                useBearerAuth = provider.useBearerAuth,
            ),
        )

        var thinkingAnnounced = false
        var textAnnounced = false
        var failed = false

        engine.run(
            LlmRequest(
                model = provider.model,
                system = null,   // 阶段 2 还没有 system prompt；阶段 3 接工具时一起补。
                messages = history,
            ),
        ).collect { event ->
            when (event) {
                is dev.mikhailtail.handyagent.kernel.EngineEvent.TextDelta -> {
                    if (!textAnnounced) {
                        session.sendEvent("content_start", "blockType" to JsonPrimitive("text"))
                        textAnnounced = true
                    }
                    session.sendEvent("content_delta", "text" to JsonPrimitive(event.text))
                }

                is dev.mikhailtail.handyagent.kernel.EngineEvent.ThinkingDelta -> {
                    if (!thinkingAnnounced) {
                        thinkingAnnounced = true
                    }
                    // thinking 是独立事件类型，不带 content_start（与 cc-haha 一致）。
                    session.sendEvent("thinking", "text" to JsonPrimitive(event.text))
                }

                is dev.mikhailtail.handyagent.kernel.EngineEvent.ToolUseStart -> {
                    // 阶段 2 没有注册工具，正常不会走到这里；留着是为了阶段 3 接上时不漏。
                    session.sendEvent(
                        "content_start",
                        "blockType" to JsonPrimitive("tool_use"),
                        "toolName" to JsonPrimitive(event.name),
                        "toolUseId" to JsonPrimitive(event.id),
                    )
                }

                is dev.mikhailtail.handyagent.kernel.EngineEvent.TurnComplete -> {
                    writer.appendMessage(
                        file = transcript,
                        sessionId = sessionId,
                        cwd = cwd,
                        role = "assistant",
                        content = event.assistantMessage.jsonObject["content"] ?: JsonArray(emptyList()),
                        parentUuid = writer.lastUuidOf(transcript),
                        model = provider.model,
                        usage = event.usage?.toWireUsage(),
                    )
                    session.sendEvent(
                        "message_complete",
                        "usage" to (event.usage?.toWireUsage() ?: JsonObject(emptyMap())),
                    )
                }

                is dev.mikhailtail.handyagent.kernel.EngineEvent.Failure -> {
                    failed = true
                    session.sendError(event.message, "LLM_FAILED", retryable = event.retryable)
                }
            }
        }

        if (!failed && !textAnnounced && !thinkingAnnounced) {
            // 模型返回了空内容。明确告知，别让界面停在"思考中"转圈。
            session.sendError("模型没有返回任何内容", "EMPTY_RESPONSE", retryable = true)
        }
    }

    /**
     * 转录条目 → Anthropic Messages API 的 messages 数组。
     *
     * 只取 user / assistant 两类：`tool_use` / `tool_result` 在阶段 3 接上工具时才需要
     * 按配对规则重排（Anthropic 要求二者严格成对，顺序错了会 400）。
     */
    private fun buildApiMessages(entries: List<MessageEntry>): List<JsonElement> =
        entries
            .filter { it.type == "user" || it.type == "assistant" }
            .mapNotNull { entry ->
                val content = entry.content ?: return@mapNotNull null
                buildJsonObject {
                    put("role", JsonPrimitive(entry.type))
                    put("content", content)
                }
            }

    private fun textBlocks(text: String): JsonElement =
        JsonArray(listOf(
            buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(text))
            },
        ))
}

private fun dev.mikhailtail.handyagent.kernel.api.TokenUsage.toWireUsage(): JsonElement =
    buildJsonObject {
        put("input_tokens", JsonPrimitive(inputTokens))
        put("output_tokens", JsonPrimitive(outputTokens))
        cacheReadTokens?.let { put("cache_read_tokens", JsonPrimitive(it)) }
        cacheCreationTokens?.let { put("cache_creation_tokens", JsonPrimitive(it)) }
    }

private suspend fun WebSocketSession.sendEvent(type: String, vararg fields: Pair<String, JsonElement>) {
    val obj = buildJsonObject {
        put("type", JsonPrimitive(type))
        fields.forEach { (k, v) -> put(k, v) }
    }
    send(Frame.Text(obj.toString()))
}

private suspend fun WebSocketSession.sendError(message: String, code: String, retryable: Boolean = false) {
    sendEvent(
        "error",
        "message" to JsonPrimitive(message),
        "code" to JsonPrimitive(code),
        "retryable" to JsonPrimitive(retryable),
    )
}

private suspend fun WebSocketSession.sendStatus(state: String) {
    sendEvent("status", "state" to JsonPrimitive(state))
}

// contentOrNull 等 JSON 取值助手见 JsonExt.kt —— 同包内多处各自定义同名 private 扩展
// 会让调用点解析歧义（编译器报 "None of the following functions can be called"）。
