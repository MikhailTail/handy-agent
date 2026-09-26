package dev.pocket.agent.core.mcp

import dev.pocket.agent.core.tool.Tool
import dev.pocket.agent.core.tool.ToolMerge
import dev.pocket.agent.core.tool.ToolRegistry
import dev.pocket.agent.core.tool.mergeTools
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import java.io.File

/** 单个 MCP 服务端的连接状态。 */
data class McpServerStatus(
    val id: String,
    val connected: Boolean,
    val toolCount: Int = 0,
    val error: String? = null,
    val serverName: String = "",
    val serverVersion: String = "",
    val toolNames: List<String> = emptyList(),
) {
    val failed: Boolean get() = !connected && error != null
}

/**
 * MCP 服务端生命周期管理器。
 *
 * 关键原则：**单个服务端失败绝不能拖垮整个会话**。启动时逐个隔离异常，
 * 失败的只在 [statuses] 里留一条错误，其余服务端照常可用。
 */
class McpManager(
    private val configs: List<McpServerConfig>,
    private val workingDir: File? = null,
    private val transportFactory: (McpServerConfig) -> McpTransport = { cfg ->
        StdioMcpTransport(cfg.id, cfg.command, workingDir, cfg.env)
    },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clientFactory: (McpTransport) -> McpClient = { McpClient(it, dispatcher = dispatcher) },
) {

    private val clients = LinkedHashMap<String, McpClient>()
    private val bridges = ArrayList<Tool>()
    private var statusList: List<McpServerStatus> = emptyList()

    val tools: List<Tool> get() = bridges.toList()

    fun statuses(): List<McpServerStatus> = statusList

    val connectedCount: Int get() = statusList.count { it.connected }

    /**
     * 启动全部启用的服务端（并行），做握手并拉取工具清单。
     * 永不抛异常：全部结果都以 [McpServerStatus] 返回。
     */
    suspend fun startAll(): List<McpServerStatus> {
        val enabled = configs.filter { it.enabled }
        // 握手可以并行，但**合并必须在调用协程里按配置顺序做**。原因有两条：
        // 1. 顺序稳定 —— statuses / tools / promptSection 都按用户配置的顺序展示，
        //    之前 awaitAll 的完成顺序不定，UI 上的服务端顺序会「随机跳动」；
        // 2. 无数据竞争 —— clients / bridges 是普通 LinkedHashMap / ArrayList，
        //    从多个 Default 线程协程里直接写会丢条目甚至损坏结构。
        val started = coroutineScope {
            enabled.map { cfg -> async { startOne(cfg) } }.awaitAll()
        }
        val results = ArrayList<McpServerStatus>(started.size)
        started.forEach { outcome ->
            outcome.client?.let { clients[outcome.status.id] = it }
            bridges.addAll(outcome.bridges)
            results.add(outcome.status)
        }
        statusList = results
        return results
    }

    /** 单个服务端的握手产物；**不触碰共享状态**，由 [startAll] 统一合并。 */
    private class Outcome(
        val status: McpServerStatus,
        val client: McpClient? = null,
        val bridges: List<Tool> = emptyList(),
    )

    private suspend fun startOne(cfg: McpServerConfig): Outcome {
        val transport = try {
            transportFactory(cfg)
        } catch (e: Exception) {
            return Outcome(McpServerStatus(cfg.id, false, error = "cannot create transport: ${e.message}"))
        }
        val client = clientFactory(transport)
        return try {
            client.start()
            val info = client.initialize()
            val descriptors = client.listTools()
            val added = descriptors.map { McpToolBridge(client, it, cfg.id) }
            Outcome(
                status = McpServerStatus(
                    id = cfg.id,
                    connected = true,
                    toolCount = added.size,
                    serverName = info.name,
                    serverVersion = info.version,
                    toolNames = added.map { it.name },
                ),
                client = client,
                bridges = added,
            )
        } catch (e: Exception) {
            runCatching { client.close() }
            runCatching { transport.close() }
            Outcome(McpServerStatus(cfg.id, false, error = e.message ?: e::class.simpleName ?: "unknown error"))
        }
    }

    /** 与既有工具表合并，跳过重名（避免插件/内置工具被远端同名工具顶掉）。 */
    fun combineRegistry(base: ToolRegistry): ToolMerge = mergeTools(base, bridges)

    /** 注入系统提示的片段：只列服务名与工具名，避免把远端 schema 全量塞进上下文。 */
    fun promptSection(): String {
        val connected = statusList.filter { it.connected }
        if (connected.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## MCP servers\n")
        sb.append("Remote tools bridged from MCP servers (call them like any other tool):\n")
        for (status in connected) {
            sb.append("- ").append(status.id)
            if (status.serverName.isNotBlank() && status.serverName != status.id) {
                sb.append(" (").append(status.serverName).append(')')
            }
            sb.append(": ")
            if (status.toolNames.isEmpty()) {
                sb.append("no tools")
            } else {
                sb.append(status.toolNames.joinToString(", "))
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** 关闭全部客户端。幂等，且不抛异常（关闭失败不该影响退出流程）。 */
    suspend fun close() {
        for (client in clients.values) {
            runCatching { client.close() }
        }
        clients.clear()
        bridges.clear()
    }
}
