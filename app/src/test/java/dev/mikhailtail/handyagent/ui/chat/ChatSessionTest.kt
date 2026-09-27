package dev.mikhailtail.handyagent.ui.chat

import dev.mikhailtail.handyagent.core.context.ContextBudget
import dev.mikhailtail.handyagent.core.context.ContextManager
import dev.mikhailtail.handyagent.core.loop.AgentLoop
import dev.mikhailtail.handyagent.core.loop.ScriptedProvider
import dev.mikhailtail.handyagent.core.permission.PermissionBroker
import dev.mikhailtail.handyagent.core.permission.PermissionRelay
import dev.mikhailtail.handyagent.core.platform.AgentRuntime
import dev.mikhailtail.handyagent.core.platform.FakeSecretCipher
import dev.mikhailtail.handyagent.core.platform.InMemoryKeyValueStore
import dev.mikhailtail.handyagent.core.platform.KeyValueStore
import dev.mikhailtail.handyagent.core.platform.WorkspaceLayout
import dev.mikhailtail.handyagent.core.provider.FakeHttpEngine
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import dev.mikhailtail.handyagent.core.sandbox.ProcessShell
import dev.mikhailtail.handyagent.core.tool.ReadFileTool
import dev.mikhailtail.handyagent.core.tool.ToolContext
import dev.mikhailtail.handyagent.core.tool.ToolRegistry
import dev.mikhailtail.handyagent.core.tool.WriteFileTool
import dev.mikhailtail.handyagent.ui.timeline.TimelineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 多会话与历史持久化。
 *
 * 用的是**真实的 [AgentRuntime]**（真实目录、[PathJail]、会话存储），只把 LLM 换成
 * [ScriptedProvider]。于是「历史真的写进了文件」「切回来模型真的还记得」这类
 * 最容易说一套做一套的地方，都在宿主 JVM 上被钉死。
 *
 * 注意 [awaitSaved]：落盘发生在「界面显示完成」**之后**，是真正的异步 IO，
 * 所以断言磁盘内容前必须多等一步，不能只看 UI 状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var layout: WorkspaceLayout
    private lateinit var relay: PermissionRelay
    private lateinit var kv: KeyValueStore

    private val tools = ToolRegistry(listOf(ReadFileTool, WriteFileTool))

    @Before
    fun setUp() {
        layout = WorkspaceLayout(tmp.newFolder("app"))
        relay = PermissionRelay()
        kv = InMemoryKeyValueStore()
    }

    // ------------------------------------------------------------------ 装配

    private fun newRuntime(): AgentRuntime {
        val rt = AgentRuntime(
            layout = layout,
            kv = kv,
            cipher = FakeSecretCipher(),
            http = FakeHttpEngine(),
            approver = relay,
            shellTimeoutMs = 5_000,
        )
        rt.credentials.put("anthropic", "sk-test")
        return rt
    }

    private fun loopFor(provider: ScriptedProvider, runtime: AgentRuntime): AgentLoop {
        val settings = runtime.settingsStore.load()
        val broker = PermissionBroker(relay).apply { applyDefaults(tools.specs()) }
        return AgentLoop(
            provider = provider,
            registry = tools,
            toolContext = ToolContext(
                PathJail(layout.workspace),
                ProcessShell(layout.workspace, timeoutMs = 5_000),
            ),
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

    // ------------------------------------------------------------ 测试小工具

    private suspend fun ChatController.await(predicate: (ChatUiState) -> Boolean): ChatUiState {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(state.value)) return state.value
            yield()
        }
        fail("timed out waiting for state; last=${state.value.items}")
        error("unreachable")
    }

    /**
     * 等本轮跑完**并且**历史真的写进了文件。
     *
     * 注意不能只等「会话存在」：会话在发送开始时就创建好了（索引里立刻可见），
     * 但历史要等这一轮跑完才异步写回，只等前者会读到空会话。
     */
    private suspend fun ChatController.awaitSaved(rt: AgentRuntime, sessions: Int = 1) {
        await {
            val metas = rt.sessions.list()
            it.settled() && metas.size >= sessions &&
                metas.all { m -> rt.sessions.load(m.id)?.messages?.isNotEmpty() == true }
        }
    }

    private fun ChatUiState.users() = items.filterIsInstance<TimelineItem.UserText>()
    private fun ChatUiState.replies() = items.filterIsInstance<TimelineItem.AssistantText>()
    private fun ChatUiState.summaries() = items.filterIsInstance<TimelineItem.RunSummary>()
    private fun ChatUiState.settled() = !running && summaries().isNotEmpty()

    // ---------------------------------------------------------------- 持久化

    @Test
    fun `a finished turn is written to the session store`() = runTest {
        val rt = newRuntime()
        val c = controller(backgroundScope, rt, ScriptedProvider(ScriptedProvider.text("reply")))
        c.start()

        c.send("你好呀")
        c.awaitSaved(rt)

        val meta = rt.sessions.list().single()
        assertEquals("会话标题取自首条消息", "你好呀", meta.title)
        val record = rt.sessions.load(meta.id)!!
        assertTrue("历史必须真的落盘", record.messages.size >= 2)
        assertEquals("你好呀", record.messages.first().text)
    }

    @Test
    fun `the session id is exposed for the ui`() = runTest {
        val rt = newRuntime()
        val c = controller(backgroundScope, rt, ScriptedProvider(ScriptedProvider.text("x")))
        c.start()
        assertNull("还没有内容时不该先建出空会话", c.state.value.sessionId)

        c.send("第一句")
        c.awaitSaved(rt)

        val id = c.state.value.sessionId
        assertNotNull(id)
        assertEquals(id, rt.sessions.list().single().id)
    }

    // ---------------------------------------------------------------- 新建

    @Test
    fun `a new session clears the timeline but keeps the old one on disk`() = runTest {
        val rt = newRuntime()
        val c = controller(
            backgroundScope,
            rt,
            ScriptedProvider(ScriptedProvider.text("第一次回答"), ScriptedProvider.text("第二次回答")),
        )
        c.start()

        c.send("第一句")
        c.awaitSaved(rt)
        val firstId = c.state.value.sessionId!!

        c.newSession()

        assertTrue("时间轴清空", c.state.value.items.isEmpty())
        assertNull("新会话等首条消息再建", c.state.value.sessionId)
        assertEquals("旧会话仍在磁盘上", 1, rt.sessions.list().size)
        assertNotNull(rt.sessions.load(firstId))

        c.send("第二句")
        c.awaitSaved(rt, sessions = 2)

        assertNotEquals(firstId, c.state.value.sessionId)
    }

    // ---------------------------------------------------------------- 切换

    @Test
    fun `switching back restores the previous timeline`() = runTest {
        val rt = newRuntime()
        val c = controller(
            backgroundScope,
            rt,
            ScriptedProvider(ScriptedProvider.text("第一轮回答"), ScriptedProvider.text("第二轮回答")),
        )
        c.start()
        c.send("第一句")
        c.awaitSaved(rt)
        val firstId = c.state.value.sessionId!!

        c.newSession()
        c.send("第二句")
        c.awaitSaved(rt, sessions = 2)

        c.switchTo(firstId)

        val s = c.state.value
        assertEquals(firstId, s.sessionId)
        assertEquals(listOf("第一句"), s.users().map { it.text })
        assertTrue(s.replies().any { it.text == "第一轮回答" })
        assertFalse("重建的条目不该是流式草稿", s.replies().any { it.streaming })
    }

    @Test
    fun `resuming a session restores the model's memory of it`() = runTest {
        val rt = newRuntime()
        val provider = ScriptedProvider(
            ScriptedProvider.text("好的小明"),
            ScriptedProvider.text("你叫小明"),
        )
        val c = controller(backgroundScope, rt, provider)
        c.start()

        c.send("我叫小明")
        c.awaitSaved(rt)
        val id = c.state.value.sessionId!!

        c.newSession()
        c.switchTo(id)
        c.send("我叫什么")

        // 重建的时间轴不含落款（历史里没有这个信息），所以只等这一轮的落款
        c.await { it.summaries().size == 1 && !it.running }

        // 切回来之后，模型必须仍然看得到第一轮的对话
        val lastRequest = provider.requests.last()
        assertTrue(
            "恢复历史后模型应看到之前说过的话：${lastRequest.messages.map { it.text }}",
            lastRequest.messages.any { it.text.contains("我叫小明") },
        )
    }

    @Test
    fun `switching to the same session is a no-op`() = runTest {
        val rt = newRuntime()
        val c = controller(backgroundScope, rt, ScriptedProvider(ScriptedProvider.text("x")))
        c.start()
        c.send("一")
        c.awaitSaved(rt)
        val id = c.state.value.sessionId!!
        val before = c.state.value.items

        c.switchTo(id)

        assertEquals(before, c.state.value.items)
        assertEquals(id, c.state.value.sessionId)
    }

    // ---------------------------------------------------------------- 冷启动

    @Test
    fun `a restarted controller reopens the last session`() = runTest {
        val rt = newRuntime()
        val first = controller(backgroundScope, rt, ScriptedProvider(ScriptedProvider.text("答")))
        first.start()
        first.send("记住我")
        first.awaitSaved(rt)

        // 模拟冷启动：同一个 runtime（同 kv、同目录），换一个 controller
        val restarted = controller(
            backgroundScope,
            rt,
            ScriptedProvider(ScriptedProvider.text("接着聊")),
        )
        restarted.start()

        val s = restarted.state.value
        assertEquals(listOf("记住我"), s.users().map { it.text })
        assertEquals(1, s.sessions.size)
        assertEquals(rt.sessions.list().single().id, s.sessionId)
    }

    // ---------------------------------------------------------------- 删除

    @Test
    fun `deleting the active session returns to an empty state`() = runTest {
        val rt = newRuntime()
        val c = controller(backgroundScope, rt, ScriptedProvider(ScriptedProvider.text("x")))
        c.start()
        c.send("待删除")
        c.awaitSaved(rt)

        c.deleteSession(c.state.value.sessionId!!)

        assertTrue(c.state.value.items.isEmpty())
        assertNull(c.state.value.sessionId)
        assertTrue(rt.sessions.list().isEmpty())
    }

    @Test
    fun `deleting another session keeps the active one`() = runTest {
        val rt = newRuntime()
        val c = controller(
            backgroundScope,
            rt,
            ScriptedProvider(ScriptedProvider.text("一答"), ScriptedProvider.text("二答")),
        )
        c.start()
        c.send("会话一")
        c.awaitSaved(rt)
        val firstId = c.state.value.sessionId!!

        c.newSession()
        c.send("会话二")
        c.awaitSaved(rt, sessions = 2)
        val secondId = c.state.value.sessionId!!

        c.deleteSession(firstId)

        assertEquals("当前会话不受影响", secondId, c.state.value.sessionId)
        assertEquals(1, rt.sessions.list().size)
        assertEquals(listOf("会话二"), c.state.value.users().map { it.text })
    }
}
