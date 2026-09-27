package dev.mikhailtail.handyagent.core.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作解析的**拒绝语义**。
 *
 * 误触在手机上可能意味着转账、发消息、删文件，所以这里的准则只有一条：
 * **宁可失败，也不猜**。序号对不上就明确报错让模型重新取树。
 */
class ActionResolverTest {

    private val screen = ScreenInfo(1080, 2400)

    private fun tree(
        dumpId: String = "d1",
        nodes: List<UiNode> = listOf(
            UiNode(
                className = "Button",
                text = "发送",
                bounds = RectI(900, 2100, 1020, 2200),
                clickable = true,
            ),
        ),
    ): FlatTree = UiTreeFlattener.flatten(UiNode("Root", children = nodes), screen, "com.x", dumpId)

    private fun failure(r: ResolvedAction): ResolvedAction.Failure {
        assertTrue("期望失败但得到 $r", r is ResolvedAction.Failure)
        return r as ResolvedAction.Failure
    }

    // ------------------------------------------------------------------ 正常

    @Test
    fun `tap by index resolves to the node centre`() {
        val r = ActionResolver.resolve(MobileAction.TapIndex(1, "d1"), tree())

        val tap = r as ResolvedAction.Tap
        assertEquals(960, tap.point.x)
        assertEquals(2150, tap.point.y)
        assertEquals("发送", tap.target?.label)
    }

    @Test
    fun `swipe without an index uses the whole screen`() {
        val r = ActionResolver.resolve(MobileAction.Swipe(ScrollDir.UP), tree())

        val swipe = r as ResolvedAction.Swipe
        assertTrue(swipe.path.size >= 2)
        assertTrue("手指应向上移动", swipe.path.first().y > swipe.path.last().y)
    }

    @Test
    fun `launch accepts a well formed package name`() {
        val r = ActionResolver.resolve(MobileAction.Launch("com.tencent.mm"), tree())

        assertEquals("com.tencent.mm", (r as ResolvedAction.LaunchApp).packageName)
    }

    @Test
    fun `key presses need no tree`() {
        val r = ActionResolver.resolve(MobileAction.Key(MobileKey.BACK), null)

        assertEquals(MobileKey.BACK, (r as ResolvedAction.KeyPress).key)
    }

    // ------------------------------------------------------------------ 拒绝

    @Test
    fun `a stale dump id is refused rather than guessed`() {
        val f = failure(ActionResolver.resolve(MobileAction.TapIndex(1, "d0"), tree("d1")))

        assertEquals(ResolveError.STALE_TREE, f.reason)
        assertTrue(f.message, f.message.contains("mobile_ui_tree"))
    }

    @Test
    fun `an empty dump id is treated as stale rather than matching anything`() {
        val f = failure(ActionResolver.resolve(MobileAction.TapIndex(1, ""), tree("d1")))

        assertEquals(ResolveError.STALE_TREE, f.reason)
    }

    @Test
    fun `an out of range index is refused`() {
        val f = failure(ActionResolver.resolve(MobileAction.TapIndex(99, "d1"), tree()))

        assertEquals(ResolveError.OUT_OF_RANGE, f.reason)
    }

    @Test
    fun `acting before any snapshot is refused`() {
        val f = failure(ActionResolver.resolve(MobileAction.TapIndex(1, "d1"), null))

        assertEquals(ResolveError.STALE_TREE, f.reason)
    }

    @Test
    fun `coordinates outside the screen are refused`() {
        val f = failure(ActionResolver.resolve(MobileAction.TapXY(5000, 5000), tree()))

        assertEquals(ResolveError.OUT_OF_RANGE, f.reason)
    }

    @Test
    fun `typing into a password field is refused outright`() {
        val t = tree(
            nodes = listOf(
                UiNode(
                    className = "EditText",
                    editable = true,
                    password = true,
                    bounds = RectI(0, 0, 200, 100),
                ),
            ),
        )

        val f = failure(ActionResolver.resolve(MobileAction.Type("hunter2", index = 1, dumpId = "d1"), t))

        assertEquals(ResolveError.INVALID, f.reason)
        assertTrue(f.message, f.message.contains("密码框"))
    }

    @Test
    fun `typing empty text is refused`() {
        val f = failure(ActionResolver.resolve(MobileAction.Type("   ".trim()), tree()))
        assertEquals(ResolveError.INVALID, f.reason)
    }

    @Test
    fun `a malformed package name is refused`() {
        assertTrue(
            ActionResolver.resolve(MobileAction.Launch("not a package"), tree())
                is ResolvedAction.Failure,
        )
        assertTrue(
            ActionResolver.resolve(MobileAction.Launch(""), tree()) is ResolvedAction.Failure,
        )
    }

    @Test
    fun `swiping a stale index is refused instead of swiping the whole screen`() {
        val f = failure(ActionResolver.resolve(MobileAction.Swipe(ScrollDir.DOWN, index = 5, dumpId = "d9"), tree()))

        assertEquals("不能悄悄退化成全屏滑动", ResolveError.STALE_TREE, f.reason)
    }
}
