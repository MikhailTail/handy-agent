package dev.mikhailtail.handyagent.core.mobile

/**
 * 手势坐标计算（纯函数，可在宿主 JVM 上单测）。
 *
 * **唯一但致命的约定**：所有坐标都是「屏幕绝对像素」。
 * `AccessibilityNodeInfo.getBoundsInScreen()` 与 `dispatchGesture()` 用的是同一个坐标系，
 * 所以**绝对不要乘 density** —— 乘了之后在高 DPI 机器上点哪儿都不对，
 * 而且在低 DPI 的模拟器上还测不出来。
 */
object GesturePlanner {

    /** 点击点 = 矩形中心，并夹进屏幕内。 */
    fun tapPoint(bounds: RectI, screen: ScreenInfo): PointI =
        clampToScreen(bounds.center(), screen)

    /**
     * 从 [bounds] 区域朝 [dir] 滑动的手指路径（3 个采样点即可，交给系统插值）。
     *
     * [dir] 是**手指移动方向**：`UP` = 手指向上划（内容往下滚）。
     */
    fun swipeIn(
        bounds: RectI,
        dir: ScrollDir,
        screen: ScreenInfo,
        distancePx: Int? = null,
    ): List<PointI> {
        val from = clampToScreen(bounds.center(), screen)
        val dist = (distancePx ?: defaultDistance(bounds, dir, screen)).coerceAtLeast(1)
        val to = when (dir) {
            ScrollDir.UP -> PointI(from.x, from.y - dist)
            ScrollDir.DOWN -> PointI(from.x, from.y + dist)
            ScrollDir.LEFT -> PointI(from.x - dist, from.y)
            ScrollDir.RIGHT -> PointI(from.x + dist, from.y)
        }
        return swipePath(from, clampToScreen(to, screen))
    }

    /** 两点之间的直线路径，[steps] 个采样点（含首尾）。 */
    fun swipePath(from: PointI, to: PointI, steps: Int = 12): List<PointI> {
        val n = steps.coerceAtLeast(2)
        return (0 until n).map { i ->
            val t = i.toDouble() / (n - 1)
            PointI(
                Math.round(from.x + (to.x - from.x) * t).toInt(),
                Math.round(from.y + (to.y - from.y) * t).toInt(),
            )
        }
    }

    /** 默认滑动距离：控件在该方向尺寸的 60%，但不小于屏幕的 1/4（太短会触发不了滑动）。 */
    fun defaultDistance(bounds: RectI, dir: ScrollDir, screen: ScreenInfo): Int = when (dir) {
        ScrollDir.UP, ScrollDir.DOWN -> maxOf(bounds.height * 6 / 10, screen.height / 4)
        ScrollDir.LEFT, ScrollDir.RIGHT -> maxOf(bounds.width * 6 / 10, screen.width / 4)
    }

    /** 夹到屏幕内并内缩 1px：贴边的点会被系统判为无效手势直接丢弃。 */
    private fun clampToScreen(p: PointI, screen: ScreenInfo): PointI = PointI(
        p.x.coerceIn(1, (screen.width - 2).coerceAtLeast(1)),
        p.y.coerceIn(1, (screen.height - 2).coerceAtLeast(1)),
    )
}

/** 与 [MobileKey] 一一对应的系统级动作；执行侧负责翻译成 `performGlobalAction`。 */
object GlobalActions {
    /** 这些键可以用无障碍全局动作完成，不需要坐标。 */
    val GLOBAL: Set<MobileKey> = setOf(
        MobileKey.BACK,
        MobileKey.HOME,
        MobileKey.RECENTS,
        MobileKey.NOTIFICATIONS,
        MobileKey.QUICK_SETTINGS,
        MobileKey.LOCK_SCREEN,
    )
}
