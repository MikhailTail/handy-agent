package dev.mikhailtail.handyagent.server

import dev.mikhailtail.handyagent.kernel.AnthropicLlmClient
import dev.mikhailtail.handyagent.kernel.CompactThresholds
import dev.mikhailtail.handyagent.kernel.ContextCompactor
import dev.mikhailtail.handyagent.kernel.EngineEvent
import dev.mikhailtail.handyagent.kernel.PermissionMode
import dev.mikhailtail.handyagent.kernel.PermissionPipeline
import dev.mikhailtail.handyagent.kernel.QueryEngine
import dev.mikhailtail.handyagent.kernel.api.LlmRequest
import dev.mikhailtail.handyagent.kernel.api.ToolContext
import dev.mikhailtail.handyagent.persistence.MessageEntry
import dev.mikhailtail.handyagent.persistence.SessionScanner
import dev.mikhailtail.handyagent.persistence.TranscriptWriter
import dev.mikhailtail.handyagent.tools.BUILTIN_TOOLS
import dev.mikhailtail.handyagent.tools.ProcessShell
import dev.mikhailtail.handyagent.tools.RealFileHost
import dev.mikhailtail.handyagent.tools.toolsToApiSchema
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 一轮对话的处理 —— 把 WS 收到的 `user_message` 变成"模型流式输出 + 工具执行 + 落盘"。
 *
 * 事件与字段名严格对齐 cc-haha 的 `ServerMessage`（`desktop/src/types/chat.ts`）。
 */
