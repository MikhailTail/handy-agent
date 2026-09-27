package dev.mikhailtail.handyagent.core.tool

/**
 * 动态工具（插件 / MCP）合并进既有工具表的结果。
 *
 * [skipped] 列出因重名而被跳过的工具名，调用方可据此提示用户；
 * 静默丢弃会让「装了插件却没生效」变成难查的隐形故障。
 */
data class ToolMerge(val registry: ToolRegistry, val skipped: List<String>)

/**
 * 把 [extra] 合并进 [base]，跳过与 [base] 或彼此重名的工具。
 *
 * [ToolRegistry] 对重名会 `require` 失败，那会让整个会话起不来，
 * 因此新增动态工具必须走这条「跳过并回报」的路径，而不是直接 `registerAll`。
 */
fun mergeTools(base: ToolRegistry, extra: List<Tool>): ToolMerge {
    val existing = base.names().toSet()
    val skipped = ArrayList<String>()
    val accepted = ArrayList<Tool>()
    for (tool in extra) {
        if (tool.name in existing || accepted.any { it.name == tool.name }) {
            skipped.add(tool.name)
        } else {
            accepted.add(tool)
        }
    }
    return ToolMerge(base.registerAll(accepted), skipped)
}
