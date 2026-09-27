package dev.mikhailtail.handyagent.core.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 护栏层：敏感界面识别 + 打转检测。
 *
 * 前者决定"什么时候必须停手转人工"，后者决定"什么时候该提醒模型换策略"。
 */
class MobileGuardTest {

    private fun flat(vararg nodes: UiNode): List<FlatNode> =
        UiTreeFlattener.flatten(
            UiNode("Root", children = nodes.toList()),
            ScreenInfo(1080, 2400),
            "com.x",
            "d1",
        ).nodes

    // -------------------------------------------------------------- 敏感界面

    @Test
    fun `a secure window forces a hard stop`() {
        val v = SensitiveScreenDetector.inspect("com.bank", flat(), secureWindow = true)

        assertTrue(v.sensitive)
        assertTrue("FLAG_SECURE 必须硬停，不是再确认一次", v.hardStop)
        assertEquals(SensitiveReason.SECURE_WINDOW, v.reason)
    }

    @Test
    fun `a password field is flagged but does not hard stop`() {
        val v = SensitiveScreenDetector.inspect(
            null,
            flat(UiNode("EditText", editable = true, password = true, bounds = RectI(0, 0, 100, 50))),
        )

        assertTrue(v.sensitive)
        assertEquals(SensitiveReason.PASSWORD_FIELD, v.reason)
        assertFalse("密码框只要求审批，不必整体停手", v.hardStop)
    }

    @Test
    fun `a denylisted package is flagged`() {
        val v = SensitiveScreenDetector.inspect("com.eg.android.AlipayGphone", flat())

        assertEquals(SensitiveReason.PACKAGE_DENYLIST, v.reason)
        assertTrue(v.sensitive)
    }

    @Test
    fun `payment wording on screen is flagged`() {
        val v = SensitiveScreenDetector.inspect(
            null,
            flat(UiNode("TextView", text = "请输入支付密码", bounds = RectI(0, 0, 300, 60))),
        )

        assertEquals(SensitiveReason.PAYMENT_KEYWORDS, v.reason)
    }

    @Test
    fun `an ordinary chat screen is clean`() {
        val v = SensitiveScreenDetector.inspect(
            "com.tencent.mm",
            flat(UiNode("Button", text = "发送", clickable = true, bounds = RectI(0, 0, 100, 60))),
        )

        assertTrue(v.safe)
        assertEquals(null, v.reason)
    }

    @Test
    fun `secure window outranks everything else`() {
        // 即使是白名单包、界面也没有密码框，只要系统说这个窗口不给看，就必须停
        val v = SensitiveScreenDetector.inspect("com.tencent.mm", flat(), secureWindow = true)

        assertTrue(v.hardStop)
    }

    // -------------------------------------------------------------- 打转检测

    @Test
    fun `repeating the same action on the same screen is reported`() {
        val detector = LoopDetector()
        val step = StepFingerprint("screenA", "actionX")

        assertEquals(LoopVerdict.NONE, detector.observe(step))
        assertEquals(LoopVerdict.NONE, detector.observe(step))
        assertEquals("第三次还没变，说明这个操作根本没生效", LoopVerdict.REPEATING, detector.observe(step))
    }

    @Test
    fun `a screen that never changes is reported as stuck`() {
        val detector = LoopDetector(stuckThreshold = 3)

        detector.observe(StepFingerprint("same", "a1"))
        detector.observe(StepFingerprint("same", "a2"))

        assertEquals(LoopVerdict.STUCK, detector.observe(StepFingerprint("same", "a3")))
    }

    @Test
    fun `progress resets the stuck counter`() {
        val detector = LoopDetector(stuckThreshold = 3)

        detector.observe(StepFingerprint("a", "x"))
        detector.observe(StepFingerprint("a", "y"))
        detector.observe(StepFingerprint("b", "z"))

        assertEquals(LoopVerdict.NONE, detector.observe(StepFingerprint("b", "w")))
    }

    @Test
    fun `reset clears the history`() {
        val detector = LoopDetector(stuckThreshold = 2)
        detector.observe(StepFingerprint("a", "x"))
        detector.observe(StepFingerprint("a", "y"))

        detector.reset()

        assertEquals(LoopVerdict.NONE, detector.observe(StepFingerprint("a", "z")))
    }

    @Test
    fun `screen hash ignores small coordinate jitter`() {
        fun treeAt(offset: Int) = UiTreeFlattener.flatten(
            UiNode(
                "Root",
                children = listOf(
                    UiNode("Button", text = "发送", clickable = true, bounds = RectI(0, 1000 + offset, 100, 1060 + offset)),
                ),
            ),
            ScreenInfo(1080, 2400),
            "com.x",
            "d1",
        )

        // 几个像素的抖动不该被当成"界面变了"（否则永远判不出卡死）
        assertEquals(ScreenHasher.hash(treeAt(0)), ScreenHasher.hash(treeAt(3)))
    }

    @Test
    fun `screen hash changes when the content really changes`() {
        fun treeWith(label: String) = UiTreeFlattener.flatten(
            UiNode(
                "Root",
                children = listOf(
                    UiNode("Button", text = label, clickable = true, bounds = RectI(0, 1000, 100, 1060)),
                ),
            ),
            ScreenInfo(1080, 2400),
            "com.x",
            "d1",
        )

        assertTrue(ScreenHasher.hash(treeWith("发送")) != ScreenHasher.hash(treeWith("取消")))
    }
}