class ChatHandler(
    private val projectsDir: File,
    private val configDir: File,
    private val scope: CoroutineScope,
    /** 工作目录 —— 工具解析相对路径的基准。 */
    private val workDir: String,
    /** 模型的上下文窗口，决定压缩阈值。 */
    private val contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    /**
     * 手机操作能力的提供者。
     *
     * **是 provider 而不是固定值**：无障碍服务由系统绑定，可能在 App 启动之后才连上，
     * 也可能被用户中途关掉。每轮对话时求值，才能跟上它的真实状态。
     * 返回 null 表示这台设备没有该能力（桌面开发机），此时**不注册手机工具** ——
     * 免得模型看到一堆必然失败的工具，把每一步都浪费在试错上。
     */
    private val mobileProvider: () -> dev.mikhailtail.handyagent.kernel.api.MobileCapability? = { null },
) {
    private val scanner = SessionScanner(projectsDir)
    private val writer = TranscriptWriter()

    /** 每个会话至多一轮在跑；`stop_generation` 靠它取消。 */
    private val running = mutableMapOf<String, Job>()

    /** 每个会话的审批闸门 —— 用户回 `permission_response` 时按 sessionId 找它。 */
    private val gates = mutableMapOf<String, WsApprovalGate>()

    fun onUserMessage(sessionId: String, session: WebSocketSession, payload: JsonObject) {
        val text = payload["content"]?.jsonPrimitive?.contentOrNull().orEmpty()
        if (text.isBlank()) return

        if (running[sessionId]?.isActive == true) {
            scope.launch { session.sendError("上一轮还在进行中", "TURN_IN_PROGRESS", retryable = true) }
            return
        }

        val gate = WsApprovalGate(session)
        gates[sessionId] = gate

        running[sessionId] = scope.launch {
            try {
                runTurn(sessionId, session, gate, text)
            } catch (e: Exception) {
                session.sendError(e.message ?: "对话失败", "TURN_FAILED", retryable = true)
            } finally {
                running.remove(sessionId)
                gates.remove(sessionId)
                runCatching { session.sendStatus("idle") }
            }
        }
    }

    fun onStop(sessionId: String) {
        running.remove(sessionId)?.cancel()
        gates.remove(sessionId)?.cancelAll()
    }

    /** 用户回了审批。 */
    fun onPermissionResponse(sessionId: String, requestId: String, allowed: Boolean) {
        gates[sessionId]?.onResponse(requestId, allowed)
    }

    fun onDisconnect(sessionId: String) {
        onStop(sessionId)
    }

    private suspend fun runTurn(
        sessionId: String,
        session: WebSocketSession,
        gate: WsApprovalGate,
        text: String,
    ) {
        val provider = loadActiveProvider(configDir)
            ?: return session.sendError("没有可用的模型供应商，请先在设置里配置", "NO_PROVIDER")
        if (!provider.usable) {
            return session.sendError("模型供应商配置不完整（缺 baseUrl / apiKey / model）", "PROVIDER_INCOMPLETE")
        }

        val item = scanner.findSession(sessionId)
            ?: return session.sendError("会话不存在：$sessionId", "SESSION_NOT_FOUND")
        val transcript = File(File(projectsDir, item.projectPath), "$sessionId.jsonl")
        val cwd = item.projectRoot ?: item.workDir ?: workDir

        // 先把用户这条落盘再回放给模型：顺序反过来的话，进程被杀就丢了输入，
        // 而界面上已经显示"已发送"。
        writer.appendMessage(
            file = transcript,
            sessionId = sessionId,
            cwd = cwd,
            role = "user",
            content = textBlocks(text),
            parentUuid = writer.lastUuidOf(transcript),
        )

        session.sendStatus("thinking")

        val toolContext = object : ToolContext {
            override val files = RealFileHost()
            override val shell = ProcessShell()
            override val workDir: String = cwd
            override val mobile get() = mobileProvider()
        }

        // 阶段 3 的权限模式固定 default（每次写都问）。模式切换留到接 /api/permissions/mode 时做。
        val pipeline = PermissionPipeline(gate, PermissionMode.DEFAULT)

        val llm = AnthropicLlmClient(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            useBearerAuth = provider.useBearerAuth,
        )
        val engine = QueryEngine(
            llm = llm,
            // 手机能力可用时才挂上手机工具 —— 不可用时注册等于给模型挖坑。
            tools = BUILTIN_TOOLS + (mobileProvider()?.let { dev.mikhailtail.handyagent.tools.MobileToolSet(it).tools() } ?: emptyList()),
            permissions = pipeline,
            toolContext = toolContext,
            // 窗口按模型的实际情况给。cc-haha 从 providers.json 的 modelContextWindows 读，
            // 我们暂时用统一值 —— 读不到时宁可保守（小窗口），压得早总比撞 400 好。
            compactor = ContextCompactor(
                llm = llm,
                thresholds = CompactThresholds.forWindow(contextWindow),
            ),
        )

        var textAnnounced = false
        var sawAnything = false

        engine.run(
            LlmRequest(
                model = provider.model,
                // 不告诉它工作目录的话，它会先 pwd、再到处 ls 试探，第一次有效操作要等上百秒。
                system = dev.mikhailtail.handyagent.kernel.buildSystemPrompt(workDir = cwd),
                messages = buildApiMessages(scanner.messagesOf(item.projectPath, sessionId)),
                tools = toolsToApiSchema(),
            ),
        ).collect { event ->
            when (event) {
                is EngineEvent.TextDelta -> {
                    if (!textAnnounced) {
                        session.sendEvent("content_start", "blockType" to JsonPrimitive("text"))
                        textAnnounced = true
                    }
                    sawAnything = true
                    session.sendEvent("content_delta", "text" to JsonPrimitive(event.text))
                }

                is EngineEvent.ThinkingDelta -> {
                    sawAnything = true
                    session.sendEvent("thinking", "text" to JsonPrimitive(event.text))
                }

                is EngineEvent.ToolUseStart -> {
                    sawAnything = true
                    session.sendEvent(
                        "content_start",
                        "blockType" to JsonPrimitive("tool_use"),
                        "toolName" to JsonPrimitive(event.name),
                        "toolUseId" to JsonPrimitive(event.id),
                    )
                }

                is EngineEvent.ToolFinished -> {
                    sawAnything = true
                    // 前端靠 tool_result 把工具卡片的转圈收掉，并显示输出。
                    session.sendEvent(
                        "tool_result",
                        "toolUseId" to JsonPrimitive(event.id),
                        "content" to JsonPrimitive(event.output),
                        "isError" to JsonPrimitive(event.isError),
                    )
                }

                is EngineEvent.TurnComplete -> {
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
                    // 每轮都发：前端据此收掉"生成中"的指示器。整个回合的结束看 status(idle)。
                    session.sendEvent(
                        "message_complete",
                        "usage" to (event.usage?.toWireUsage() ?: JsonObject(emptyMap())),
                    )
                    textAnnounced = false
                }

                is EngineEvent.Compacted -> {
                    // 必须让用户看见 —— 悄悄丢掉历史会让人困惑："我刚才说的它怎么不记得了"。
                    session.sendEvent(
                        "system_notification",
                        "subtype" to JsonPrimitive("compact_boundary"),
                        "message" to JsonPrimitive(
                            "对话已压缩：${event.replacedMessages} 条历史被摘要替代" +
                                "（约 ${event.tokensBefore} → ${event.tokensAfter} tokens）",
                        ),
                    )
                    // 同时落进转录，这样重开 App 也能看到这条边界。
                    writer.appendSystemNote(
                        file = transcript,
                        sessionId = sessionId,
                        subtype = "compact_boundary",
                        text = event.summary,
                        tokensBefore = event.tokensBefore,
                        tokensAfter = event.tokensAfter,
                    )
                }

                is EngineEvent.RunComplete -> {
                    session.sendStatus("idle")
                }

                is EngineEvent.Failure -> {
                    session.sendError(event.message, "LLM_FAILED", retryable = event.retryable)
                }
            }
        }

        if (!sawAnything) {
            session.sendError("模型没有返回任何内容", "EMPTY_RESPONSE", retryable = true)
        }
    }

    /**
     * 转录条目 → Anthropic Messages API 的 messages 数组。
     *
     * **tool_use 与 tool_result 必须成对且顺序正确**，否则端点直接 400。
     * 转录里它们是分开的行，这里按原顺序透传即可 —— cc-haha 落盘时就已经
     * 保证了 assistant(tool_use) 紧跟 user(tool_result) 的顺序。
     */
    private fun buildApiMessages(entries: List<MessageEntry>): List<JsonElement> =
        entries
            .filter {
                it.type == "user" || it.type == "assistant" ||
                    it.type == "tool_use" || it.type == "tool_result"
            }
            .mapNotNull { entry ->
                val content = entry.content ?: return@mapNotNull null
                // tool_use / tool_result 行在 API 侧要归回它们原始的角色：
                // tool_use 属于 assistant，tool_result 属于 user。
                val role = when (entry.type) {
                    "assistant", "tool_use" -> "assistant"
                    else -> "user"
                }
                buildJsonObject {
                    put("role", JsonPrimitive(role))
                    put("content", content)
                }
            }

    private fun textBlocks(text: String): JsonElement =
        JsonArray(
            listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(text))
                },
            ),
        )
}

/**
 * 未知模型的上下文窗口默认值。
 *
 * 取 200k 是主流模型的常见量级；cc-haha 会从 `providers.json` 的 `modelContextWindows`
 * 按模型精确读，我们暂时用统一值。**宁可保守**：估小了只会提早压缩（浪费一次调用），
 * 估大了则是直接撞 API 的 400，对话卡死。
 */
internal const val DEFAULT_CONTEXT_WINDOW = 200_000

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

// JSON 取值助手（contentOrNull 等）见 JsonExt.kt —— 同包内重复定义同名 private 扩展
// 会让调用点解析歧义，编译器只报 "None of the following functions can be called"。
