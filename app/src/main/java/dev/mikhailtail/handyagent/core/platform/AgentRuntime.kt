package dev.mikhailtail.handyagent.core.platform

import dev.mikhailtail.handyagent.core.context.ContextBudget
import dev.mikhailtail.handyagent.core.context.ContextManager
import dev.mikhailtail.handyagent.core.context.Summarizer
import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.loop.AgentLoop
import dev.mikhailtail.handyagent.core.loop.DEFAULT_SYSTEM_PROMPT
import dev.mikhailtail.handyagent.core.mcp.McpConfigParser
import dev.mikhailtail.handyagent.core.mcp.McpManager
import dev.mikhailtail.handyagent.core.mcp.McpServerStatus
import dev.mikhailtail.handyagent.core.mobile.MobileBridge
import dev.mikhailtail.handyagent.core.mobile.MobilePreviewInfo
import dev.mikhailtail.handyagent.core.mobile.MobilePreviewSource
import dev.mikhailtail.handyagent.core.mobile.MobileSessionState
import dev.mikhailtail.handyagent.core.model.ReasoningEffort
import dev.mikhailtail.handyagent.core.model.ToolSpec
import dev.mikhailtail.handyagent.core.permission.PermissionApprover
import dev.mikhailtail.handyagent.core.permission.PermissionBroker
import dev.mikhailtail.handyagent.core.permission.ToolPreviewer
import dev.mikhailtail.handyagent.core.plugin.PluginHost
import dev.mikhailtail.handyagent.core.plugin.PluginLoader
import dev.mikhailtail.handyagent.core.provider.HttpEngine
import dev.mikhailtail.handyagent.core.provider.ImageResolver
import dev.mikhailtail.handyagent.core.provider.LlmProvider
import dev.mikhailtail.handyagent.core.provider.ProviderConfig
import dev.mikhailtail.handyagent.core.provider.ProviderFactory
import dev.mikhailtail.handyagent.core.provider.ProviderSummarizer
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import dev.mikhailtail.handyagent.core.sandbox.ProcessShell
import dev.mikhailtail.handyagent.core.skill.SkillLoader
import dev.mikhailtail.handyagent.core.skill.SkillRegistry
import dev.mikhailtail.handyagent.core.skill.SkillTool
import dev.mikhailtail.handyagent.core.tool.MOBILE_TOOLS
import dev.mikhailtail.handyagent.core.tool.Tool
import dev.mikhailtail.handyagent.core.tool.ToolContext
import dev.mikhailtail.handyagent.core.tool.ToolRegistry
import dev.mikhailtail.handyagent.core.tool.mergeTools

/** 启动摘要：给日志 / 设置页显示「本次加载到了什么」。 */
data class RuntimeState(
    val workspace: String,
    val skills: List<String> = emptyList(),
    val plugins: List<String> = emptyList(),
    val loadErrors: List<String> = emptyList(),
    val providers: Int = 0,
    val activeProviderId: String? = null,
) {
    val healthy: Boolean get() = loadErrors.isEmpty()
}

/** 一次 [AgentRuntime.createLoop] 的可覆盖参数（设置页改完立刻对新会话生效）。 */
data class LoopOptions(
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val model: String? = null,
    val effort: ReasoningEffort? = null,
    val maxIterations: Int? = null,
    val maxTokens: Int = 4_096,
)

/**
 * 组装根（composition root）：把 Loop 1–7 的全部部件按用户设置接成一个可用的 Agent。
 *
 * 设计约束：
 * 1. 只依赖接口（[KeyValueStore] / [SecretCipher] / [HttpEngine] / [PermissionApprover]），
 *    因此整个装配过程可以在宿主机 JVM 上用假实现端到端测试，Android 侧只提供实现类。
 * 2. **绝不制造「半可用」的状态**：没配密钥就没有 provider，[createLoop] 返回 null，
 *    UI 据此提示「先去设置页填 key」，而不是拿空 key 发请求然后报 401。
 * 3. 每个会话一个新的 [AgentLoop]（历史不共享），但 skills / plugins / mcp 是长生命周期单例。
 */
