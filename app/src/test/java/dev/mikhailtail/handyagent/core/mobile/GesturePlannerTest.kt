package dev.mikhailtail.handyagent.core.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势坐标计算。
 *
 * 最要命的一条：**绝不乘 density**。bounds 与 dispatchGesture 同为屏幕绝对像素，
 * 乘了 density 在高 DPI 真机上会到处点错位置，而在模拟器上还测不出来。
 */
class GesturePlannerTest {

    private val screen = ScreenInfo(1080, 2400, densityDpi = 420)

    @Test
    fun `tap point is the rect centre in raw pixels`() {
        val p = GesturePlanner.tapPoint(RectI(100, 200, 300, 400), screen)

        assertEquals(200, p.x)
        assertEquals(300, p.y)
    }

    @Test
    fun `density is never applied`() {
        val bounds = RectI(100, 200, 300, 400)

        val atMdpi = GesturePlanner.tapPoint(bounds, screen.copy(densityDpi = 160))
        val atXxxhdpi = GesturePlanner.tapPoint(bounds, screen.copy(densityDpi = 640))

        assertEquals("同一 bounds 在任何 DPI 下都必须得到同样的像素坐标", atMdpi, atXxxhdpi)
    }

    @Test
    fun `tap points are clamped inside the screen and off the very edge`() {
        val p = GesturePlanner.tapPoint(RectI(1070, 2390, 1300, 2600), screen)

        assertTrue("不能贴边，系统会丢弃贴边手势", p.x < screen.width - 1)
        assertTrue(p.y < screen.height - 1)
        assertTrue(p.x > 0 && p.y > 0)
    }

    @Test
    fun `swipe up moves the finger upward`() {
        val path = GesturePlanner.swipeIn(RectI(0, 1000, 1080, 2000), ScrollDir.UP, screen)

        assertTrue("手指向上 = 内容向下滚", path.first().y > path.last().y)
    }

    @Test
    fun `swipe down moves the finger downward`() {
        val path = GesturePlanner.swipeIn(RectI(0, 1000, 1080, 2000), ScrollDir.DOWN, screen)

        assertTrue(path.last().y > path.first().y)
    }

    @Test
    fun `swipe path never leaves the screen`() {
        val path = GesturePlanner.swipeIn(RectI(0, 10, 1080, 90), ScrollDir.DOWN, screen)

        assertTrue(path.all { it.y in 0 until screen.height })
        assertTrue(path.all { it.x in 0 until screen.width })
    }

    @Test
    fun `swipe on a small target still travels a usable distance`() {
        // 小控件上滑动如果只走控件自身高度，系统会当成点击
        val path = GesturePlanner.swipeIn(RectI(0, 1000, 100, 1040), ScrollDir.UP, screen)
        val travelled = path.first().y - path.last().y

        assertTrue("滑动距离过短会被判为点击：$travelled", travelled >= screen.height / 4)
    }

    @Test
    fun `an explicit distance overrides the default`() {
        val path = GesturePlanner.swipeIn(RectI(0, 1000, 1080, 2000), ScrollDir.UP, screen, distancePx = 100)

        assertEquals(100, path.first().y - path.last().y)
    }

    @Test
    fun `swipe path includes both endpoints`() {
        val path = GesturePlanner.swipePath(PointI(0, 0), PointI(10, 20), steps = 5)

        assertEquals(5, path.size)
        assertEquals(PointI(0, 0), path.first())
        assertEquals(PointI(10, 20), path.last())
    }

    @Test
    fun `a degenerate screen size does not produce out of range points`() {
        val tiny = ScreenInfo(0, 0)
        val p = GesturePlanner.tapPoint(RectI(0, 0, 10, 10), tiny)

        assertTrue(p.x >= 1 && p.y >= 1)
    }
}
