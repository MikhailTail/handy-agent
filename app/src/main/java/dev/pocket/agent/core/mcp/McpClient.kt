package dev.pocket.agent.core.mcp

import dev.pocket.agent.core.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** `initialize` 得到的服务端自述。 */
data class McpServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
    val capabilities: Json? = null,
)

/** `tools/list` 返回的一个远端工具描述。 */
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: Json,
)

/** `tools/call` 的结果（已把 MCP content 数组渲染成文本）。 */
data class McpCallResult(val content: String, val isError: Boolean)

/** 服务端通知（notifications 命名空间下的消息）。 */
data class McpNotification(val method: String, val params: Json?)

/**
 * MCP 客户端：在 [McpTransport] 之上实现 `initialize → tools/list → tools/call`。
 *
 * 不变量：
 * 1. 每个请求都有唯一 id，并与应答一一配对；未知/迟到的 id 一律丢弃（不能污染别人的等待）。
 * 2. 对端关闭或读失败时，**所有**挂起请求立即失败 —— 否则调用方会一直挂到超时。
 * 3. 服务端反向请求必须应答（哪怕只是「不支持」），否则对端会永久等待。
 *
 * [dispatcher] 可注入是**契约的一部分**，不只是为了测试：读循环跑在自己的 scope 上，
 * 生产环境用 [Dispatchers.Default]（MCP 是网络/子进程 IO，绝不能占住调用方线程）；
 * 测试里换成单一调度器，请求-应答才在**同一个**时钟上发生。
 * 若写死一个调度器，`withTimeout` 用的却是调用方时钟，两者不同步就会产生假的超时。
 */