class AgentRuntime(
    val layout: WorkspaceLayout,
    val kv: KeyValueStore,
    cipher: SecretCipher,
    val http: HttpEngine,
    private val approver: PermissionApprover? = null,
    private val summarizer: Summarizer? = null,
    private val presets: List<ProviderConfig> = ProviderFactory.presets(),
    private val contextMaxTokens: Int = 100_000,
    private val shellTimeoutMs: Long = 60_000,
    /** 图片载荷解析，由平台侧注入。默认不解析 —— 等价于「本后端不支持图片」。 */
    private val images: ImageResolver = ImageResolver.NONE,
    /** 设备操作能力；null = 桌面 / 测试环境，此时不注册 mobile 工具。 */
    private val mobile: MobileBridge? = null,
) {

    val credentials: CredentialStore = KeyValueCredentialStore(kv, cipher)
    val providerStore = ProviderStore(kv)
    val settingsStore = SettingsStore(kv)

    /** workspace 登记簿（id / 名字 / 时间）。 */
    val workspaceStore = WorkspaceStore(kv)

    /**
     * 未显式注入 summarizer 时用真实模型做摘要（惰性解析当前 provider，
     * 因此「换 provider 后立刻生效」）。注入方（测试）仍可覆盖。
     */
    private val defaultSummarizer: Summarizer by lazy {
        ProviderSummarizer(provider = { activeProvider() })
    }

    private val loadErrors = ArrayList<String>()

    /** 技能注册表（启动时从磁盘加载一次）。 */
    val skills: SkillRegistry = run {
        val result = SkillLoader.discover(layout.appRoot)
        loadErrors.addAll(result.errors.map { "skill: $it" })
        SkillRegistry(result.skills)
    }

    /** 插件宿主（含共享钩子总线；AgentLoop 直接使用这条总线）。 */
    val pluginHost: PluginHost = PluginHost()

    /**
     * 当前激活的 workspace。
     *
     * 切换它意味着"之后的会话在另一块沙箱里跑"。已经建好的 loop 仍绑在旧沙箱上，
     * 所以 UI 必须先让会话重建（[dev.mikhailtail.handyagent.ui.chat.ChatController.switchTo] 一类）。
     */
    @Volatile
    var activeWorkspaceId: String = WorkspaceLayout.DEFAULT_WORKSPACE_ID
        private set

    fun activateWorkspace(workspaceId: String) {
        activeWorkspaceId = workspaceId
    }

    // 沙箱按 workspace 缓存：PathJail 会做 canonicalPath 解析，没必要每次重建；
    // 但也绝不能全局只有一个 —— 那正是"所有会话共用一块沙箱"的根源。
    private val jailCache = java.util.concurrent.ConcurrentHashMap<String, PathJail>()
    private val shellCache = java.util.concurrent.ConcurrentHashMap<String, ProcessShell>()

    fun jailFor(workspaceId: String): PathJail =
        jailCache.computeIfAbsent(workspaceId) { PathJail(layout.filesOf(it)) }

    fun shellFor(workspaceId: String): ProcessShell =
        shellCache.computeIfAbsent(workspaceId) {
            ProcessShell(layout.filesOf(it), timeoutMs = shellTimeoutMs)
        }

    /** mobile 会话状态（最近快照 / 敏感标记 / 步数）；无 mobile 时也保留，只是工具用不到。 */
    val mobileState: MobileSessionState = MobileSessionState()

    /** 当前 workspace 的沙箱根（终端页与旧调用点用）。 */
    val jail: PathJail get() = jailFor(activeWorkspaceId)

    /** 当前 workspace 的命令执行器。 */
    val shell: ProcessShell get() = shellFor(activeWorkspaceId)

    /** 当前 workspace 的工具上下文。 */
    val toolContext: ToolContext get() = toolContextFor(activeWorkspaceId)

    /** 指定 workspace 的工具上下文；建 loop 时用它，保证工具落在正确的沙箱里。 */
    fun toolContextFor(workspaceId: String): ToolContext =
        ToolContext(jailFor(workspaceId), shellFor(workspaceId), mobile = mobile, mobileState = mobileState)

    /**
     * 审批预览器。mobile 工具要能看到**当前屏幕快照**，才能把 `{"index":12}` 翻译成
     * 「点击 [12] BUTTON「发送」」——否则用户根本不知道自己批准了什么。
     * 惰性读 [mobileState]，装配期还没有快照也不会出问题。
     */
    private val previewer: ToolPreviewer = if (mobile == null) {
        ToolPreviewer()
    } else {
        ToolPreviewer(mobilePreview = MobilePreviewSource {
            MobilePreviewInfo(tree = mobileState.lastTree)
        })
    }

    // 会话按 workspace 分开存：同一 workspace 下的会话共享沙箱，不同 workspace 的互不可见。
    private val sessionStores = java.util.concurrent.ConcurrentHashMap<String, SessionStore>()

    fun sessionsFor(workspaceId: String): SessionStore =
        sessionStores.computeIfAbsent(workspaceId) { FileSessionStore(layout.sessionsOf(it)) }

    /** 当前 workspace 的会话存储。 */
    val sessions: SessionStore get() = sessionsFor(activeWorkspaceId)

    /** 静态工具表：内置 + 插件贡献 + 技能加载器。MCP 工具在建 loop 时动态并入。 */
    val registry: ToolRegistry = run {
        loadErrors.addAll(pluginHost.loadFrom(layout.appRoot).map { "plugin: $it" })
        val merge = mergeTools(ToolRegistry.builtin(), pluginHost.tools())
        val withSkills = if (skills.size == 0) {
            merge.registry
        } else {
            mergeTools(merge.registry, listOf<Tool>(SkillTool(skills))).registry
        }
        // 只有平台真的提供了设备能力才注册 mobile 工具：否则模型会看到一堆必然失败的工具，
        // 白白吃掉上下文，还会诱导它去试。
        if (mobile == null) withSkills else mergeTools(withSkills, MOBILE_TOOLS).registry
    }

    private var mcpManager: McpManager? = null
    private var mcpStatuses: List<McpServerStatus> = emptyList()

    /** 启动：建目录 + 起 MCP（若启用）。幂等，可重复调用。 */
    suspend fun start(): RuntimeState {
        layout.bootstrap()
        startMcp()
        return state()
    }

    /**
     * 连接全部 MCP 服务端。单个失败只记录状态，不影响其它服务端，也不抛异常。
     * [AppSettings.mcpEnabled] 为 false 时直接跳过（用户可能只是暂时不想起子进程）。
     */
    suspend fun startMcp(): List<McpServerStatus> {
        val settings = settingsStore.load()
        if (!settings.mcpEnabled) {
            mcpStatuses = emptyList()
            return mcpStatuses
        }
        val parsed = McpConfigParser.parse(kv.getString(MCP_KEY) ?: "")
        if (parsed.servers.isEmpty()) {
            mcpStatuses = emptyList()
            return mcpStatuses
        }
        val manager = mcpManager ?: McpManager(parsed.servers, layout.workspace).also { mcpManager = it }
        mcpStatuses = manager.startAll()
        return mcpStatuses
    }

    fun mcpStatus(): List<McpServerStatus> = mcpStatuses

    /** 保存 MCP 配置原文（设置页直接编辑 JSON）。 */
    fun saveMcpConfig(json: String) {
        kv.putString(MCP_KEY, json)
    }

    fun mcpConfigText(): String = kv.getString(MCP_KEY) ?: ""

    fun state(): RuntimeState = RuntimeState(
        workspace = layout.workspace.absolutePath,
        skills = skills.names(),
        plugins = pluginHost.manifests().map { it.id },
        loadErrors = loadErrors.toList(),
        providers = availableProviders().size,
        activeProviderId = activeProvider()?.id,
    )

    /** 预置 + 用户配置合并后的完整列表（apiKey 为空）。 */
    fun configuredProviders(): List<ProviderConfig> =
        providerStore.mergeWithPresets(presets, providerStore.load().configs)

    /** 带密钥的列表，供设置页显示「已配置 key」。 */
    fun availableProviders(): List<ProviderConfig> =
        providerStore.withSecrets(configuredProviders(), credentials)

    // ------------------------------------------------------------ 模型列表缓存

    /**
     * 从 provider 拉到的模型列表缓存。
     *
     * 为什么要缓存而不是每次现拉：设置页一打开就打网络会让界面卡顿，而且离线时
     * 整个模型下拉会变空 —— 那比看到一份稍旧的列表更糟。
     */
    fun cachedModels(providerId: String): List<String> {
        val raw = kv.getString(modelsKey(providerId)) ?: return emptyList()
        return runCatching {
            (Json.parse(raw) as? Json.Arr)?.items.orEmpty().mapNotNull { it.asStringOrNull() }
        }.getOrDefault(emptyList())
    }

    fun cacheModels(providerId: String, models: List<String>) {
        if (models.isEmpty()) return
        kv.putString(modelsKey(providerId), Json.arrOfStrings(models).encode())
    }

    private fun modelsKey(providerId: String) = "provider.models.$providerId"

    /**
     * 当前生效的 provider：优先设置里选中的；未选（或选中的没 key）则回退到第一个配了 key 的。
     * 都没配 key → null。
     */
    fun activeProvider(): LlmProvider? {
        val settings = settingsStore.load()
        val providers = availableProviders()
        val chosen = providers.firstOrNull { it.id == settings.providerId && it.apiKey.isNotEmpty() }
            ?: providers.firstOrNull { it.apiKey.isNotEmpty() }
            ?: return null
        return try {
            ProviderFactory.create(chosen, http, images)
        } catch (e: Exception) {
            loadErrors.add("provider '${chosen.id}': ${e.message}")
            null
        }
    }

    /**
     * 新建一个会话。无可用 provider 时返回 null（UI 据此引导去设置页）。
     * [options] 里未指定的项回落到用户设置。
     */
    fun createLoop(options: LoopOptions = LoopOptions()): AgentLoop? {
        val provider = activeProvider() ?: return null
        val settings = settingsStore.load()

        val effectiveRegistry = effectiveRegistry()

        val broker = PermissionBroker(approver, previewer)
        broker.applyDefaults(effectiveRegistry.specs())
        settings.permissionModes.forEach { (tool, mode) -> broker.setMode(tool, mode) }

        val contextManager = ContextManager(
            ContextBudget(maxTokens = contextMaxTokens, threshold = settings.compactThreshold)
        )

        return AgentLoop(
            provider = provider,
            registry = effectiveRegistry,
            toolContext = toolContext,
            broker = broker,
            contextManager = contextManager,
            summarizer = summarizer ?: defaultSummarizer,
            systemPrompt = options.systemPrompt,
            skills = skills,
            pluginHost = pluginHost,
            mcpManager = mcpManager,
            model = options.model?.takeIf { it.isNotBlank() }
                ?: settings.resolvedModel(provider.config.defaultModel),
            effort = options.effort ?: settings.effort,
            maxTokens = options.maxTokens,
            maxIterations = options.maxIterations ?: settings.maxIterations,
        )
    }

    /** 当前生效的工具名（含 MCP 动态工具），供设置页展示与调试。 */
    fun toolNames(): List<String> = effectiveRegistry().names()

    /**
     * 当前生效的工具声明（含 MCP 动态工具）。设置页据此展示「只读 / 需要审批」，
     * 与 [PermissionBroker] 的判定同源 —— UI 不会和真正生效的策略说两套话。
     */
    fun toolSpecs(): List<ToolSpec> = effectiveRegistry().specs()

    /** 静态表 + 已连接 MCP 工具。MCP 未启动时就是静态表本身。 */
    private fun effectiveRegistry(): ToolRegistry =
        mcpManager?.let { mergeTools(registry, it.tools).registry } ?: registry

    companion object {
        const val MCP_KEY = "mcp.config"
    }
}
