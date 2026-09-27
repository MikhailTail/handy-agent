package dev.mikhailtail.handyagent.core.plugin

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.tool.ToolOutcome

/** 插件可挂载的钩子点。 */
enum class HookKind(val wire: String) {
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    SESSION_START("SessionStart"),
}

/** 递给钩子的事件快照；全部不可变，钩子无法反向改动 Agent 内部状态。 */
sealed interface HookEvent {

    val kind: HookKind

    /** 工具执行前。返回 [HookDecision] 可以放行 / 拒绝 / 改写入参。 */
    data class PreToolUse(val toolName: String, val input: Json) : HookEvent {
        override val kind: HookKind get() = HookKind.PRE_TOOL_USE
    }

    /** 工具执行后（含失败）。只读通知，返回值被忽略。 */
    data class PostToolUse(
        val toolName: String,
        val input: Json,
        val outcome: ToolOutcome,
        val durationMs: Long,
    ) : HookEvent {
        override val kind: HookKind get() = HookKind.POST_TOOL_USE
    }

    /** 用户提交输入后、进入模型之前。 */
    data class UserPromptSubmit(val text: String) : HookEvent {
        override val kind: HookKind get() = HookKind.USER_PROMPT_SUBMIT
    }

    /** 会话开始（首次 run 之前，同样只触发一次）。 */
    data object SessionStart : HookEvent {
        override val kind: HookKind get() = HookKind.SESSION_START
    }
}

/** PreToolUse 钩子的裁决。 */
sealed interface HookDecision {

    /** 放行（原样或使用 [ReplaceInput] 改写后的入参）。 */
    data object Allow : HookDecision

    /** 拒绝本次调用；[reason] 会作为 tool_result 回填给模型。 */
    data class Deny(val reason: String) : HookDecision

    /** 改写入参后继续走后续钩子与审批。 */
    data class ReplaceInput(val input: Json) : HookDecision

    companion object {
        fun deny(reason: String): HookDecision = Deny(reason)
    }
}

fun interface PreToolUseHook {
    suspend fun onPreToolUse(event: HookEvent.PreToolUse): HookDecision
}

fun interface NotificationHook {
    suspend fun onEvent(event: HookEvent)
}

fun interface PromptHook {
    suspend fun onPrompt(event: HookEvent.UserPromptSubmit): String
}

/** [HookBus.preToolUse] 的合并结果。 */
data class PreToolUseResult(
    val input: Json,
    /** 非空表示被某个钩子拒绝，调用方不得执行工具。 */
    val deniedBy: String? = null,
    val reason: String = "",
) {
    val denied: Boolean get() = deniedBy != null
}

/**
 * 钩子总线。多个插件的钩子按注册顺序串联。
 *
 * 容错策略（刻意 fail-open + 留痕）：
 * 钩子抛异常不会中断 Agent —— 记进 [drainErrors] 后按「放行 / 不改写」处理。
 * 一个写坏的插件不应该让整个会话不可用；但错误必须可被 UI 呈现，否则等于静默失效。
 */
class HookBus(private val maxErrors: Int = 50) {

    private val preHooks = ArrayList<PreToolUseHook>()
    private val postHooks = ArrayList<NotificationHook>()
    private val promptHooks = ArrayList<PromptHook>()
    private val sessionHooks = ArrayList<NotificationHook>()

    private val errors = ArrayDeque<String>()

    val hasPreToolUse: Boolean get() = preHooks.isNotEmpty()
    val hasPostToolUse: Boolean get() = postHooks.isNotEmpty()
    val hasUserPromptSubmit: Boolean get() = promptHooks.isNotEmpty()
    val hasSessionStart: Boolean get() = sessionHooks.isNotEmpty()

    fun count(): Int = preHooks.size + postHooks.size + promptHooks.size + sessionHooks.size

    fun onPreToolUse(hook: PreToolUseHook) = apply { preHooks.add(hook) }
    fun onPostToolUse(hook: NotificationHook) = apply { postHooks.add(hook) }
    fun onUserPromptSubmit(hook: PromptHook) = apply { promptHooks.add(hook) }
    fun onSessionStart(hook: NotificationHook) = apply { sessionHooks.add(hook) }

    /** 依次执行 PreToolUse 钩子，直到被拒绝。改写会传递到下一个钩子。 */
    suspend fun preToolUse(toolName: String, input: Json): PreToolUseResult {
        var current = input
        for ((index, hook) in preHooks.withIndex()) {
            val decision = try {
                hook.onPreToolUse(HookEvent.PreToolUse(toolName, current))
            } catch (e: Exception) {
                record(HookKind.PRE_TOOL_USE, index, e)
                continue
            }
            when (decision) {
                is HookDecision.Allow -> Unit
                is HookDecision.Deny -> return PreToolUseResult(current, "hook#$index", decision.reason)
                is HookDecision.ReplaceInput -> current = decision.input
            }
        }
        return PreToolUseResult(current)
    }

    /** 通知型钩子：异常只记录，绝不冒泡到 AgentLoop。 */
    suspend fun postToolUse(toolName: String, input: Json, outcome: ToolOutcome, durationMs: Long) {
        if (postHooks.isEmpty()) return
        val event = HookEvent.PostToolUse(toolName, input, outcome, durationMs)
        for ((index, hook) in postHooks.withIndex()) {
            try {
                hook.onEvent(event)
            } catch (e: Exception) {
                record(HookKind.POST_TOOL_USE, index, e)
            }
        }
    }

    /** 提示词钩子：某个钩子抛异常时保留上一个钩子的结果。 */
    suspend fun userPromptSubmit(text: String): String {
        var current = text
        for ((index, hook) in promptHooks.withIndex()) {
            current = try {
                hook.onPrompt(HookEvent.UserPromptSubmit(current))
            } catch (e: Exception) {
                record(HookKind.USER_PROMPT_SUBMIT, index, e)
                current
            }
        }
        return current
    }

    suspend fun sessionStart() {
        if (sessionHooks.isEmpty()) return
        for ((index, hook) in sessionHooks.withIndex()) {
            try {
                hook.onEvent(HookEvent.SessionStart)
            } catch (e: Exception) {
                record(HookKind.SESSION_START, index, e)
            }
        }
    }

    /** 取出并清空已记录的错误（UI 展示一次即可，不重复刷屏）。 */
    fun drainErrors(): List<String> {
        if (errors.isEmpty()) return emptyList()
        val out = errors.toList()
        errors.clear()
        return out
    }

    fun peekErrors(): List<String> = errors.toList()

    private fun record(kind: HookKind, index: Int, e: Exception) {
        if (errors.size >= maxErrors) errors.removeFirst()
        errors.addLast("${kind.wire} hook#$index failed: ${e::class.simpleName}: ${e.message ?: "no message"}")
    }
}
