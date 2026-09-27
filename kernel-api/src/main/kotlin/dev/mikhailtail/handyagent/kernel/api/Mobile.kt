package dev.mikhailtail.handyagent.kernel.api

/**
 * 手机操作能力。
 *
 * **这是 cc-haha 的「语义路径」在 Android 上的对应物。**
 * 它的 computer use 有两条实现：Windows 走像素（截图 → 坐标 → SendInput），
 * macOS 走语义（AX 元素句柄）。**Android 的无障碍节点树属于后者** ——
 * 能按元素而非坐标操作，比像素路径稳得多（窗口移动、分辨率变化都不影响）。
 *
 * 方法名对齐 `CodexComputerEngine` 的那几个（listApps / getAppState / click /
 * setValue / scroll / pressKey），这样换宿主不换语义。
 */
interface MobileCapability {

    /** 服务是否已就绪（无障碍被系统关掉时为 false）。 */
    fun isReady(): Boolean

    /** 未就绪时的原因，用来告诉用户该去开什么。 */
    fun unavailableReason(): String?

    /** 列出可启动的应用。 */
    suspend fun listApps(): List<MobileApp>

    /**
     * 读取当前界面。
     *
     * 一次调用同时给出**控件树**与**截图**，这是刻意的：
     * 树负责"精确选中"（按 index 点），截图负责"看清内容"
     * （图标、图片、颜色、没有语义标签的区域）。缺任何一个都会让模型瞎猜。
     */
    suspend fun getState(): MobileState

    /** 按元素索引点击。索引来自最近一次 [getState]，见 [MobileState.dumpId]。 */
    suspend fun click(index: Int, dumpId: String): MobileResult<Unit>

    /** 按坐标点击（元素点不动时的退路，坐标从截图里读）。 */
    suspend fun tap(x: Int, y: Int): MobileResult<Unit>

    /** 在输入框里写入文本。 */
    suspend fun setValue(index: Int, dumpId: String, text: String): MobileResult<Unit>

    /** 从某个元素起滑动（或从屏幕中心）。 */
    suspend fun scroll(index: Int?, dumpId: String, direction: ScrollDirection, pages: Double): MobileResult<Unit>

    /** 按系统键（返回、主页、回车等）。 */
    suspend fun pressKey(key: MobileKey): MobileResult<Unit>

    /** 启动应用。 */
    suspend fun launchApp(packageName: String): MobileResult<Unit>

    /** 读取通知。 */
    suspend fun readNotifications(): List<MobileNotification>
}

/**
 * 一次界面快照。
 *
 * [dumpId] 是这套设计的关键：**每次 [MobileCapability.getState] 产生一个新的 dumpId**，
 * 元素索引只在它对应的那次快照内有效。模型若拿旧的 index 来点，必须被拒绝并让它重新观察 ——
 * 界面早就变了，那个 index 此刻可能指向完全不同的东西（比如"删除"按钮）。
 */
data class MobileState(
    val dumpId: String,
    val packageName: String?,
    val appLabel: String?,
    /** 压平成带序号的控件清单，直接给模型读。 */
    val treeText: String,
    /** 当前屏幕截图（JPEG base64）。FLAG_SECURE 界面拿不到，那时为 null。 */
    val screenshotBase64: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    /** 受保护界面（银行/支付等 FLAG_SECURE 窗口）。 */
    val secure: Boolean = false,
)

data class MobileApp(
    val packageName: String,
    val label: String,
)

data class MobileNotification(
    val packageName: String,
    val title: String?,
    val text: String?,
    val postedAt: Long,
)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** 系统按键。对齐 Android 的 KEYCODE_*，只保留手机上有意义的那些。 */
enum class MobileKey(val androidKeyCode: Int) {
    BACK(4),
    HOME(3),
    ENTER(66),
    TAB(61),
    ESCAPE(111),
    DEL(67),
    APP_SWITCH(187),
}

/**
 * 操作结果。
 *
 * **失败要区分原因**：索引过期（让模型重新观察）与能力不可用（让用户去开权限）
 * 是完全不同的处置，混成一个"失败"模型只会反复重试。
 */
sealed interface MobileResult<out T> {
    data class Ok<T>(val value: T) : MobileResult<T>
    data class Failed(val message: String) : MobileResult<Nothing>
    /** 索引/快照过期：界面变了，必须重新 [MobileCapability.getState]。 */
    data class Stale(val message: String) : MobileResult<Nothing>
    /** 能力缺失（无障碍未开启、截图被 FLAG_SECURE 拒绝等）。 */
    data class Unavailable(val message: String) : MobileResult<Nothing>
}

/** 便捷：取出值或抛出可读错误。 */
fun <T> MobileResult<T>.getOrThrow(): T = when (this) {
    is MobileResult.Ok -> value
    is MobileResult.Failed -> throw MobileUnavailableException(message)
    is MobileResult.Stale -> throw MobileUnavailableException(message)
    is MobileResult.Unavailable -> throw MobileUnavailableException(message)
}

class MobileUnavailableException(message: String) : Exception(message)
