package dev.mikhailtail.handyagent.tools

import dev.mikhailtail.handyagent.kernel.api.MobileApp
import dev.mikhailtail.handyagent.kernel.api.MobileCapability
import dev.mikhailtail.handyagent.kernel.api.MobileKey
import dev.mikhailtail.handyagent.kernel.api.MobileNotification
import dev.mikhailtail.handyagent.kernel.api.MobileResult
import dev.mikhailtail.handyagent.kernel.api.MobileState
import dev.mikhailtail.handyagent.kernel.api.ScrollDirection
import dev.mikhailtail.handyagent.kernel.api.ToolContext
import dev.mikhailtail.handyagent.kernel.api.FileHost
import dev.mikhailtail.handyagent.kernel.api.ShellHost
import dev.mikhailtail.handyagent.kernel.api.ShellResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 手机工具的护栏测试。
 *
 * 用假的能力实现驱动，所以能在电脑上穷举 —— Android 无障碍本体没法在这里跑，
 * 但"什么情况下给模型什么回答"这层逻辑可以完整验证。
 */
class MobileToolsTest {

    /** 可编排的假手机。 */
    private class FakeMobile(
        var ready: Boolean = true,
        var stateResult: () -> MobileState = { defaultState() },
        var clickResult: MobileResult<Unit> = MobileResult.Ok(Unit),
        var setValueResult: MobileResult<Unit> = MobileResult.Ok(Unit),
    ) : MobileCapability {
        val clicked = mutableListOf<Pair<Int, String>>()
        val typed = mutableListOf<Triple<Int, String, String>>()

        override fun isReady() = ready
        override fun unavailableReason() = if (ready) null else "无障碍服务未开启"

        override suspend fun listApps() = listOf(MobileApp("com.tencent.mm", "微信"))
        override suspend fun getState() = stateResult()
        override suspend fun click(index: Int, dumpId: String): MobileResult<Unit> {
            clicked += index to dumpId
            return clickResult
        }
        override suspend fun tap(x: Int, y: Int) = MobileResult.Ok(Unit)
        override suspend fun setValue(index: Int, dumpId: String, text: String): MobileResult<Unit> {
            typed += Triple(index, dumpId, text)
            return setValueResult
        }
        override suspend fun scroll(index: Int?, dumpId: String, direction: ScrollDirection, pages: Double) =
            MobileResult.Ok(Unit)
        override suspend fun pressKey(key: MobileKey) = MobileResult.Ok(Unit)
        override suspend fun launchApp(packageName: String) = MobileResult.Ok(Unit)
        override suspend fun readNotifications() =
            listOf(MobileNotification("com.tencent.mm", "张三", "在吗", 0L))

        companion object {
            fun defaultState() = MobileState(
                dumpId = "d1",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                treeText = "[0] EditText \"搜索\"\n[1] Button \"发送\"",
                screenshotBase64 = "BASE64JPEG",
                screenWidth = 1080,
                screenHeight = 2400,
            )
        }
    }

    private val ctx = object : ToolContext {
        override val files = object : FileHost {
            override fun readText(path: String) = ""
            override fun writeText(path: String, text: String) {}
            override fun exists(path: String) = false
            override fun isDirectory(path: String) = false
            override fun list(path: String) = emptyList<String>()
            override fun delete(path: String) {}
            override fun glob(pattern: String, root: String) = emptyList<String>()
        }
        override val shell = object : ShellHost {
            override suspend fun run(command: String, workDir: String, timeoutMs: Long) =
                ShellResult("", "", 0)
        }
        override val workDir = "/work"
    }

    private fun toolOf(mobile: MobileCapability, name: String) =
        MobileToolSet(mobile).tools().first { it.name == name }

    // ─── 只读 / 写 的审批边界 ────────────────────────────────────────────────

    /**
     * 读操作必须免审批。
     *
     * 这条是体验底线：看个界面也要点确认的话，用户会在第三次就关掉无障碍权限，
     * 那样整个 mobile use 就废了。而写操作必须审批 —— 误触在手机上可能是转账。
     */
    @Test
    fun `read tools skip approval and write tools require it`() {
        val tools = MobileToolSet(FakeMobile()).tools().associateBy { it.name }

        for (name in listOf("mobile_list_apps", "mobile_get_state", "mobile_notifications")) {
            assertTrue(tools.getValue(name).isReadOnly, "$name 应免审批")
        }
        for (name in listOf(
            "mobile_click", "mobile_tap", "mobile_set_value",
            "mobile_scroll", "mobile_press_key", "mobile_launch_app",
        )) {
            assertTrue(!tools.getValue(name).isReadOnly, "$name 必须走审批")
            assertTrue(tools.getValue(name).needsApproval(), "$name 必须弹卡")
        }
    }

    // ─── 观察 ────────────────────────────────────────────────────────────────

    /** 树与截图必须一起给 —— 只给树就看不见图标与无标签区域，只给图就只能猜坐标。 */
    @Test
    fun `get state returns both the tree and the screenshot`() = runTest {
        val mobile = FakeMobile()

        val result = toolOf(mobile, "mobile_get_state").execute(JsonObject(emptyMap()), ctx)

        val text = (result.content as JsonPrimitive).content
        assertTrue(text.contains("发送"), "应含控件清单：$text")
        assertTrue(text.contains("dump_id：d1"), "应告知快照 id：$text")
        assertEquals(listOf("BASE64JPEG"), result.images, "截图必须随结果返回")
    }

