package dev.mikhailtail.handyagent.core.tool

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.mobile.FakeMobile
import dev.mikhailtail.handyagent.core.mobile.MobileResult
import dev.mikhailtail.handyagent.core.mobile.MobileSessionState
import dev.mikhailtail.handyagent.core.mobile.RectI
import dev.mikhailtail.handyagent.core.mobile.UnavailableReason
import dev.mikhailtail.handyagent.core.mobile.UiNode
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import dev.mikhailtail.handyagent.core.sandbox.ProcessShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobileToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx(
        fake: FakeMobile,
        state: MobileSessionState = MobileSessionState(),
    ): ToolContext = ToolContext(
        jail = PathJail(tmp.newFolder()),
        shell = ProcessShell(tmp.newFolder(), timeoutMs = 5_000),
        mobile = fake.bridge(),
        mobileState = state,
    )

    private fun args(vararg pairs: Pair<String, Json>) = Json.obj(*pairs)

    // ------------------------------------------------------------------ 感知

    @Test
    fun `ui tree records the snapshot so later taps can resolve indices`() = runTest {
        val fake = FakeMobile(tree = FakeMobile.treeOf("d1", FakeMobile.button("发送")))
        val state = MobileSessionState()

        val out = MobileUiTreeTool.execute(args(), ctx(fake, state))

        assertFalse(out.isError)
        assertTrue(out.content, out.content.contains("dump=d1"))
        assertEquals("d1", state.lastTree?.dumpId)
    }

    @Test
    fun `observe returns the screenshot together with the tree`() = runTest {
        val fake = FakeMobile(tree = FakeMobile.treeOf("d1", FakeMobile.button("发送")))

        val out = MobileUiTreeTool.execute(args(), ctx(fake))

        assertFalse(out.isError)
        assertTrue("必须带控件清单：${out.content}", out.content.contains("dump=d1"))
        assertEquals("必须同时带截图 —— 只看树会漏掉图标/图片/无标签区域", 1, out.images.size)
    }

    @Test
    fun `observe still returns the tree when the screenshot is refused`() = runTest {
        val fake = FakeMobile(
            tree = FakeMobile.treeOf("d1", FakeMobile.button("发送")),
            captureFails = MobileResult.Unavailable(UnavailableReason.SECURE_WINDOW, "受保护窗口"),
        )
        val state = MobileSessionState()

        val out = MobileUiTreeTool.execute(args(), ctx(fake, state))

        assertFalse("拿不到截图不该让整个观察失败", out.isError)
        assertTrue(out.content, out.content.contains("dump=d1"))
        assertTrue(out.images.isEmpty())
        assertTrue("受保护界面要触发硬停", state.hardStopped)
    }

    @Test
    fun `ui tree without a service explains what to do`() = runTest {
        val fake = FakeMobile(tree = null)

        val out = MobileUiTreeTool.execute(args(), ctx(fake))

        assertTrue(out.isError)
        assertTrue(out.content, out.content.contains("无障碍"))
    }

    @Test
    fun `screenshot returns the image alongside a summary`() = runTest {
        val out = MobileScreenshotTool.execute(args(), ctx(FakeMobile()))

        assertFalse(out.isError)
        assertEquals(1, out.images.size)
        assertEquals("image/jpeg", out.images.single().mediaType)
    }

    @Test
    fun `a secure window screenshot hard stops automation`() = runTest {
        val fake = FakeMobile(
            captureFails = MobileResult.Unavailable(
                UnavailableReason.SECURE_WINDOW,
                "受保护窗口",
            ),
        )
        val state = MobileSessionState()

        val out = MobileScreenshotTool.execute(args(), ctx(fake, state))

        assertTrue(out.isError)
        assertTrue("必须置为硬停", state.hardStopped)
    }

    @Test
    fun `notifications explain the missing permission instead of returning empty`() = runTest {
        val fake = FakeMobile(notificationAccess = false)

        val out = MobileNotificationsTool.execute(args(), ctx(fake))

        assertTrue(out.isError)
        assertTrue(out.content, out.content.contains("通知"))
    }

    // ------------------------------------------------------------------ 操作

    @Test
    fun `tap by index hits the node centre`() = runTest {
        val fake = FakeMobile(
            tree = FakeMobile.treeOf("d1", FakeMobile.button("发送", RectI(900, 2100, 1020, 2200))),
        )
        val state = MobileSessionState()
        val ctx = ctx(fake, state)
        MobileUiTreeTool.execute(args(), ctx)

        val out = MobileTapTool.execute(
            args("index" to Json.of(1), "dump_id" to Json.of("d1")),
            ctx,
        )

        assertFalse(out.isError)
        assertEquals(listOf(960 to 2150), fake.taps)
        assertTrue(out.content, out.content.contains("发送"))
    }

    @Test
    fun `tap with a stale dump id performs no gesture at all`() = runTest {
        val fake = FakeMobile(tree = FakeMobile.treeOf("d2", FakeMobile.button("发送")))
        val state = MobileSessionState()
        val ctx = ctx(fake, state)
        MobileUiTreeTool.execute(args(), ctx)

        val out = MobileTapTool.execute(
            args("index" to Json.of(1), "dump_id" to Json.of("d1")),
            ctx,
        )

        assertTrue(out.isError)
        assertTrue("过期序号绝不能产生真实点击", fake.taps.isEmpty())
    }

    @Test
    fun `tap requires either an index or coordinates`() = runTest {
        val fake = FakeMobile(tree = FakeMobile.treeOf("d1", FakeMobile.button("发送")))

        val out = MobileTapTool.execute(args(), ctx(fake))

        assertTrue(out.isError)
        assertTrue(fake.taps.isEmpty())
    }

    @Test
    fun `typing into a password field is refused before touching the device`() = runTest {
        val fake = FakeMobile(
            tree = FakeMobile.treeOf(
                "d1",
                UiNode(
                    className = "EditText",
                    editable = true,
                    password = true,
                    bounds = RectI(100, 500, 900, 600),
                ),
            ),
        )
        val state = MobileSessionState()
        val ctx = ctx(fake, state)
        MobileUiTreeTool.execute(args(), ctx)

        val out = MobileTypeTool.execute(
            args("text" to Json.of("secret"), "index" to Json.of(1), "dump_id" to Json.of("d1")),
            ctx,
        )

        assertTrue(out.isError)
        assertTrue("密码框被拒后不该有任何输入", fake.typed.isEmpty())
    }

    @Test
    fun `typing focuses the target then writes the text`() = runTest {
        val fake = FakeMobile(
            tree = FakeMobile.treeOf(
                "d1",
                UiNode(
                    className = "EditText",
                    editable = true,
                    bounds = RectI(100, 500, 900, 600),
                ),
            ),
        )
        val state = MobileSessionState()
        val ctx = ctx(fake, state)
        MobileUiTreeTool.execute(args(), ctx)

        val out = MobileTypeTool.execute(
            args("text" to Json.of("你好"), "index" to Json.of(1), "dump_id" to Json.of("d1")),
            ctx,
        )

        assertFalse(out.isError)
        assertEquals(listOf("你好"), fake.typed)
        assertEquals("应先聚焦输入框", 1, fake.taps.size)
    }

    @Test
    fun `swipe defaults to the whole screen when no index is given`() = runTest {
        val fake = FakeMobile(tree = FakeMobile.treeOf("d1", FakeMobile.button("X")))
        val state = MobileSessionState()
        val ctx = ctx(fake, state)
        MobileUiTreeTool.execute(args(), ctx)

        val out = MobileSwipeTool.execute(args("direction" to Json.of("up")), ctx)

        assertFalse(out.isError)
        assertEquals(1, fake.swipes.size)
        assertTrue("手指应向上移动", fake.swipes.single().first().second > fake.swipes.single().last().second)
    }

    @Test
    fun `swipe rejects an unknown direction`() = runTest {
        val out = MobileSwipeTool.execute(args("direction" to Json.of("sideways")), ctx(FakeMobile()))

        assertTrue(out.isError)
    }

    @Test
    fun `key maps names to system actions`() = runTest {
        val fake = FakeMobile()

        val out = MobileKeyTool.execute(args("key" to Json.of("back")), ctx(fake))

        assertFalse(out.isError)
        assertEquals(1, fake.globalKeys.size)
    }

    @Test
    fun `launch validates the package name`() = runTest {
        val fake = FakeMobile()

        assertTrue(MobileLaunchTool.execute(args("package" to Json.of("bad name")), ctx(fake)).isError)
        assertTrue(fake.launched.isEmpty())

        val ok = MobileLaunchTool.execute(args("package" to Json.of("com.tencent.mm")), ctx(fake))
        assertFalse(ok.isError)
        assertEquals(listOf("com.tencent.mm"), fake.launched)
    }

    // ------------------------------------------------------------------ 硬停

    @Test
    fun `after a hard stop every mutating tool refuses without touching the device`() = runTest {
        val fake = FakeMobile(tree = FakeMobile.treeOf("d1", FakeMobile.button("转账")))
        val state = MobileSessionState()
        val ctx = ctx(fake, state)
        MobileUiTreeTool.execute(args(), ctx)

        // 模拟遇到 FLAG_SECURE
        state.markSecureWindow()

        val tap = MobileTapTool.execute(args("index" to Json.of(1), "dump_id" to Json.of("d1")), ctx)
        val swipe = MobileSwipeTool.execute(args("direction" to Json.of("up")), ctx)
        val type = MobileTypeTool.execute(args("text" to Json.of("x")), ctx)
        val launch = MobileLaunchTool.execute(args("package" to Json.of("com.x.y")), ctx)

        assertTrue(tap.isError)
        assertTrue(swipe.isError)
        assertTrue(type.isError)
        assertTrue(launch.isError)
        assertTrue("硬停后不得有任何设备动作", fake.taps.isEmpty())
        assertTrue(fake.swipes.isEmpty())
        assertTrue(fake.typed.isEmpty())
        assertTrue(fake.launched.isEmpty())
    }

    // ------------------------------------------------------------------ 审批

    @Test
    fun `perception tools are read only but actuators are not`() {
        assertTrue(MobileUiTreeTool.readOnly)
        assertTrue(MobileScreenshotTool.readOnly)
        assertTrue(MobileStateTool.readOnly)
        assertTrue(MobileNotificationsTool.readOnly)

        assertFalse("点击必须走审批", MobileTapTool.readOnly)
        assertFalse(MobileSwipeTool.readOnly)
        assertFalse(MobileTypeTool.readOnly)
        assertFalse(MobileKeyTool.readOnly)
        assertFalse(MobileLaunchTool.readOnly)
    }

    @Test
    fun `every mobile tool declares a valid input schema`() {
        for (tool in MOBILE_TOOLS) {
            assertEquals("object", tool.inputSchema.str("type"))
            assertTrue(tool.name.startsWith("mobile_"))
        }
        assertEquals(MOBILE_TOOLS.size, MOBILE_TOOLS.map { it.name }.distinct().size)
    }
}
