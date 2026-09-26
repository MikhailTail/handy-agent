package dev.pocket.agent.core.mcp

import dev.pocket.agent.core.json.Json

/**
 * 一个 MCP 服务端配置。
 *
 * [env] 是唯一允许注入环境变量的入口 —— 服务端需要的密钥（如 GitHub token）
 * 只应出现在这里，而不是继承整个宿主环境。
 */
data class McpServerConfig(
    val id: String,
    val command: List<String>,
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val requestTimeoutMs: Long = McpClient.DEFAULT_TIMEOUT_MS,
)

/** 配置解析结果：坏掉的条目只记录错误，不让整份配置作废。 */
data class McpConfigParseResult(
    val servers: List<McpServerConfig>,
    val errors: List<String>,
) {
    val isEmpty: Boolean get() = servers.isEmpty() && errors.isEmpty()
}

/**
 * 解析 MCP 配置。同时接受两种常见形态：
 * - `{"mcpServers": {"fs": {...}}}`（Claude Desktop / Claude Code 风格）
 * - `{"fs": {...}}`（裸映射）
 *
 * 单条格式：
 * `{"command": "node", "args": ["server.js"], "env": {"KEY": "v"}, "enabled": true, "timeoutMs": 20000}`
 */
object McpConfigParser {

    fun parse(text: String): McpConfigParseResult {
        val root = Json.parseOrNull(text)
            ?: return McpConfigParseResult(emptyList(), listOf("invalid JSON in MCP config"))
        return parse(root)
    }

    fun parse(root: Json): McpConfigParseResult {
        if (root !is Json.Obj) {
            return McpConfigParseResult(emptyList(), listOf("MCP config must be a JSON object"))
        }
        val map = (root["mcpServers"] as? Json.Obj)?.fields ?: root.fields
        val servers = ArrayList<McpServerConfig>()
        val errors = ArrayList<String>()

        for ((id, node) in map) {
            if (node !is Json.Obj) {
                errors.add("server '$id': entry must be an object")
                continue
            }
            val command = readCommand(node)
            if (command.isEmpty()) {
                // stdio 之外的传输（url 型）本 Loop 未实现，明确报错而不是静默忽略。
                if (node.str("url") != null) {
                    errors.add("server '$id': 'url' transport is not supported yet")
                } else {
                    errors.add("server '$id': missing 'command'")
                }
                continue
            }
            servers.add(
                McpServerConfig(
                    id = id,
                    command = command,
                    env = readEnv(node),
                    enabled = node.bool("enabled") ?: true,
                    requestTimeoutMs = node.long("timeoutMs")?.takeIf { it > 0 }
                        ?: McpClient.DEFAULT_TIMEOUT_MS,
                )
            )
        }
        return McpConfigParseResult(servers, errors)
    }

    private fun readCommand(node: Json.Obj): List<String> {
        val cmd = node.str("command")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val args = node.array("args").mapNotNull { (it as? Json.Str)?.value }
        return listOf(cmd) + args
    }

    private fun readEnv(node: Json.Obj): Map<String, String> {
        val env = node.obj("env") as? Json.Obj ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in env.fields) {
            when (v) {
                is Json.Str -> out[k] = v.value
                // 数字/布尔按字面量透传，方便 `{"PORT": 3000}` 这类写法。
                is Json.Num, is Json.Bool -> out[k] = v.encode()
                else -> Unit
            }
        }
        return out
    }
}
