package dev.mikhailtail.handyagent.core.mobile

/**
 * 屏幕坐标矩形，单位是**屏幕绝对像素**。
 *
 * 与 Android 的 `AccessibilityNodeInfo.getBoundsInScreen()` 以及
 * `dispatchGesture()` 处于同一坐标系，所以**绝不能再乘 density**（这是很容易犯的错）。
 */
data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun center(): PointI = PointI(centerX, centerY)

    /** 把点夹到本矩形内，避免落到屏幕外（手势会因此被系统丢弃）。 */
    fun clamp(p: PointI): PointI = PointI(
        p.x.coerceIn(left, (right - 1).coerceAtLeast(left)),
        p.y.coerceIn(top, (bottom - 1).coerceAtLeast(top)),
    )

    override fun toString(): String = "[$left,$top,$right,$bottom]"

    companion object {
        val EMPTY = RectI(0, 0, 0, 0)
    }
}

data class PointI(val x: Int, val y: Int) {
    override fun toString(): String = "($x,$y)"
}

enum class ScrollDir { UP, DOWN, LEFT, RIGHT }

/** 无障碍可执行的全局按键。 */
enum class MobileKey { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, ENTER, DELETE, SEARCH }

enum class ScreenRotation { PORTRAIT, LANDSCAPE, PORTRAIT_REVERSED, LANDSCAPE_REVERSED, UNKNOWN }

data class ScreenInfo(
    val width: Int,
    val height: Int,
    val densityDpi: Int = 0,
    val rotation: ScreenRotation = ScreenRotation.PORTRAIT,
) {
    val bounds: RectI get() = RectI(0, 0, width, height)
}

enum class WindowType { APPLICATION, INPUT_METHOD, SYSTEM, ACCESSIBILITY_OVERLAY, NOTIFICATION, UNKNOWN }

data class WindowInfo(
    val id: Int,
    val type: WindowType,
    val packageName: String? = null,
    val focused: Boolean = false,
    val title: String? = null,
)

/**
 * 平台无关的控件树快照。
 *
 * Android 侧只在 dump 的那一刻把 `AccessibilityNodeInfo` 转成它，之后**不再持有节点引用**
 * —— node 引用会随界面变化立即失效（stale），跨步骤复用一定会点错地方。
 */
data class UiNode(
    /** 短类名，如 `Button` / `EditText`（长包名对模型没有信息量，还占 token）。 */
    val className: String,
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val bounds: RectI = RectI.EMPTY,
    val depth: Int = 0,
    val windowId: Int = 0,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focusable: Boolean = false,
    /** 密码框：命中它要触发敏感界面处理，禁止自动填入。 */
    val password: Boolean = false,
    val visibleToUser: Boolean = true,
    val children: List<UiNode> = emptyList(),
) {
    /** 节点是否"有用"——纯布局容器对模型没有价值，不该占序号和 token。 */
    val interactive: Boolean
        get() = clickable || longClickable || editable || scrollable || checkable || focusable

    val hasLabel: Boolean
        get() = !text.isNullOrBlank() || !contentDescription.isNullOrBlank()

    /** 值得暴露给模型的节点。 */
    val meaningful: Boolean get() = interactive || hasLabel
}
