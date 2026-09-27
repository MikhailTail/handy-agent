package dev.mikhailtail.handyagent.platform.mobile

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import dev.mikhailtail.handyagent.core.mobile.RectI
import dev.mikhailtail.handyagent.core.mobile.ScreenInfo
import dev.mikhailtail.handyagent.core.mobile.ScreenRotation
import dev.mikhailtail.handyagent.core.mobile.UiNode
import android.view.Surface
import dev.mikhailtail.handyagent.core.mobile.WindowInfo
import dev.mikhailtail.handyagent.core.mobile.WindowType
import android.view.accessibility.AccessibilityWindowInfo

/**
 * `AccessibilityNodeInfo` → 平台无关的 [UiNode]。
 *
 * 转换只在 dump 的那一刻发生，转完立刻丢掉 node 引用 ——
 * `AccessibilityNodeInfo` 是跨进程句柄，界面一变就失效（stale），
 * 跨步骤持有它一定会点错东西。
 */
object AccessibilityTreeMapper {

    /** 树太深时截断：真实界面极少超过 30 层，再深基本是恶意/异常布局。 */
    private const val MAX_DEPTH = 30

    /** 单次 dump 的节点上限，防止极端界面把内存撑爆。 */
    private const val MAX_NODES = 3000

    private val tmpRect = Rect()

    fun toUiNode(root: AccessibilityNodeInfo, windowId: Int = 0): UiNode? {
        val counter = intArrayOf(0)
        return convert(root, 0, windowId, counter)
    }

    private fun convert(
        node: AccessibilityNodeInfo,
        depth: Int,
        windowId: Int,
        counter: IntArray,
    ): UiNode? {
        if (depth > MAX_DEPTH || counter[0] >= MAX_NODES) return null
        counter[0]++

        node.getBoundsInScreen(tmpRect)
        val bounds = RectI(tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.bottom)

        val children = ArrayList<UiNode>(node.childCount)
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            convert(child, depth + 1, windowId, counter)?.let { children.add(it) }
            // 注意：child 不需要手动 recycle —— API 33 起 recycle() 已是空操作，
            // 且强行调用会在部分 ROM 上抛异常。
        }

        return UiNode(
            className = shortClassName(node.className?.toString()),
            viewId = node.viewIdResourceName,
            text = node.text?.toString()?.takeIf { it.isNotBlank() },
            contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            hintText = runCatching { node.hintText?.toString() }.getOrNull()?.takeIf { it.isNotBlank() },
            bounds = bounds,
            depth = depth,
            windowId = windowId,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            selected = node.isSelected,
            enabled = node.isEnabled,
            focusable = node.isFocusable,
            password = node.isPassword,
            visibleToUser = node.isVisibleToUser,
            children = children,
        )
    }

    /** 长包名对模型没有信息量，留短名（`android.widget.Button` → `Button`）。 */
    private fun shortClassName(full: String?): String {
        if (full.isNullOrBlank()) return "View"
        val idx = full.lastIndexOf('.')
        return if (idx >= 0 && idx < full.length - 1) full.substring(idx + 1) else full
    }

    fun windowTypeOf(type: Int): WindowType = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
        AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
        else -> WindowType.UNKNOWN
    }

    fun toWindowInfo(w: AccessibilityWindowInfo): WindowInfo = WindowInfo(
        id = w.id,
        type = windowTypeOf(w.type),
        packageName = runCatching { w.root?.packageName?.toString() }.getOrNull(),
        focused = runCatching { w.isFocused }.getOrDefault(false),
        title = runCatching { w.title?.toString() }.getOrNull(),
    )

    fun rotationOf(surfaceRotation: Int): ScreenRotation = when (surfaceRotation) {
        Surface.ROTATION_0 -> ScreenRotation.PORTRAIT
        Surface.ROTATION_90 -> ScreenRotation.LANDSCAPE
        Surface.ROTATION_180 -> ScreenRotation.PORTRAIT_REVERSED
        Surface.ROTATION_270 -> ScreenRotation.LANDSCAPE_REVERSED
        else -> ScreenRotation.UNKNOWN
    }
}
