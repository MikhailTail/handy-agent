package dev.pocket.agent.core.tool

import dev.pocket.agent.core.json.Json

data class TodoItem(val content: String, val status: String) {
    companion object {
        const val PENDING = "pending"
        const val IN_PROGRESS = "in_progress"
        const val COMPLETED = "completed"

        val STATUSES = setOf(PENDING, IN_PROGRESS, COMPLETED)

        fun mark(status: String): String = when (status) {
            IN_PROGRESS -> "[>]"
            COMPLETED -> "[x]"
            else -> "[ ]"
        }
    }
}

/** 会话内的任务清单；纯内存，按会话重建。 */
class TodoStore {
    private val items = ArrayList<TodoItem>()

    @Synchronized
    fun replace(next: List<TodoItem>): List<TodoItem> {
        items.clear()
        items.addAll(next)
        return items.toList()
    }

    @Synchronized
    fun snapshot(): List<TodoItem> = items.toList()

    @Synchronized
    fun clear() = items.clear()

    @Synchronized
    fun render(): String {
        if (items.isEmpty()) return "(no tasks)"
        return items.joinToString("\n") { "${TodoItem.mark(it.status)} ${it.content}" }
    }
}

/**
 * 任务清单工具。模型用它把多步计划外化，UI 可据此渲染进度。
 * 语义是「整体替换」，避免增量编辑产生的状态漂移。
 */
object TodoTool : Tool {
    override val name = "todo"
    override val description =
        "Create or update the task list. Pass the full list; it replaces the previous one. " +
            "status is one of: pending, in_progress, completed."

    override val inputSchema = toolSchema(
        required = listOf("items"),
        properties = linkedMapOf(
            "items" to arrayProp(
                "The complete task list, in order.",
                Json.obj(
                    "type" to Json.Str("object"),
                    "properties" to Json.obj(
                        "content" to stringProp("Task description."),
                        "status" to stringProp("pending | in_progress | completed"),
                    ),
                    "required" to Json.arrOfStrings(listOf("content", "status")),
                ),
            ),
        ),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val raw = input.array("items")
        val parsed = ArrayList<TodoItem>(raw.size)
        for ((idx, node) in raw.withIndex()) {
            val content = node.str("content")?.trim()
            if (content.isNullOrEmpty()) {
                return ToolOutcome.error("items[$idx].content must not be empty")
            }
            val status = node.str("status")?.trim() ?: TodoItem.PENDING
            if (status !in TodoItem.STATUSES) {
                return ToolOutcome.error(
                    "items[$idx].status '$status' is invalid; expected one of ${TodoItem.STATUSES.joinToString()}"
                )
            }
            parsed.add(TodoItem(content, status))
        }
        ctx.todos.replace(parsed)
        if (parsed.isEmpty()) return ToolOutcome.ok("task list cleared")
        return ToolOutcome.ok(ctx.todos.render())
    }
}
