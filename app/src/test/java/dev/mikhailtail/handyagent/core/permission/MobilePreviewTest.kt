package dev.mikhailtail.handyagent.core.permission

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.mobile.FakeMobile
import dev.mikhailtail.handyagent.core.mobile.MobilePreviewInfo
import dev.mikhailtail.handyagent.core.mobile.MobilePreviewSource
import dev.mikhailtail.handyagent.core.mobile.RectI
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * mobile 工具的审批预览。
 *
 * 这一层的价值全在"用户看不看得懂"：如果把 `{"index":12,"dump_id":"d7"}` 直接摆给用户，
 * 他唯一能做的判断就是"这串东西看起来挺复杂，点同意吧" —— 审批形同虚设。
 */
class MobilePreviewTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tree = FakeMobile.treeOf(
        "d1",
        FakeMobile.button("发送", RectI(900, 2100, 1020, 2200)),
    )

    private fun jail() = PathJail(tmp.newFolder())

    private fun args(vararg p: Pair<String, Json>) = Json.obj(*p)

    private fun previewer(info: MobilePreviewInfo? = MobilePreviewInfo(tree, "com.tencent.mm")) =
        ToolPreviewer(mobilePreview = MobilePreviewSource { info })

    private fun preview(tool: String, input: Json) =
        previewer().preview(tool, input, spec = null, jail = jail())

    // ------------------------------------------------------------------ tap

    @Test
    fun `tap preview names the element instead of dumping json`() {
        val r = preview("mobile_tap", args("index" to Json.of(1), "dump_id" to Json.of("d1")))

        assertTrue(r.summary, r.summary.contains("发送"))
        assertTrue(r.summary, r.summary.contains("BUTTON"))
        assertTrue("应带上当前应用，帮用户确认在哪个 App 里操作", r.summary.contains("com.tencent.mm"))
        assertFalse("绝不能把原始 JSON 丢给用户", r.summary.contains("dump_id"))
    }

    @Test
    fun `tap preview falls back to coordinates`() {
        val r = preview("mobile_tap", args("x" to Json.of(100), "y" to Json.of(200)))

        assertTrue(r.summary, r.summary.contains("(100, 200)"))
    }

    @Test
    fun `tap preview admits when the target cannot be resolved`() {
        val r = preview("mobile_tap", args("index" to Json.of(99)))

        assertTrue(r.summary, r.summary.contains("失效"))
    }

    // ------------------------------------------------------------------ type

    @Test
    fun `type preview shows both the target and the text`() {
        val r = preview("mobile_type", args("index" to Json.of(1), "text" to Json.of("你好")))

        assertTrue(r.summary, r.summary.contains("输入框"))
        assertTrue(r.summary, r.summary.contains("你好"))
    }

    @Test
    fun `type preview truncates very long text`() {
        val r = preview("mobile_type", args("text" to Json.of("x".repeat(200))))

        assertTrue("审批卡不该被 200 字撑爆：len=${r.summary.length}", r.summary.length < 120)
    }

    @Test
    fun `type preview mentions submission when requested`() {
        val r = preview("mobile_type", args("text" to Json.of("hi"), "submit" to Json.of(true)))

        assertTrue(r.summary, r.summary.contains("提交"))
    }

    // ------------------------------------------------------------------ 其它

    @Test
    fun `swipe preview spells out the direction`() {
        val r = preview("mobile_swipe", args("direction" to Json.of("up")))

        assertTrue(r.summary, r.summary.contains("向上滑"))
        assertTrue(r.summary, r.summary.contains("整屏"))
    }

    @Test
    fun `key preview uses a human readable label`() {
        assertTrue(preview("mobile_key", args("key" to Json.of("back"))).summary.contains("返回"))
        assertTrue(preview("mobile_key", args("key" to Json.of("notifications"))).summary.contains("通知栏"))
    }

    @Test
    fun `launch preview names the package`() {
        val r = preview("mobile_launch", args("package" to Json.of("com.tencent.mm")))
        assertTrue(r.summary, r.summary.contains("com.tencent.mm"))
    }

    // ------------------------------------------------------------------ 降级

    @Test
    fun `preview still works without a snapshot source`() {
        val r = ToolPreviewer().preview("mobile_tap", args("index" to Json.of(1)), null, jail())

        assertTrue(r.summary.isNotBlank())
    }

    @Test
    fun `a null snapshot does not crash the preview`() {
        val r = previewer(info = null).preview("mobile_tap", args("index" to Json.of(1)), null, jail())

        assertTrue(r.summary, r.summary.contains("失效"))
    }

    // ------------------------------------------------------------------ 附图

    @Test
    fun `a recent screenshot is attached for visual confirmation`() {
        val shot = ImageRef("img_1.jpg", "image/jpeg", 1080, 2400, 120_000)
        val r = previewer(MobilePreviewInfo(tree, "com.tencent.mm", shot))
            .preview("mobile_tap", args("index" to Json.of(1)), null, jail())

        assertEquals(shot, r.previewImage)
        assertTrue(r.hasImage)
    }

    @Test
    fun `no screenshot means no image on the card`() {
        val r = preview("mobile_tap", args("index" to Json.of(1)))

        assertEquals(null, r.previewImage)
        assertFalse(r.hasImage)
    }

    // ------------------------------------------------------------------ 不越界

    @Test
    fun `mobile previews do not disturb the file tool previews`() {
        val p = previewer()
        val r = p.preview("bash", args("command" to Json.of("ls -la")), null, jail())

        assertTrue(r.summary, r.summary.contains("ls -la"))
        assertEquals(null, r.previewImage)
    }
}
