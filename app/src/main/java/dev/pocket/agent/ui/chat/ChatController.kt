package dev.pocket.agent.ui.chat

import dev.pocket.agent.core.loop.AgentLoop
import dev.pocket.agent.core.model.ReasoningEffort
import dev.pocket.agent.core.permission.PermissionApprover
import dev.pocket.agent.core.permission.PermissionMode
import dev.pocket.agent.core.permission.PermissionRelay
import dev.pocket.agent.core.permission.PermissionRequest
import dev.pocket.agent.core.permission.PermissionVerdict
import dev.pocket.agent.core.platform.AgentRuntime
import dev.pocket.agent.core.platform.AppSettings
import dev.pocket.agent.core.platform.RuntimeState
import dev.pocket.agent.ui.timeline.TimelineItem
import dev.pocket.agent.ui.timeline.TimelineReducer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一次「等待用户裁决」的审批。UI 弹出审批卡，用户点按后 [ChatController.resolveApproval]
 * 完成 [decision]，挂起的工具调用才继续。
 *
 * [decision] 若始终不被完成（界面销毁 / 协程取消），broker 会按**拒绝**处理 ——
 * fail-closed，绝不因为 UI 消失而静默放行写操作。
 */
class PendingApproval(
    val request: PermissionRequest,
    internal val decision: CompletableDeferred<PermissionVerdict?>,
)

/** 对话页的全部可渲染状态。immutable，Compose 直接 diff。 */
data class ChatUiState(
    val items: List<TimelineItem> = emptyList(),
    val running: Boolean = false,
    /** [ChatController.start] 完成前为 false：此时 provider / 工具表还没就绪。 */
    val ready: Boolean = false,
    val approval: PendingApproval? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    /** 当前生效模型（已代入用户设置）。 */
    val model: String = "",
    val models: List<String> = emptyList(),
    val effort: ReasoningEffort = ReasoningEffort.DEFAULT,
    val maxIterations: Int = AppSettings.DEFAULT_MAX_ITERATIONS,
    /** 用户显式改过的审批策略；未列出的工具按 broker 的默认规则（只读放行、其余询问）。 */
    val permissionModes: Map<String, PermissionMode> = emptyMap(),
    /** 需要横幅提示的状态，如「尚未配置 API Key」。 */
    val notice: String? = null,
    val runtime: RuntimeState? = null,
) {
    /** 有可用 provider 且不在运行中时才允许发送。 */
    val canSend: Boolean get() = ready && providerId != null && !running

    val canStop: Boolean get() = running
}

/**
 * 对话页的状态机：把 [AgentLoop] 的事件流归约成时间轴，并把审批挂起成一个可渲染的卡片。
 *
 * 刻意**不依赖任何 android.* / compose.***：因此这整套「发送 → 流式 → 工具 → 审批 → 收尾」
 * 的交互可以在宿主 JVM 上用脚本化 LLM 端到端单测（见 ChatControllerTest），
 * Compose 只负责把 [state] 画出来。
 *
 * 线程模型：所有 [state] 写入都发生在 [scope] 的单线程语义里（生产 = Main.immediate，
 * 测试 = TestDispatcher）。工具执行在 [AgentLoop] 内部切到 IO，事件回到收集协程。
 *
 * **会话粒度**：一个会话**只有一个** [AgentLoop]，跨多轮复用（见 [sessionLoop]）。
 * 这不是实现细节而是语义要求：
 * 1. 历史连续性 —— 模型必须记得上一轮说了什么，而历史只活在 loop 里；
 * 2. 审批记忆 —— 「本会话始终允许」记在 loop 的 broker 上，每轮新建就等于没记住；
 * 3. [newSession] 才有意义 —— 它丢掉的正是那个 loop。
 * 设置变化时用 [rebindSession] 重建 loop（并 [AgentLoop.restore] 接回历史），
 * 于是「改完设置下一句就生效」和「对话不中断」同时成立。
 *
 * 取消语义：`runToken` 是**代际号**。停止 / 新建会话会先自增 token 再取消协程，
 * 于是被取消那次 run 的 `finally` 不会误清掉新一轮的 `running` 标志。
 */
