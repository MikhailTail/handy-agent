package dev.pocket.agent.core.mcp

import dev.pocket.agent.core.json.Json
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * 内存版 MCP 服务端：`send` 时立刻按协议应答，行为等价于一个真实（但极简）的服务端。
 *
 * 与 [FakeMcpTransport] 的区别：后者由测试逐条喂应答、用于验证时序；
 * 本类自带应答逻辑，方便驱动 Manager / AgentLoop 这类「不关心时序、只关心结果」的集成测试。
 */
class ScriptedMcpServer(
    override val name: String,
    private val serverName: String = "scripted-server",
    private val serverVersion: String = "1.0.0",
    private val tools: List<McpToolDescriptor> = emptyList(),
    private val results: Map<String, McpCallResult> = emptyMap(),
    private val initError: String? = null,
    private val toolsError: String? = null,
) : McpTransport {

    private val inbox = Channel<String>(Channel.UNLIMITED)

    var startCount = 0
        private set
    var closed = false
        private set

    /** 收到的 notifications（无 id 的消息）。 */
    val notifications = ArrayList<String>()

    /** 收到的 tools/call 调用记录，用于断言「桥接真的转发了远端名与参数」。 */
    val calls = ArrayList<Pair<String, Json>>()

    override suspend fun start() {
        startCount++
    }

    override suspend fun send(message: String) {
        val req = Json.parseOrNull(message) ?: return
        val idNode = req["id"]
        val method = req.str("method") ?: return
        if (idNode == null || idNode.isNull) {
            notifications.add(method)
            return
        }
        val id = when (idNode) {
            is Json.Num -> idNode.value.toLong()
            else -> return
        }
        val reply = when (method) {
            "initialize" -> if (initError != null) {
                fault(id, JsonRpcErrorCode.INTERNAL_ERROR, initError)
            } else {
                ok(
                    id,
                    Json.obj(
                        "protocolVersion" to Json.Str(McpClient.DEFAULT_PROTOCOL_VERSION),
                        "capabilities" to Json.obj("tools" to Json.obj()),
                        "serverInfo" to Json.obj(
                            "name" to Json.Str(serverName),
                            "version" to Json.Str(serverVersion),
                        ),
                    ),
                )
            }

            "tools/list" -> if (toolsError != null) {
                fault(id, JsonRpcErrorCode.INTERNAL_ERROR, toolsError)
            } else {
                ok(id, Json.obj("tools" to Json.arr(tools.map { d ->
                    Json.obj(
                        "name" to Json.Str(d.name),
                        "description" to Json.Str(d.description),
                        "inputSchema" to d.inputSchema,
                    )
                })))
            }

            "tools/call" -> {
                val params = req.obj("params")
                val remote = params?.str("name").orEmpty()
                calls.add(remote to (params?.obj("arguments") ?: Json.obj()))
                val r = results[remote] ?: McpCallResult("ok from $remote", false)
                ok(
                    id,
                    Json.obj(
                        "content" to Json.arr(Json.obj("type" to Json.Str("text"), "text" to Json.Str(r.content))),
                        "isError" to Json.of(r.isError),
                    ),
                )
            }

            else -> fault(id, JsonRpcErrorCode.METHOD_NOT_FOUND, "unknown method $method")
        }
        inbox.send(reply.encode())
    }

    override val incoming: Flow<String> get() = inbox.receiveAsFlow()

    override suspend fun close() {
        closed = true
        inbox.close()
    }

    private fun ok(id: Long, result: Json): Json = Json.obj(
        "jsonrpc" to Json.Str("2.0"),
        "id" to Json.Num(id.toDouble()),
        "result" to result,
    )

    private fun fault(id: Long, code: Int, message: String): Json = Json.obj(
        "jsonrpc" to Json.Str("2.0"),
        "id" to Json.Num(id.toDouble()),
        "error" to Json.obj("code" to Json.Num(code.toDouble()), "message" to Json.Str(message)),
    )
}
