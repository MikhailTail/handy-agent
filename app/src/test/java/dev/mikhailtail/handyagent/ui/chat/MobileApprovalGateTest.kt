package dev.mikhailtail.handyagent.ui.chat

import dev.mikhailtail.handyagent.core.context.ContextBudget
import dev.mikhailtail.handyagent.core.context.ContextManager
import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.loop.AgentLoop
import dev.mikhailtail.handyagent.core.loop.ScriptedProvider
import dev.mikhailtail.handyagent.core.mobile.FakeMobile
import dev.mikhailtail.handyagent.core.permission.PermissionBroker
import dev.mikhailtail.handyagent.core.permission.PermissionRelay
import dev.mikhailtail.handyagent.core.permission.PermissionVerdict
import dev.mikhailtail.handyagent.core.platform.AgentRuntime
import dev.mikhailtail.handyagent.core.platform.FakeSecretCipher
import dev.mikhailtail.handyagent.core.platform.InMemoryKeyValueStore
import dev.mikhailtail.handyagent.core.platform.WorkspaceLayout
import dev.mikhailtail.handyagent.core.provider.FakeHttpEngine
import dev.mikhailtail.handyagent.ui.timeline.TimelineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * **mobile use 最重要的一条不变量**：
 * broker 批准之前，任何手势都不得真的发到设备上。
 *
 * 这里用**真实的 AgentRuntime + 真实的工具表 + 真实的审批 broker**，只把 LLM 换成脚本化的，
 * 设备换成可记录调用的 [FakeMobile]。于是"审批卡出现 → 用户点拒绝 → 设备一点没动"
 * 这种最容易在实现里漏掉的地方，被钉死在 JVM 上。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MobileApprovalGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var layout: WorkspaceLayout
    private lateinit var relay: PermissionRelay

    @Before
    fun setUp() {
        layout = WorkspaceLayout(tmp.newFolder("app"))
        relay = PermissionRelay()
    }

    private fun runtime(fake: FakeMobile): AgentRuntime {
        val rt = AgentRuntime(
            layout = layout,
            kv = InMemoryKeyValueStore(),
            cipher = FakeSecretCipher(),
            http = FakeHttpEngine(),
            approver = relay,
            shellTimeoutMs = 5_000,
            mobile = fake.bridge(),
        )
        rt.credentials.put("anthropic", "sk-test")
        return rt
    }

    /**
     * 复刻 createLoop 的接线，但用脚本化 provider。
     * **工具表与 ToolContext 直接用 runtime 自己的**，确保测的是真实装配而不是测试自造的替身。
     */
    private fun loopFor(provider: ScriptedProvider, runtime: AgentRuntime): AgentLoop {
        val settings = runtime.settingsStore.load()
        val registry = runtime.registry
        val broker = PermissionBroker(relay).apply {
            applyDefaults(registry.specs())
            settings.permissionModes.forEach { (tool, mode) -> setMode(tool, mode) }
        }
        return AgentLoop(
            provider = provider,
            registry = registry,
            toolContext = runtime.toolContext,
            broker = broker,
            contextManager = ContextManager(ContextBudget(maxTokens = 100_000, threshold = 0.8)),
            systemPrompt = "sys",
            model = settings.resolvedModel(provider.config.defaultModel),
            effort = settings.effort,
            maxIterations = settings.maxIterations,
        )
    }

    private fun controller(
        scope: CoroutineScope,
        runtime: AgentRuntime,
        provider: ScriptedProvider,
    ): ChatController = ChatController(
        runtimeProvider = { runtime },
        approvals = relay,
        scope = scope,
        loopFactory = { rt -> loopFor(provider, rt) },
    )

    private suspend fun ChatController.await(predicate: (ChatUiState) -> Boolean): ChatUiState {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(state.value)) return state.value
            yield()
        }
        fail("timed out; items=${state.value.items}")
        error("unreachable")
    }

    /**
     * 脚本：先取界面（只读，自动放行），再点击目标（需要审批）。
     *
     * 两轮必须用不同的 idPrefix —— 否则两轮的 tool id 都是 `call_0`，
     * 审批拒绝时会把第一轮那张卡片错标成 DENIED（真实 API 的 id 是唯一的）。
     */
    private fun tapScript() = ScriptedProvider(
        ScriptedProvider.tools("mobile_ui_tree" to Json.obj(), idPrefix = "tree_"),
        ScriptedProvider.tools(
            "mobile_tap" to Json.obj("index" to Json.of(1), "dump_id" to Json.of("d1")),
            idPrefix = "tap_",
        ),
        ScriptedProvider.text("完成"),
    )

    private fun fakeWithButton() =
        FakeMobile(tree = FakeMobile.treeOf("d1", FakeMobile.button("发送", dev.mikhailtail.handyagent.core.mobile.RectI(900, 2100, 1020, 2200))))

    // ------------------------------------------------------------------ 拒绝

    @Test
    fun `a denied tap never reaches the device`() = runTest {
        val fake = fakeWithButton()
        val c = controller(backgroundScope, runtime(fake), tapScript())
        c.start()

        c.send("点一下发送")
        val pending = c.await { it.approval != null }

        assertEquals("mobile_tap", pending.approval!!.request.toolName)
        assertTrue("审批卡出现前就不能有动作", fake.taps.isEmpty())

        c.resolveApproval(PermissionVerdict.denyOnce())
        c.await { !it.running }

        assertTrue("用户拒绝后设备上不得有任何点击", fake.taps.isEmpty())
    }

    @Test
    fun `a denied tap is reported back to the model as a tool error`() = runTest {
        val fake = fakeWithButton()
        val c = controller(backgroundScope, runtime(fake), tapScript())
        c.start()
        c.send("点一下发送")
        c.await { it.approval != null }

        c.resolveApproval(PermissionVerdict.denyOnce())
        val s = c.await { !it.running }

        val cards = s.items.filterIsInstance<TimelineItem.ToolCall>()
        val card = cards.firstOrNull { it.name == "mobile_tap" }
        assertTrue(
            "应有一条被拒绝的 mobile_tap 卡片，实际：${cards.map { it.name to it.status }}",
            card != null,
        )
        assertEquals(TimelineItem.ToolCall.Status.DENIED, card!!.status)
    }

    // ------------------------------------------------------------------ 批准

    @Test
    fun `an approved tap executes exactly once at the right point`() = runTest {
        val fake = fakeWithButton()
        val c = controller(backgroundScope, runtime(fake), tapScript())
        c.start()

        c.send("点一下发送")
        c.await { it.approval != null }
        c.resolveApproval(PermissionVerdict.allowOnce())

        c.await { !it.running }

        assertEquals("只应点击一次", 1, fake.taps.size)
        assertEquals(960 to 2150, fake.taps.single())
    }

    @Test
    fun `perception tools do not raise an approval card`() = runTest {
        val fake = fakeWithButton()
        val provider = ScriptedProvider(
            ScriptedProvider.tools("mobile_ui_tree" to Json.obj()),
            ScriptedProvider.text("看完了"),
        )
        val c = controller(backgroundScope, runtime(fake), provider)
        c.start()

        c.send("看看现在是什么界面")
        val s = c.await { !it.running }

        assertEquals("只读工具不该弹审批", null, s.approval)
        assertEquals(1, fake.dumpCount)
    }

    // ------------------------------------------------------------------ 硬停

    @Test
    fun `after a secure window the tap tool refuses even when approved`() = runTest {
        // 截图被 FLAG_SECURE 拒绝 → 本轮停手
        val fake = FakeMobile(
            tree = FakeMobile.treeOf("d1", FakeMobile.button("确认转账")),
            captureFails = dev.mikhailtail.handyagent.core.mobile.MobileResult.Unavailable(
                dev.mikhailtail.handyagent.core.mobile.UnavailableReason.SECURE_WINDOW,
                "受保护窗口",
            ),
        )
        val provider = ScriptedProvider(
            ScriptedProvider.tools("mobile_ui_tree" to Json.obj()),
            ScriptedProvider.tools("mobile_screenshot" to Json.obj()),
            ScriptedProvider.tools(
                "mobile_tap" to Json.obj("index" to Json.of(1), "dump_id" to Json.of("d1")),
            ),
            ScriptedProvider.text("停手"),
        )
        val c = controller(backgroundScope, runtime(fake), provider)
        c.start()

        c.send("操作一下")
        // 中途可能为 mobile_tap 弹审批；一律放行，验证即使批准也会被硬停拦住
        repeat(6) {
            val s = c.await { it.approval != null || !it.running }
            s.approval?.let { c.resolveApproval(PermissionVerdict.allowOnce()) }
        }
        c.await { !it.running }

        assertTrue("敏感界面下即使批准也不能点击", fake.taps.isEmpty())
    }

    // ------------------------------------------------------------------ 未接入

    @Test
    fun `mobile tools stay hidden when the platform has no device access`() = runTest {
        val rt = AgentRuntime(
            layout = layout,
            kv = InMemoryKeyValueStore(),
            cipher = FakeSecretCipher(),
            http = FakeHttpEngine(),
            approver = relay,
            shellTimeoutMs = 5_000,
            mobile = null,
        )

        assertFalse("没有设备能力时不该注册 mobile 工具", rt.registry.names().any { it.startsWith("mobile_") })
        assertTrue(rt.registry.names().contains("read"))
    }

    @Test
    fun `mobile tools are registered when the platform provides them`() = runTest {
        val rt = runtime(fakeWithButton())

        val names = rt.registry.names()
        assertTrue(names.contains("mobile_tap"))
        assertTrue(names.contains("mobile_ui_tree"))
        assertNotNull(rt.toolContext.mobile)
    }
}
