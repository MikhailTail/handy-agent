package dev.mikhailtail.handyagent.core.loop

import dev.mikhailtail.handyagent.core.context.CompactionResult
import dev.mikhailtail.handyagent.core.context.ContextManager
import dev.mikhailtail.handyagent.core.context.Summarizer
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.ChatRequest
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.ReasoningEffort
import dev.mikhailtail.handyagent.core.model.Role
import dev.mikhailtail.handyagent.core.model.StopReason
import dev.mikhailtail.handyagent.core.model.ToolSpec
import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.mcp.McpManager
import dev.mikhailtail.handyagent.core.permission.PermissionBroker
import dev.mikhailtail.handyagent.core.plugin.HookBus
import dev.mikhailtail.handyagent.core.plugin.PluginHost
import dev.mikhailtail.handyagent.core.provider.LlmProvider
import dev.mikhailtail.handyagent.core.provider.ProviderEvent
import dev.mikhailtail.handyagent.core.provider.TokenUsage
import dev.mikhailtail.handyagent.core.skill.SkillRegistry
import dev.mikhailtail.handyagent.core.tool.ToolContext
import dev.mikhailtail.handyagent.core.tool.ToolOutcome
import dev.mikhailtail.handyagent.core.tool.ToolRegistry
import dev.mikhailtail.handyagent.core.tool.mergeTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Agent 单向事件流：驱动 UI 时间轴。
 *
 * 设计约束：事件是「已发生事实」的不可变快照，UI 不持有循环状态，
 * 因此配置变更 / 进程重建后可以只靠历史重放渲染整个时间轴。
 */
sealed interface AgentEvent {

    /** 一次迭代开始（第 index 次调用模型，从 0 计）。 */
    data class IterationStarted(val index: Int) : AgentEvent

    data class TextDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent

    /** 模型本轮完整回复（含 thinking 签名），已写入历史。 */
    data class AssistantMessage(val message: Message, val usage: TokenUsage) : AgentEvent

    /** 即将执行某个工具（已通过审批）。 */
    data class ToolStarted(val toolUseId: String, val name: String, val input: Json) : AgentEvent

    /** 工具执行结束。[outcome].isError 为 true 表示失败（错误已回填给模型）。 */
    data class ToolFinished(
        val toolUseId: String,
        val name: String,
        val outcome: ToolOutcome,
        val durationMs: Long,
    ) : AgentEvent

    /** 审批被拒绝；同一次调用不会再执行。 */
    data class ToolDenied(val toolUseId: String, val name: String, val reason: String) : AgentEvent

    /** 历史被压缩，UI 可插入一条分隔线。 */
    data class Compacted(val result: CompactionResult) : AgentEvent

    /** 本轮对话正常结束。 */
    data class Completed(val stopReason: StopReason, val usage: TokenUsage, val iterations: Int) : AgentEvent

    /** 用户中止（协程取消）。历史已修复为协议合法状态。 */
    data class Aborted(val iterations: Int) : AgentEvent

    /** 失败（网络 / 协议 / 未知异常）。历史保留至最后一次成功的助手回复。 */
    data class Failed(val message: String, val iterations: Int) : AgentEvent
}

/** 默认系统提示。Skills / MCP / 插件在后续 Loop 里追加到 [AgentLoop.systemPrompt]。 */
val DEFAULT_SYSTEM_PROMPT: String = """
You are Handy Agent, a coding agent running locally on an Android device inside a sandboxed workspace.

Rules:
- All file paths are relative to the workspace root; paths escaping it are rejected.
- Prefer targeted edits (edit) over whole-file rewrites (write).
- Read before you edit; never guess file contents.
- Use the todo tool to plan multi-step work and keep it updated.
- When a tool fails, read the error and adapt instead of retrying blindly.
- Report results concisely; do not paste large file contents back to the user.
""".trim()

