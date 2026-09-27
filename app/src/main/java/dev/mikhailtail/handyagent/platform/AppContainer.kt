package dev.mikhailtail.handyagent.platform

import android.content.Context
import dev.mikhailtail.handyagent.core.context.Summarizer
import dev.mikhailtail.handyagent.core.permission.PermissionApprover
import dev.mikhailtail.handyagent.core.permission.PermissionRelay
import dev.mikhailtail.handyagent.core.platform.AgentRuntime
import dev.mikhailtail.handyagent.core.platform.FileImageBytesStore
import dev.mikhailtail.handyagent.core.platform.KeyValueStore
import dev.mikhailtail.handyagent.core.platform.RuntimeState
import dev.mikhailtail.handyagent.core.platform.StoreImageResolver
import dev.mikhailtail.handyagent.core.platform.WorkspaceLayout
import dev.mikhailtail.handyagent.core.provider.HttpEngine
import dev.mikhailtail.handyagent.core.provider.jvm.UrlConnectionHttpEngine
import dev.mikhailtail.handyagent.platform.mobile.AndroidMobileBridge
import dev.mikhailtail.handyagent.platform.mobile.HandyNotificationListener
import dev.mikhailtail.handyagent.ui.theme.CcThemeId
import java.io.File

/**
 * Android 侧的组装根：把平台实现（SharedPreferences / Keystore / HttpURLConnection）
 * 注入到与平台无关的 [AgentRuntime]。
 *
 * 生命周期：进程级单例，挂在 [HandyApp] 上。
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

    /** 图片字节存储：截图与附件都落在这里，会话文件只存文件名。 */
    val imageStore: FileImageBytesStore by lazy { FileImageBytesStore(layout.images) }

    /** 会话工厂；首次访问才加载 skills/plugins，避免拖慢冷启动。 */
    val runtime: AgentRuntime by lazy {
        // 通知监听服务写通知时需要一个出口；接到 KV 上，跨重启也能读
        HandyNotificationListener.sink = HandyNotificationListener.NotificationSink { records ->
            keyValueStore.putString(
                HandyNotificationListener.KEY,
                dev.mikhailtail.handyagent.core.json.Json.arr(records).encode(),
            )
        }
        AgentRuntime(
            layout = layout,
            kv = keyValueStore,
            cipher = cipher,
            http = http,
            approver = approvals,
            summarizer = summarizer,
            // 图片：会话里存 ref，发请求时才读字节编成 base64
            images = StoreImageResolver(imageStore),
            // 设备操作能力；未开启无障碍时各项会返回"不可用"，工具据此提示用户
            mobile = AndroidMobileBridge(appContext, imageStore, keyValueStore).build(),
        )
    }

    /** 建目录 + 连 MCP。幂等；应在后台线程调用一次。 */
    suspend fun start(): RuntimeState = runtime.start()

    // ------------------------------------------------------------------ 主题

    /**
     * 当前主题。用 Flow 而不是普通字段：设置页改了要立刻让整个界面重组。
     */
    val theme = kotlinx.coroutines.flow.MutableStateFlow(
        CcThemeId.fromName(keyValueStore.getString(THEME_KEY))
    )

    fun setTheme(id: CcThemeId) {
        keyValueStore.putString(THEME_KEY, id.name)
        theme.value = id
    }

    companion object {
        private const val THEME_KEY = "ui.theme"
    }
}