class McpClient(
    private val transport: McpTransport,
    private val clientName: String = "pocket-agent",
    private val clientVersion: String = "0.1.0",
    private val requestedProtocolVersion: String = DEFAULT_PROTOCOL_VERSION,
    private val requestTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxListPages: Int = 50,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val notificationsFlow = MutableSharedFlow<McpNotification>(extraBufferCapacity = 64)

    @Volatile
    private var readerJob: Job? = null

    @Volatile
    private var info: McpServerInfo? = null

    private class Pending(val method: String, val deferred: CompletableDeferred<Json>)

    val serverInfo: McpServerInfo? get() = info

    /** 服务端通知流；缓冲满了会丢弃最旧之外的新通知（通知永远不该阻塞协议主路径）。 */
    val notifications: SharedFlow<McpNotification> get() = notificationsFlow

    /** 启动传输并开始读循环（幂等）。 */
    suspend fun start() {
        if (readerJob != null) return
        transport.start()
        readerJob = scope.launch { readLoop() }
    }

    /** 握手：initialize + notifications/initialized。 */
    suspend fun initialize(): McpServerInfo {
        val params = Json.obj(
            "protocolVersion" to Json.Str(requestedProtocolVersion),
            "capabilities" to Json.obj(),
            "clientInfo" to Json.obj(
                "name" to Json.Str(clientName),
                "version" to Json.Str(clientVersion),
            ),
        )
        val result = request("initialize", params)
        val serverInfo = result.obj("serverInfo")
        val parsed = McpServerInfo(
            name = serverInfo?.str("name") ?: transport.name,
            version = serverInfo?.str("version") ?: "",
            protocolVersion = result.str("protocolVersion") ?: requestedProtocolVersion,
            capabilities = result.obj("capabilities"),
        )
        info = parsed
        // 握手第二步：规范要求必须发出，否则部分服务端会拒绝后续请求。
        transport.send(JsonRpc.notification("notifications/initialized").encode())
        return parsed
    }

    /** 拉取全部远端工具（自动翻页）。 */
    suspend fun listTools(): List<McpToolDescriptor> {
        val out = ArrayList<McpToolDescriptor>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            val params = if (cursor == null) Json.obj() else Json.obj("cursor" to Json.Str(cursor))
            val result = request("tools/list", params)
            for (node in result.array("tools")) {
                val name = node.str("name") ?: continue
                out.add(
                    McpToolDescriptor(
                        name = name,
                        description = node.str("description") ?: "",
                        inputSchema = node.obj("inputSchema") ?: Json.obj("type" to Json.Str("object")),
                    )
                )
            }
            cursor = result.str("nextCursor")
            pages++
            // 服务端若回环 nextCursor，这里必须兜住，否则会无限翻页。
            if (cursor == null || cursor.isEmpty() || pages >= maxListPages) break
        }
        return out
    }

    /** 调用远端工具。协议错误会抛 [McpProtocolException]，工具自身的失败则体现在 [McpCallResult.isError]。 */
    suspend fun callTool(name: String, arguments: Json): McpCallResult {
        val params = Json.obj(
            "name" to Json.Str(name),
            "arguments" to arguments,
        )
        val result = request("tools/call", params)
        val rendered = renderContent(result.array("content"))
        val text = when {
            rendered.isNotEmpty() -> rendered
            else -> result.obj("structuredContent")?.encode() ?: ""
        }
        return McpCallResult(text, result.bool("isError") ?: false)
    }

    suspend fun close() {
        runCatching { readerJob?.cancel() }
        readerJob = null
        failAll(McpProtocolException("MCP client for '${transport.name}' was closed"))
        scope.cancel()
        transport.close()
    }

    // -----------------------------------------------------------------------

    private suspend fun request(method: String, params: Json?): Json {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<Json>()
        pending[id] = Pending(method, deferred)
        try {
            transport.send(JsonRpc.request(id, method, params).encode())
            return withTimeout(requestTimeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw McpProtocolException(
                "MCP request '$method' to '${transport.name}' timed out after ${requestTimeoutMs}ms"
            )
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun readLoop() {
        try {
            transport.incoming.collect { handleLine(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failAll(McpProtocolException("MCP transport '${transport.name}' read failed: ${e.message}"))
            return
        }
        // 正常结束 = 对端关闭，挂起请求不可能再被应答。
        failAll(
            McpProtocolException(
                "MCP server '${transport.name}' closed the connection" +
                    transport.diagnostics().let { if (it.isBlank()) "" else "\n--- server stderr ---\n${it.trim()}" }
            )
        )
    }

    private suspend fun handleLine(line: String) {
        when (val msg = JsonRpc.parseIncoming(line)) {
            is IncomingMessage.Response -> {
                val slot = pending.remove(msg.id) ?: return
                val fault = msg.error
                if (fault != null) {
                    slot.deferred.completeExceptionally(
                        McpProtocolException(
                            "MCP '${slot.method}' failed on '${transport.name}': ${fault.describe()}" +
                                (fault.data?.let { " data=${it.encode().take(300)}" } ?: ""),
                            fault.code,
                        )
                    )
                } else {
                    slot.deferred.complete(msg.result ?: Json.Null)
                }
            }

            is IncomingMessage.ServerRequest -> {
                // 我们没声明任何客户端能力，所以一律礼貌拒绝，绝不能让对端空等。
                val reply = JsonRpc.errorResponse(
                    msg.id,
                    JsonRpcErrorCode.METHOD_NOT_FOUND,
                    "client does not implement '${msg.method}'",
                )
                runCatching { transport.send(reply.encode()) }
            }

            is IncomingMessage.Notification -> {
                notificationsFlow.tryEmit(McpNotification(msg.method, msg.params))
            }

            is IncomingMessage.Malformed -> Unit
        }
    }

    private fun failAll(cause: Throwable) {
        val snapshot = pending.keys.toList()
        for (id in snapshot) {
            pending.remove(id)?.deferred?.completeExceptionally(cause)
        }
    }

    companion object {
        const val DEFAULT_PROTOCOL_VERSION = "2024-11-05"
        const val DEFAULT_TIMEOUT_MS = 20_000L

        /** 把 MCP content 数组渲染成可回填给模型的文本。 */
        fun renderContent(parts: List<Json>): String {
            if (parts.isEmpty()) return ""
            val sb = StringBuilder()
            for (part in parts) {
                val type = part.str("type") ?: "unknown"
                when (type) {
                    "text" -> sb.append(part.str("text") ?: "")
                    "image" -> sb.append("[image ").append(part.str("mimeType") ?: "unknown").append(']')
                    "audio" -> sb.append("[audio ").append(part.str("mimeType") ?: "unknown").append(']')
                    "resource" -> {
                        val uri = part.obj("resource")?.str("uri")
                        sb.append("[resource ").append(uri ?: "embedded").append(']')
                    }
                    else -> sb.append('[').append(type).append(']')
                }
                sb.append('\n')
            }
            return sb.toString().trimEnd('\n')
        }
    }
}