/**
 * 多轮 + 工具循环的编排器。
 *
 * 不变量（违反会直接导致下一轮请求 400 或 UI 状态错乱）：
 * 1. 每条 assistant 消息里的 **每个** `tool_use` 都必须有且仅有一个 `tool_result`，
 *    并且它们必须在同一条 USER 消息里、按 tool_use 出现顺序排列。
 * 2. 历史只会追加，永不原地修改（压缩除外，且压缩只作用于「用户文本轮」边界）。
 * 3. 任何异常都不会让历史停在「assistant 有 tool_use 但缺 result」的中间态：
 *    取消或崩溃时会补齐中断结果。
 * 4. 同一实例同时只允许一次 [run]（Mutex），否则两轮会交错污染历史。
 */
class AgentLoop(
    private val provider: LlmProvider,
    private val registry: ToolRegistry,
    private val toolContext: ToolContext,
    private val broker: PermissionBroker,
    private val contextManager: ContextManager? = null,
    private val summarizer: Summarizer? = null,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** 技能注册表；同时提供 `skill` 工具与系统提示中的技能清单。 */
    private val skills: SkillRegistry? = null,
    /** 插件宿主；其工具需由调用方并入 [registry]，宿主只负责提示片段与钩子。 */
    private val pluginHost: PluginHost? = null,
    /** 钩子总线；默认复用 [pluginHost] 的那一条，避免出现两条互不相通的总线。 */
    private val hookBus: HookBus? = null,
    /**
     * MCP 管理器；其桥接工具在每次请求时并入工具表，
     * 因此可以在 [AgentLoop] 构造之后才 `startAll()`（连接结果动态生效）。
     */
    private val mcpManager: McpManager? = null,
    private val model: String = provider.config.defaultModel,
    private val effort: ReasoningEffort = ReasoningEffort.DEFAULT,
    private val maxTokens: Int = 4_096,
    /** 单轮对话内最多调用模型多少次，防止工具死循环。 */
    private val maxIterations: Int = 24,
) {

    private val mutex = Mutex()
    private val history = ArrayList<Message>()
    private var usage = TokenUsage.EMPTY
    private var sessionStarted = false

    /** 生效的钩子总线：显式传入优先，否则用插件宿主的。 */
    private val hooks: HookBus? get() = hookBus ?: pluginHost?.bus

    /** 系统提示 + Skills / Plugins / MCP 动态片段。每轮请求前求值一次。 */
    private fun effectiveSystemPrompt(): String {
        val extra = ArrayList<String>(3)
        skills?.promptSection()?.takeIf { it.isNotBlank() }?.let { extra.add(it) }
        pluginHost?.promptSection()?.takeIf { it.isNotBlank() }?.let { extra.add(it) }
        mcpManager?.promptSection()?.takeIf { it.isNotBlank() }?.let { extra.add(it) }
        if (extra.isEmpty()) return systemPrompt
        return (listOf(systemPrompt) + extra).joinToString("\n\n")
    }

    /**
     * 生效工具表：把 MCP 桥接工具并入 [registry]。
     *
     * 每次请求时求值，而不是构造时合并 —— MCP 连接是异步建立的，
     * 构造期合并会把「尚未连上」永久固化。重名时 [mergeTools] 跳过远端工具，
     * 保证内置/插件工具不会被第三方服务顶掉。
     */
    private fun effectiveRegistry(): ToolRegistry {
        val remote = mcpManager?.tools ?: return registry
        if (remote.isEmpty()) return registry
        return mergeTools(registry, remote).registry
    }

    /** 当前可向模型暴露的工具：激活的技能若声明了白名单，则据此裁剪。 */
    private fun advertisedSpecs(): List<ToolSpec> {
        val all = effectiveRegistry().specs()
        val gate = skills?.activeAllowedTools() ?: return all
        if (gate.isEmpty()) return all
        return all.filter { it.name in gate }
    }

    /** 只读快照，供 UI 重建时间轴或做持久化。 */
    fun history(): List<Message> = history.toList()

    fun totalUsage(): TokenUsage = usage

    /** 恢复历史（进程重建）。会校验工具调用配对完整性，避免带病启动。 */
    fun restore(messages: List<Message>) {
        val error = validatePairing(messages)
        require(error == null) { "cannot restore malformed history: $error" }
        history.clear()
        history.addAll(messages)
    }

    fun clear() {
        history.clear()
        usage = TokenUsage.EMPTY
        sessionStarted = false
        skills?.deactivate()
    }

    /**
     * 跑一轮用户输入。返回的 Flow 必须在协程里收集；取消它即中止本轮。
     */
    fun run(userText: String): Flow<AgentEvent> = flow {
        mutex.withLock {
            if (!sessionStarted) {
                sessionStarted = true
                hooks?.sessionStart()
            }
            // 钩子可以重写用户输入（如注入仓库约定）；落进历史的必须是真正发给模型的那一份。
            val promptText = hooks?.userPromptSubmit(userText) ?: userText
            history.add(Message.user(promptText))
            var iterations = 0
            var finalReason = StopReason.UNKNOWN
            var pendingToolIds = emptyList<String>()

            try {
                while (iterations < maxIterations) {
                    emit(AgentEvent.IterationStarted(iterations))
                    iterations++

                    maybeCompact()?.let { emit(AgentEvent.Compacted(it)) }

                    var completed: ProviderEvent.Completed? = null
                    var failure: ProviderEvent.Failure? = null

                    provider.stream(buildRequest()).collect { ev ->
                        when (ev) {
                            is ProviderEvent.TextDelta -> emit(AgentEvent.TextDelta(ev.text))
                            is ProviderEvent.ReasoningDelta -> emit(AgentEvent.ReasoningDelta(ev.text))
                            is ProviderEvent.ToolCallStarted -> Unit
                            is ProviderEvent.Completed -> completed = ev
                            is ProviderEvent.Failure -> failure = ev
                        }
                    }

                    failure?.let {
                        emit(AgentEvent.Failed(it.message, iterations))
                        return@withLock
                    }

                    val done = completed
                    if (done == null) {
                        emit(AgentEvent.Failed("provider stream ended without a Completed event", iterations))
                        return@withLock
                    }

                    val assistant = Message(Role.ASSISTANT, done.blocks)
                    history.add(assistant)
                    usage = TokenUsage(usage.inputTokens + done.usage.inputTokens, usage.outputTokens + done.usage.outputTokens)
                    emit(AgentEvent.AssistantMessage(assistant, done.usage))

                    val calls = assistant.toolUses
                    if (done.stopReason != StopReason.TOOL_USE || calls.isEmpty()) {
                        finalReason = done.stopReason
                        emit(AgentEvent.Completed(finalReason, usage, iterations))
                        return@withLock
                    }

                    pendingToolIds = calls.map { it.id }
                    val results = ArrayList<Block.ToolResult>(calls.size)
                    for (call in calls) {
                        results.add(runTool(call) { emit(it) })
                    }
                    // 一次性回填：顺序与 tool_use 一致，且每个 id 恰好一个结果。
                    history.add(Message.toolResults(results))
                    pendingToolIds = emptyList()
                }

                emit(AgentEvent.Completed(StopReason.MAX_TOKENS, usage, iterations))
            } catch (e: CancellationException) {
                // 中断时补齐 tool_result，避免历史停在协议非法状态。
                if (pendingToolIds.isNotEmpty()) {
                    history.add(
                        Message.toolResults(
                            pendingToolIds.map {
                                Block.ToolResult(it, "aborted by user before execution", isError = true)
                            }
                        )
                    )
                }
                emit(AgentEvent.Aborted(iterations))
                throw e
            }
        }
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private fun buildRequest(): ChatRequest = ChatRequest(
        model = model,
        system = effectiveSystemPrompt(),
        messages = history.toList(),
        tools = advertisedSpecs(),
        effort = effort,
        maxTokens = maxTokens,
    )

    private suspend fun maybeCompact(): CompactionResult? {
        val cm = contextManager ?: return null
        val sum = summarizer ?: return null
        val system = effectiveSystemPrompt()
        val tools = advertisedSpecs()
        if (!cm.shouldCompact(system, history, tools)) return null
        val result = cm.compact(
            messages = history.toList(),
            summarizer = sum,
            system = system,
            tools = tools,
        )
        if (!result.didCompact) return null
        history.clear()
        history.addAll(result.messages)
        return result
    }

    private suspend fun runTool(
        call: Block.ToolUse,
        emit: suspend (AgentEvent) -> Unit,
    ): Block.ToolResult {
        // 1. PreToolUse 钩子：可拒绝、可改写入参。
        var input = call.input
        hooks?.preToolUse(call.name, input)?.let { pre ->
            if (pre.denied) {
                emit(AgentEvent.ToolDenied(call.id, call.name, "blocked by ${pre.deniedBy}: ${pre.reason}"))
                return Block.ToolResult(
                    call.id,
                    "blocked by plugin hook ${pre.deniedBy}: ${pre.reason}",
                    isError = true,
                )
            }
            input = pre.input
        }

        // 2. 技能白名单：只裁剪「暴露给模型」不够，执行侧也要拦，否则模型可以凭记忆调用未暴露的工具。
        val gate = skills?.activeAllowedTools()
        if (!gate.isNullOrEmpty() && call.name !in gate) {
            val reason = "tool '${call.name}' is not available while skill " +
                "'${skills?.active()?.name}' is active; allowed: ${gate.joinToString(", ")}"
            emit(AgentEvent.ToolDenied(call.id, call.name, reason))
            return Block.ToolResult(call.id, reason, isError = true)
        }

        // 3. 用户审批。
        val effective = effectiveRegistry()
        val spec = effective.get(call.name)?.spec()
        val verdict = broker.check(call.name, input, spec, toolContext.jail)

        if (!verdict.allowed) {
            emit(AgentEvent.ToolDenied(call.id, call.name, verdict.reason))
            return Block.ToolResult(call.id, "permission denied: ${verdict.reason}", isError = true)
        }

        emit(AgentEvent.ToolStarted(call.id, call.name, input))
        val started = System.currentTimeMillis()
        val outcome = withContext(Dispatchers.IO) {
            effective.execute(call.name, input, toolContext)
        }
        val elapsed = System.currentTimeMillis() - started

        // 4. PostToolUse 钩子：只读通知，内部异常不会影响已产生的结果。
        hooks?.postToolUse(call.name, input, outcome, elapsed)

        emit(AgentEvent.ToolFinished(call.id, call.name, outcome, elapsed))
        // 图片随 tool_result 回给模型（而不是另起一个兄弟块）：validatePairing 要求
        // tool_result 消息只含 ToolResult。
        return Block.ToolResult(call.id, outcome.content, outcome.isError, outcome.images)
    }

    /**
     * 校验工具调用配对：历史里每个 tool_use 必须被紧随其后的 USER tool_result 完整覆盖。
     * 返回 null 表示合法，否则返回人类可读的原因。
     */
    fun validatePairing(messages: List<Message>): String? {
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            if (msg.role == Role.ASSISTANT && msg.hasToolUse) {
                val ids = msg.toolUses.map { it.id }
                if (ids.size != ids.toSet().size) return "duplicate tool_use id in assistant message at $i"
                val next = messages.getOrNull(i + 1)
                    ?: return "assistant tool_use at $i has no following tool_result message"
                if (next.role != Role.USER || next.toolResults.isEmpty()) {
                    return "assistant tool_use at $i must be followed by a USER tool_result message"
                }
                val resultIds = next.toolResults.map { it.toolUseId }
                if (resultIds != ids) {
                    return "tool_result ids $resultIds do not match tool_use ids $ids at $i"
                }
                if (next.blocks.any { it !is Block.ToolResult }) {
                    return "tool_result message at ${i + 1} must contain only tool_result blocks"
                }
                i += 2
            } else {
                i++
            }
        }
        return null
    }
}
