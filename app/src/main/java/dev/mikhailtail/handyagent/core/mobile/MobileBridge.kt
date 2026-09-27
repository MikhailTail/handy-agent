package dev.mikhailtail.handyagent.core.mobile

import dev.mikhailtail.handyagent.core.model.ImageRef

/**
 * 设备能力接口层。
 *
 * 放在 `core.mobile` 而不是 `core.platform`：`ToolContext` 需要引用它，
 * 而 `AgentRuntime`（core.platform）又要引用 `ToolContext` —— 放 platform 会形成包级循环。
 * 从语义上说，"移动领域的一切"（纯逻辑 + 能力接口）同处一包也更自洽。
 */

/** 一项设备能力的可用状态。UI 的"能力体检"页直接把它渲染成清单。 */
enum class Capability {
    ACCESSIBILITY,
    NOTIFICATION_LISTENER,
    IME,
    FOREGROUND_SERVICE,
    BATTERY_UNRESTRICTED,
    OVERLAY,
}

data class CapabilityStatus(
    val capability: Capability,
    val available: Boolean,
    /** 给人看的一句话说明（为什么不可用 / 怎么开）。 */
    val detail: String = "",
)

/**
 * 能力不可用的原因。
 *
 * 这些都是**可恢复状态**而非错误：服务没开、被限流、遇到安全窗口……
 * 工具会把它翻译成给模型看的提示，而不是抛异常中断整轮对话。
 */
enum class UnavailableReason {
    SERVICE_NOT_ENABLED,
    NOTIFICATION_ACCESS_MISSING,
    IME_NOT_SELECTED,
    RATE_LIMITED,
    SECURE_WINDOW,
    TIMEOUT,
    GESTURE_PREEMPTED,
    STALE_TREE,
    NOT_FOUND,
    UNKNOWN,
}

/** 移动能力的统一返回类型：区分「不可用」与「执行失败」，上层据此给出不同提示。 */
sealed interface MobileResult<out T> {
    data class Ok<T>(val value: T) : MobileResult<T>
    data class Unavailable(val reason: UnavailableReason, val message: String) : MobileResult<Nothing>
    data class Failed(val message: String) : MobileResult<Nothing>
}

/** 设备能力探测（体检页与工具执行前的守卫都用它）。 */
interface MobileCapabilityProbe {
    fun list(): List<CapabilityStatus>

    fun isAvailable(capability: Capability): Boolean =
        list().any { it.capability == capability && it.available }
}

/** 截屏。实现必须遵守平台限流（无障碍截图约 3 张/秒）。 */
fun interface ScreenCapture {
    suspend fun capture(label: String): MobileResult<ImageRef>
}

/** 读当前界面（无障碍树），返回 P5 的扁平结果。 */
fun interface UiInspector {
    suspend fun dump(includeInvisible: Boolean): MobileResult<FlatTree>
}

interface MobileInput {
    suspend fun tap(x: Int, y: Int, durationMs: Int = 60): MobileResult<Unit>
    suspend fun swipe(path: List<Pair<Int, Int>>, durationMs: Int = 300): MobileResult<Unit>
    /** 全局按键（返回/主页/通知栏等）；输入文字不走这里。 */
    suspend fun globalAction(key: MobileKey): MobileResult<Unit>
    /** 往当前焦点输入框写文字。 */
    suspend fun type(text: String): MobileResult<Unit>
}

interface AppController {
    suspend fun launch(packageName: String): MobileResult<Unit>
    suspend fun foregroundPackage(): MobileResult<String>
    fun screenInfo(): MobileResult<ScreenInfo>
}

data class NotifRecord(
    val key: String,
    val packageName: String,
    val title: String? = null,
    val text: String? = null,
    val postedAt: Long = 0L,
    val actions: List<String> = emptyList(),
)

interface NotificationReader {
    fun available(): Boolean
    fun recent(limit: Int = 20, packageFilter: String? = null): List<NotifRecord>
}

/**
 * 移动能力的聚合门面，注入 `ToolContext` 供工具使用。
 *
 * **实现必须是惰性的**：无障碍服务是异步绑定、且随时可能被系统关掉的，
 * 所以这里不能持服务句柄 —— 每次调用都要重新取当前实例并判断可用性。
 */
class MobileBridge(
    val capability: MobileCapabilityProbe,
    val screen: ScreenCapture,
    val ui: UiInspector,
    val input: MobileInput,
    val apps: AppController,
    val notifications: NotificationReader,
) {
    companion object {
        const val NO_SERVICE = "无障碍服务未启用：请到「设置 → 无障碍」打开本应用的服务"

        /** 空实现：所有能力都不可用。桌面端 / 未接平台时使用，工具会给出明确引导。 */
        fun unavailable(): MobileBridge = MobileBridge(
            capability = object : MobileCapabilityProbe {
                override fun list(): List<CapabilityStatus> =
                    Capability.entries.map { CapabilityStatus(it, false, "当前环境不支持设备操作") }
            },
            screen = { MobileResult.Unavailable(UnavailableReason.SERVICE_NOT_ENABLED, NO_SERVICE) },
            ui = { MobileResult.Unavailable(UnavailableReason.SERVICE_NOT_ENABLED, NO_SERVICE) },
            input = UnavailableInput,
            apps = UnavailableApps,
            notifications = object : NotificationReader {
                override fun available(): Boolean = false
                override fun recent(limit: Int, packageFilter: String?): List<NotifRecord> = emptyList()
            },
        )
    }
}

private object UnavailableInput : MobileInput {
    private fun no() = MobileResult.Unavailable(UnavailableReason.SERVICE_NOT_ENABLED, MobileBridge.NO_SERVICE)
    override suspend fun tap(x: Int, y: Int, durationMs: Int) = no()
    override suspend fun swipe(path: List<Pair<Int, Int>>, durationMs: Int) = no()
    override suspend fun globalAction(key: MobileKey) = no()
    override suspend fun type(text: String) = no()
}

private object UnavailableApps : AppController {
    override suspend fun launch(packageName: String) =
        MobileResult.Unavailable(UnavailableReason.SERVICE_NOT_ENABLED, MobileBridge.NO_SERVICE)

    override suspend fun foregroundPackage(): MobileResult<String> =
        MobileResult.Unavailable(UnavailableReason.SERVICE_NOT_ENABLED, MobileBridge.NO_SERVICE)

    override fun screenInfo(): MobileResult<ScreenInfo> =
        MobileResult.Unavailable(UnavailableReason.SERVICE_NOT_ENABLED, MobileBridge.NO_SERVICE)
}
