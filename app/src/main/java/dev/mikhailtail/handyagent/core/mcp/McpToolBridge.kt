package dev.mikhailtail.handyagent.core.mcp

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.tool.Tool
import dev.mikhailtail.handyagent.core.tool.ToolContext
import dev.mikhailtail.handyagent.core.tool.ToolOutcome

/**
 * 把一个远端 MCP 工具桥接成本地 [Tool]，从而与内置工具走完全相同的
 * 审批、事件、回填路径 —— AgentLoop 不需要知道「MCP」这个概念。
 *
 * 远端工具一律 `readOnly = false`：我们无法验证第三方声明的副作用，
 * 因此默认必须经过用户审批（fail-closed）。
 */
class McpToolBridge(
    private val client: McpClient,
    private val descriptor: McpToolDescriptor,
    private val serverId: String,
) : Tool {

    /** 命名空间化 + 清洗后的本地工具名（见 [mcpToolName]）。 */
    override val name: String = mcpToolName(serverId, descriptor.name)

    override val description: String =
        descriptor.description.ifBlank { "MCP tool '${descriptor.name}' from server '$serverId'" }

    override val inputSchema: Json = descriptor.inputSchema

    override val readOnly: Boolean = false

    /** 远端原始名，`tools/call` 必须用它而不是本地名。 */
    val remoteName: String get() = descriptor.name

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val result = client.callTool(descriptor.name, if (input is Json.Obj) input else Json.obj())
        return if (result.isError) ToolOutcome.error(result.content) else ToolOutcome.ok(result.content)
    }
}

/**
 * 生成本地工具名：`mcp__<server>__<tool>`。
 *
 * 必须清洗字符并截断到 64：OpenAI 的 function name 只允许 `[a-zA-Z0-9_-]` 且 ≤64 字符，
 * 而 MCP 工具名可以含 `.` `/` 等。不改名会让整个请求 400。
 */
fun mcpToolName(serverId: String, toolName: String, maxLength: Int = 64): String {
    val raw = "mcp__${serverId}__$toolName"
    val sb = StringBuilder(raw.length)
    for (c in raw) {
        val ok = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '-'
        sb.append(if (ok) c else '_')
    }
    return if (sb.length <= maxLength) sb.toString() else sb.substring(0, maxLength)
}
