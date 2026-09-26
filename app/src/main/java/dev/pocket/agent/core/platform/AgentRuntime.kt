package dev.pocket.agent.core.platform

import dev.pocket.agent.core.context.ContextBudget
import dev.pocket.agent.core.context.ContextManager
import dev.pocket.agent.core.context.Summarizer
import dev.pocket.agent.core.loop.AgentLoop
import dev.pocket.agent.core.loop.DEFAULT_SYSTEM_PROMPT
import dev.pocket.agent.core.mcp.McpConfigParser
import dev.pocket.agent.core.mcp.McpManager
import dev.pocket.agent.core.mcp.McpServerStatus
import dev.pocket.agent.core.model.ReasoningEffort
import dev.pocket.agent.core.model.ToolSpec
import dev.pocket.agent.core.permission.PermissionApprover
import dev.pocket.agent.core.permission.PermissionBroker
import dev.pocket.agent.core.plugin.PluginHost
import dev.pocket.agent.core.plugin.PluginLoader
import dev.pocket.agent.core.provider.HttpEngine
import dev.pocket.agent.core.provider.LlmProvider
import dev.pocket.agent.core.provider.ProviderConfig
import dev.pocket.agent.core.provider.ProviderFactory
import dev.pocket.agent.core.provider.ProviderSummarizer
import dev.pocket.agent.core.sandbox.PathJail
import dev.pocket.agent.core.sandbox.ProcessShell
import dev.pocket.agent.core.skill.SkillLoader
import dev.pocket.agent.core.skill.SkillRegistry
import dev.pocket.agent.core.skill.SkillTool
import dev.pocket.agent.core.tool.Tool
import dev.pocket.agent.core.tool.ToolContext
import dev.pocket.agent.core.tool.ToolRegistry
import dev.pocket.agent.core.tool.mergeTools

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
) {

    val credentials: CredentialStore = KeyValueCredentialStore(kv, cipher)
    val providerStore = ProviderStore(kv)
    val settingsStore = SettingsStore(kv)

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

    val jail: PathJail = PathJail(layout.workspace)
    val shell: ProcessShell = ProcessShell(layout.workspace, timeoutMs = shellTimeoutMs)
    val toolContext: ToolContext = ToolContext(jail, shell)

    /** 静态工具表：内置 + 插件贡献 + 技能加载器。MCP 工具在建 loop 时动态并入。 */
    val registry: ToolRegistry = run {
        loadErrors.addAll(pluginHost.loadFrom(layout.appRoot).map { "plugin: $it" })
        val merge = mergeTools(ToolRegistry.builtin(), pluginHost.tools())
        if (skills.size == 0) {
            merge.registry
        } else {
            mergeTools(merge.registry, listOf<Tool>(SkillTool(skills))).registry
        }
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
            ProviderFactory.create(chosen, http)
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

        val broker = PermissionBroker(approver)
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