class ChatController(
    private val runtimeProvider: () -> AgentRuntime,
    private val approvals: PermissionRelay,
    private val scope: CoroutineScope,
    /** 会话工厂。默认走 [AgentRuntime.createLoop]；测试注入脚本化 LLM 的 loop。 */
    private val loopFactory: (AgentRuntime) -> AgentLoop? = { it.createLoop() },
) : PermissionApprover {

    private val reducer = TimelineReducer()
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var runToken = 0L

    /**
     * 当前会话的 loop。惰性创建（首次 [send] / 首次需要时），跨轮复用；
     * [newSession] 与设置变更会替换它。
     */
    private var session: AgentLoop? = null

    init {
        // 装配期就把审批权接过来：任何早于 UI 首帧的审批都会挂起等待，而不是被拒绝。
        approvals.target = this
    }

    /** 界面销毁：退回「无审批人」状态，避免中继持有已销毁的 UI。 */
    fun dispose() {
        if (approvals.target === this) approvals.target = null
    }

    /**
     * 取本会话的 loop，必要且可能时创建。返回 null 表示没有可用 provider。
     *
     * loop 由 [loopFactory] 造（生产 = [AgentRuntime.createLoop]，内含把审批指向本
     * controller 的 [PermissionRelay]），因此**同一会话内审批策略的「记住」是持久的**。
     */
    private fun sessionLoop(): AgentLoop? {
        session?.let { return it }
        val created = loopFactory(runtimeProvider()) ?: return null
        session = created
        return created
    }

    /**
     * 设置变了 → 按新配置重建会话 loop，并**把历史接回**（[AgentLoop.restore]）。
     *
     * 于是「改完设置下一句就生效」（模型 / 强度 / 轮次上限 / 审批策略都是构造期参数，
     * 不重建就不会变）与「对话不中断」（重建但不丢上下文）同时成立。
     *
     * 正在跑的一轮**不重建**：流式响应中途换掉会话会让这一轮的结果落进一个已被丢弃的
     * loop 里；等它收尾后再由用户的下一次操作触发。
     */
    private fun rebindSession() {
        if (_state.value.running) return
        val current = session ?: return
        val rebuilt = loopFactory(runtimeProvider())
        if (rebuilt == null) {
            // 例如用户清掉了唯一的 API Key：会话作废，下一句会走「去设置页填 key」的提示。
            session = null
            return
        }
        val history = current.history()
        if (history.isNotEmpty()) {
            // 历史理论上总是合法的（AgentLoop 在中断时已补齐 tool_result）。
            // 万一不合法，宁可从一个干净会话继续，也不要把 UI 崩在恢复历史这一步。
            runCatching { rebuilt.restore(history) }
        }
        session = rebuilt
    }

    /**
     * 后台完成重活：触发 [AgentRuntime] 的惰性构造（skills / plugins 扫描）、建目录、连 MCP，
     * 然后同步一次设置。冷启动不阻塞主线程，也不在主线程碰磁盘。
     */
    suspend fun start() {
        val rt = withContext(Dispatchers.IO) { runtimeProvider() }
        val snapshot = withContext(Dispatchers.IO) { rt.start() }
        _state.value = _state.value.copy(ready = true, runtime = snapshot)
        refresh()
    }

    /** 重新读取设置 / provider（设置页改完、或从后台回到前台时调用）。 */
    fun refresh() {
        val rt = runtimeProvider()
        val settings = rt.settingsStore.load()
        val provider = rt.activeProvider()
        val model = provider?.let { settings.resolvedModel(it.config.defaultModel) }.orEmpty()
        val models = provider?.config?.models.orEmpty()
            .ifEmpty { listOfNotNull(model.takeIf { it.isNotBlank() }) }
        _state.value = _state.value.copy(
            providerId = provider?.id,
            providerName = provider?.displayName,
            model = model,
            models = models,
            effort = settings.effort,
            maxIterations = settings.maxIterations,
            permissionModes = settings.permissionModes,
            notice = if (provider == null && _state.value.ready) NO_PROVIDER else null,
            runtime = rt.state(),
        )
    }

    // ------------------------------------------------------------------ 会话

    /** 落一条用户消息并跑一轮。同一时刻只允许一轮（[ChatUiState.running] 守卫）。 */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.running) return

        // 复用本会话的 loop：历史与审批记忆都活在它里面。
        val loop = sessionLoop()
        if (loop == null) {
            _state.value = _state.value.copy(notice = NO_PROVIDER)
            return
        }

        val token = ++runToken
        _state.value = _state.value.copy(
            items = reducer.user(trimmed),
            running = true,
            notice = null,
        )

        job = scope.launch {
            try {
                loop.run(trimmed).collect { event ->
                    if (token == runToken) {
                        _state.value = _state.value.copy(items = reducer.apply(event))
                    }
                }
            } catch (e: CancellationException) {
                // 停止 / 新建会话：AgentLoop 已补齐被中断的 tool_result（历史仍合法），
                // 时间轴上的「已中止」由 stop() 负责落，不在这里重复添一行。
                throw e
            } catch (e: Throwable) {
                if (token == runToken) {
                    _state.value = _state.value.copy(
                        items = reducer.note(
                            TimelineItem.Note.Kind.ERROR,
                            e.message ?: e::class.simpleName ?: "未知错误",
                        )
                    )
                }
            } finally {
                if (token == runToken) {
                    _state.value = _state.value.copy(running = false)
                }
            }
        }
    }

    /**
     * 中止当前轮。先自增代际号再取消，因此被取消那次 run 的收尾逻辑不会覆盖这里写入的状态。
     * 时间轴立刻落一条 INFO 提示（而不是等 AgentLoop 的 Aborted 事件 —— 取消后它可能来不及送达）。
     */
    fun stop() {
        if (!_state.value.running) return
        runToken++
        job?.cancel()
        job = null
        _state.value = _state.value.copy(
            items = reducer.note(TimelineItem.Note.Kind.INFO, ABORTED_TEXT),
            running = false,
        )
    }

    /**
     * 开新会话：丢掉旧的 loop（历史与审批记忆随之作废）、清时间轴。
     * 审批卡一并撤回（挂起的那次调用按拒绝处理）。
     */
    fun newSession() {
        runToken++
        job?.cancel()
        job = null
        session = null
        reducer.clear()
        _state.value = _state.value.copy(items = emptyList(), running = false, approval = null)
    }

    // ------------------------------------------------------------------ 设置

    fun setProvider(id: String) = mutateSettings { it.copy(providerId = id) }

    fun setModel(model: String) = mutateSettings { it.copy(model = model) }

    fun setEffort(effort: ReasoningEffort) = mutateSettings { it.copy(effort = effort) }

    fun setMaxIterations(iterations: Int) = mutateSettings { it.copy(maxIterations = iterations) }

    /**
     * 修改单个工具的审批策略（设置页的「放行 / 询问 / 禁止」）。
     *
     * 写进 [AppSettings.permissionModes]，由 [rebindSession] 在**下一次** [send] 前
     * 灌进新 broker：正在跑的那一轮策略不变（避免同一轮里权限忽松忽紧），
     * 之后的对话立即按新策略执行。
     */
    fun setPermissionMode(tool: String, mode: PermissionMode) = mutateSettings {
        it.copy(permissionModes = it.permissionModes + (tool to mode))
    }

    /** 设置页「已配置密钥」变更后重新读取（密钥写进 [CredentialStore]，不经过设置）。 */
    fun reloadProviders() {
        refresh()
        // 密钥变化可能让「原本没有 provider」变成可用（或反之），会话必须跟着换。
        rebindSession()
    }

    private fun mutateSettings(transform: (dev.pocket.agent.core.platform.AppSettings) -> dev.pocket.agent.core.platform.AppSettings) {
        runtimeProvider().settingsStore.update(transform)
        refresh()
        // 模型 / 强度 / 轮次上限 / 审批策略都是 loop 的构造期参数：不重建就不会生效。
        rebindSession()
    }

    // ------------------------------------------------------------------ 审批

    /** 实现 [PermissionApprover]：把请求变成一张卡，然后挂起等待用户点按。 */
    override suspend fun approve(request: PermissionRequest): PermissionVerdict? {
        val pending = PendingApproval(request, CompletableDeferred())
        _state.value = _state.value.copy(approval = pending)
        return try {
            pending.decision.await()
        } finally {
            if (_state.value.approval === pending) {
                _state.value = _state.value.copy(approval = null)
            }
        }
    }

    /** UI 点按：允许一次 / 本会话始终允许 / 拒绝一次 / 本会话始终拒绝。 */
    fun resolveApproval(verdict: PermissionVerdict) {
        _state.value.approval?.decision?.complete(verdict)
    }

    /** 审批卡被系统返回键 / 点外部关闭，等同于「拒绝这一次」。 */
    fun dismissApproval() = resolveApproval(PermissionVerdict.denyOnce())

    companion object {
        const val NO_PROVIDER = "尚未配置可用的模型：请到「设置」页填入 API Key。"
        const val ABORTED_TEXT = "已中止本轮。历史已修复为可继续的状态。"
    }
}
