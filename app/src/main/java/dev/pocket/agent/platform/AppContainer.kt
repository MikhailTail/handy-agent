package dev.pocket.agent.platform

import android.content.Context
import dev.pocket.agent.core.context.Summarizer
import dev.pocket.agent.core.permission.PermissionApprover
import dev.pocket.agent.core.permission.PermissionRelay
import dev.pocket.agent.core.platform.AgentRuntime
import dev.pocket.agent.core.platform.KeyValueStore
import dev.pocket.agent.core.platform.RuntimeState
import dev.pocket.agent.core.platform.WorkspaceLayout
import dev.pocket.agent.core.provider.HttpEngine
import dev.pocket.agent.core.provider.jvm.UrlConnectionHttpEngine
import java.io.File

/**
 * Android 侧的组装根：把平台实现（SharedPreferences / Keystore / HttpURLConnection）
 * 注入到与平台无关的 [AgentRuntime]。
 *
 * 生命周期：进程级单例，挂在 [PocketAgentApp] 上。
 * 构造是**廉价**的 —— 真正的磁盘 I/O（skills/plugins 扫描、目录创建、MCP 子进程）
 * 全部推迟到 [AgentRuntime] 首次被访问 / [start] 被调用，因此不会在主线程上做启动 I/O。
 *
 * [approver] 由 UI 层（Loop 9）通过 [approvals] 中继在启动后绑定：审批要弹卡片等用户点按。
 * 未绑定 / 未决策一律**拒绝**（fail-closed），而不是偷偷放行。
 * [summarizer] 缺省为 null，此时 [AgentRuntime] 会用当前 provider 生成真实摘要；
 * 只有连 provider 都没有时，压缩才退化为「丢弃旧轮次」。
 */
class AppContainer(
    context: Context,
    approver: PermissionApprover? = null,
    private val summarizer: Summarizer? = null,
    /** 测试/预览可覆盖；生产用 [Context.getFilesDir]。 */
    appRoot: File? = null,
) {

    val appContext: Context = context.applicationContext

    /**
     * 审批中继：装配期先装它（此时 Compose 还没起来，拿不到弹窗实现），
     * UI 就绪后把 [PermissionRelay.target] 指向自己。未绑定 = 拒绝，绝不静默放行。
     */
    val approvals: PermissionRelay = PermissionRelay().apply { target = approver }

    val layout: WorkspaceLayout = WorkspaceLayout(appRoot ?: appContext.filesDir)

    val keyValueStore: KeyValueStore = AndroidKeyValueStore.open(appContext)

    val cipher = AndroidKeystoreCipher()

    val http: HttpEngine = UrlConnectionHttpEngine()

    /** 会话工厂；首次访问才加载 skills/plugins，避免拖慢冷启动。 */
    val runtime: AgentRuntime by lazy {
        AgentRuntime(
            layout = layout,
            kv = keyValueStore,
            cipher = cipher,
            http = http,
            approver = approvals,
            summarizer = summarizer,
        )
    }

    /** 建目录 + 连 MCP。幂等；应在后台线程调用一次。 */
    suspend fun start(): RuntimeState = runtime.start()
}