    /** 受保护界面要说清"截图被拒绝"，而不是悄悄给个没有图的结果。 */
    @Test
    fun `secure screens explain why the screenshot is missing`() = runTest {
        val mobile = FakeMobile(
            stateResult = { FakeMobile.defaultState().copy(screenshotBase64 = null, secure = true) },
        )

        val result = toolOf(mobile, "mobile_get_state").execute(JsonObject(emptyMap()), ctx)

        val text = (result.content as JsonPrimitive).content
        assertTrue(text.contains("受保护界面"), text)
        assertTrue(result.images.isEmpty())
    }

    // ─── 索引过期 —— 宁可失败也不猜 ─────────────────────────────────────────

    /**
     * 拿旧 dump 的 index 去点必须失败。
     *
     * 这是最重要的一条护栏：界面早变了，那个 index 此刻可能指向完全不同的东西
     * （比如原本的"取消"位置现在是"删除"）。**误触在手机上代价很高**，
     * 宁可让模型重新观察一次。
     */
    @Test
    fun `stale index is rejected with an actionable message`() = runTest {
        val mobile = FakeMobile(
            clickResult = MobileResult.Stale("dump_id 不匹配，界面已变化，请重新调用 mobile_get_state"),
        )

        val result = toolOf(mobile, "mobile_click").execute(
            buildJsonObject { put("index", JsonPrimitive(1)); put("dump_id", JsonPrimitive("old")) },
            ctx,
        )

        assertTrue(result.isError, "过期索引必须报错")
        val text = (result.content as JsonPrimitive).content
        assertTrue(text.contains("重新"), "要告诉模型下一步该做什么：$text")
    }

    /** 缺 dump_id 时不该"猜一个"，而该要求模型先观察。 */
    @Test
    fun `click without dump id asks the model to observe first`() = runTest {
        val result = toolOf(FakeMobile(), "mobile_click").execute(
            buildJsonObject { put("index", JsonPrimitive(1)) },
            ctx,
        )

        assertTrue(result.isError)
        assertTrue((result.content as JsonPrimitive).content.contains("mobile_get_state"))
    }

    // ─── 能力未就绪 ──────────────────────────────────────────────────────────

    /**
     * 无障碍没开时要给出可读原因与出路，**不能让模型反复重试**。
     *
     * 模型拿到"失败"会换个参数再试，反复烧 token；告诉它"需要用户去开权限，
     * 不要重试"，它才会停下来交还给用户。
     */
    @Test
    fun `unavailable capability tells the model to stop retrying`() = runTest {
        val mobile = FakeMobile(ready = false)

        val result = toolOf(mobile, "mobile_get_state").execute(JsonObject(emptyMap()), ctx)

        assertTrue(result.isError)
        val text = (result.content as JsonPrimitive).content
        assertTrue(text.contains("不可用"), text)
        assertTrue(text.contains("无障碍"), text)
        assertTrue(text.contains("不要反复重试"), text)
    }

    /** 未就绪时不该真的去碰设备。 */
    @Test
    fun `unavailable capability performs no device action`() = runTest {
        val mobile = FakeMobile(ready = false)

        toolOf(mobile, "mobile_click").execute(
            buildJsonObject { put("index", JsonPrimitive(1)); put("dump_id", JsonPrimitive("d1")) },
            ctx,
        )

        assertTrue(mobile.clicked.isEmpty(), "能力不可用时不该发出点击")
    }

    // ─── 参数校验 ────────────────────────────────────────────────────────────

    @Test
    fun `press key rejects unknown keys`() = runTest {
        val result = toolOf(FakeMobile(), "mobile_press_key").execute(
            buildJsonObject { put("key", JsonPrimitive("F13")) },
            ctx,
        )

        assertTrue(result.isError)
        assertTrue((result.content as JsonPrimitive).content.contains("BACK"), "应列出可用键")
    }

    @Test
    fun `scroll requires dump id when an index is given`() = runTest {
        val result = toolOf(FakeMobile(), "mobile_scroll").execute(
            buildJsonObject {
                put("direction", JsonPrimitive("down"))
                put("index", JsonPrimitive(3))
            },
            ctx,
        )

        assertTrue(result.isError)
        assertTrue((result.content as JsonPrimitive).content.contains("dump_id"))
    }

    @Test
    fun `set value writes through to the device`() = runTest {
        val mobile = FakeMobile()

        toolOf(mobile, "mobile_set_value").execute(
            buildJsonObject {
                put("index", JsonPrimitive(0))
                put("dump_id", JsonPrimitive("d1"))
                put("text", JsonPrimitive("你好"))
            },
            ctx,
        )

        assertEquals(listOf(Triple(0, "d1", "你好")), mobile.typed)
    }

    /** 工具名要与 cc-haha 的语义接口一一对应，不能自创。 */
    @Test
    fun `tool names cover the semantic capability surface`() {
        val names = MobileToolSet(FakeMobile()).tools().map { it.name }.toSet()

        assertEquals(
            setOf(
                "mobile_list_apps", "mobile_get_state", "mobile_click", "mobile_tap",
                "mobile_set_value", "mobile_scroll", "mobile_press_key",
                "mobile_launch_app", "mobile_notifications",
            ),
            names,
        )
    }
}
