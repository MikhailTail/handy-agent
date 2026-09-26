package dev.pocket.agent.ui.chat

import dev.pocket.agent.core.context.ContextBudget
import dev.pocket.agent.core.context.ContextManager
import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.loop.AgentLoop
import dev.pocket.agent.core.loop.ScriptedProvider
import dev.pocket.agent.core.model.Block
import dev.pocket.agent.core.model.ReasoningEffort
import dev.pocket.agent.core.permission.PermissionBroker
import dev.pocket.agent.core.permission.PermissionMode
import dev.pocket.agent.core.permission.PermissionRelay
import dev.pocket.agent.core.permission.PermissionVerdict
import dev.pocket.agent.core.platform.AgentRuntime
import dev.pocket.agent.core.platform.FakeSecretCipher
import dev.pocket.agent.core.platform.InMemoryKeyValueStore
import dev.pocket.agent.core.platform.WorkspaceLayout
import dev.pocket.agent.core.provider.FakeHttpEngine
import dev.pocket.agent.core.sandbox.PathJail
import dev.pocket.agent.core.sandbox.ProcessShell
import dev.pocket.agent.core.tool.ReadFileTool
import dev.pocket.agent.core.tool.ToolContext
import dev.pocket.agent.core.tool.ToolRegistry
import dev.pocket.agent.core.tool.WriteFileTool
import dev.pocket.agent.ui.timeline.TimelineItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Loop 9 的关键验收：**UI 真的接上了 runtime**。
 *
 * 这里不做 Compose（那需要设备/仪器测试），而是装配**真实的 [AgentRuntime]**（假加密器 / 假传输层，
 * 但目录、[PathJail]、工具表、broker 全是真的），只把 LLM 换成脚本化的 [ScriptedProvider]，
 * 然后从这个 controller 的视角跑完整的
 * 「发送 → 流式 → 工具 → 审批弹卡 → 用户裁决 → 收尾」。
 *
 * 于是「审批卡真的会挂起写操作」「拒绝后磁盘没被碰过」「模型确实收到了拒绝理由」
 * 这些最容易说一套做一套的地方，都在宿主 JVM 上被钉死了。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var layout: WorkspaceLayout
    private lateinit var relay: PermissionRelay

    /** 真实工具表：read（只读，免审批）+ write（落盘，必须审批）。 */
    private val tools = ToolRegistry(listOf(ReadFileTool, WriteFileTool))

    @Before
    fun setUp() {
        layout = WorkspaceLayout(tmp.newFolder("app"))
        relay = PermissionRelay()
    }

    // ------------------------------------------------------------------ 装配

    private fun newRuntime(withKey: Boolean = true): AgentRuntime {
        val rt = AgentRuntime(
            layout = layout,
            kv = InMemoryKeyValueStore(),
            cipher = FakeSecretCipher(),
            http = FakeHttpEngine(),
            approver = relay,
            shellTimeoutMs = 5_000,
        )
        if (withKey) rt.credentials.put("anthropic", "sk-test")
        return rt
    }

    /**
     * 复刻 [AgentRuntime.createLoop] 的接线，只是把 provider 换成脚本化的。
     *
     * 模型 / 强度 / 轮次上限**必须同样从 settings 读**：否则「改设置 → 下一轮生效」
     * 这条路径就测不到（假的 loop 会把设置当空气），骨架里的一处硬编码就会掩盖真 bug。
     */
    private fun loopFor(provider: ScriptedProvider, runtime: AgentRuntime): AgentLoop {
        val settings = runtime.settingsStore.load()
        val broker = PermissionBroker(relay).apply {
            applyDefaults(tools.specs())
            settings.permissionModes.forEach { (tool, mode) -> setMode(tool, mode) }
        }
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

    /**
     * [provider] 为 null 时用默认工厂（[AgentRuntime.createLoop]），
     * 这样才能测「没配 key → 发不出去」这条真实路径。
     */
    private fun controller(
        scope: CoroutineScope,
        runtime: AgentRuntime,
        provider: ScriptedProvider? = null,
    ): ChatController = ChatController(
        runtimeProvider = { runtime },
        approvals = relay,
        scope = scope,
        loopFactory = { rt -> provider?.let { loopFor(it, rt) } ?: rt.createLoop() },
    )

    // ------------------------------------------------------------ 测试小工具

    /** 轮询等待状态：真实 IO（工具执行）在别的线程上完成，所以按真实时间兜底。 */
    private suspend fun ChatController.await(predicate: (ChatUiState) -> Boolean): ChatUiState {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val snapshot = state.value
            if (predicate(snapshot)) return snapshot
            yield()
        }
        fail("timed out waiting for state; last=${state.value.items}")
        error("unreachable")
    }

    /** 让挂起的协程有机会继续跑，用来断言「什么也没发生」。 */
    private suspend fun settle(times: Int = 50) = repeat(times) { yield() }

    private fun ChatUiState.toolCards() = items.filterIsInstance<TimelineItem.ToolCall>()
    private fun ChatUiState.notes() = items.filterIsInstance<TimelineItem.Note>()
    private fun ChatUiState.summaries() = items.filterIsInstance<TimelineItem.RunSummary>()
    private fun ChatUiState.users() = items.filterIsInstance<TimelineItem.UserText>()

    private fun finished(s: ChatUiState) = !s.running && s.summaries().isNotEmpty()

    private fun workspaceFile(name: String) = File(layout.workspace, name)

    private fun writeInput(path: String, content: String) =
        Json.obj("path" to Json.Str(path), "content" to Json.Str(content))

    private fun writeCall(path: String, content: String) =
        ScriptedProvider.tools("write" to writeInput(path, content))

    // ------------------------------------------------------------- 启动与就绪

    @Test
    fun `start surfaces the active provider and clears the notice`() = runTest {
        val runtime = newRuntime()
        val controller = controller(backgroundScope, runtime)

        controller.start()

        val s = controller.state.value
        assertTrue("ready after start", s.ready)
        assertEquals("anthropic", s.providerId)
        assertEquals("Anthropic", s.providerName)
        assertEquals("claude-sonnet-4-5", s.model)
        assertTrue("preset models should be offered", s.models.contains("claude-opus-4-1"))
        assertNull(s.notice)
        assertTrue(s.canSend)
        assertNotNull("start should publish a runtime snapshot", s.runtime)
        assertTrue("workspace should be bootstrapped", layout.isBootstrapped())
    }

    @Test
    fun `without a key the controller refuses to send and points at settings`() = runTest {
        val runtime = newRuntime(withKey = false)
        val controller = controller(backgroundScope, runtime)

        controller.start()

        val s = controller.state.value
        assertNull(s.providerId)
        assertFalse("no provider means no send", s.canSend)
        assertEquals(ChatController.NO_PROVIDER, s.notice)

        controller.send("hi")

        assertTrue("nothing may be recorded without a provider", controller.state.value.items.isEmpty())
        assertFalse(controller.state.value.running)
        assertEquals(ChatController.NO_PROVIDER, controller.state.value.notice)
    }

    // ------------------------------------------------------------------ 纯文本

    @Test
    fun `plain text turn lands as user then assistant then summary`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(ScriptedProvider.text("hello there"))
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("hi")
        val s = controller.await { finished(it) }

        assertEquals("hi", s.users().single().text)
        val assistant = s.items.filterIsInstance<TimelineItem.AssistantText>().single()
        assertEquals("hello there", assistant.text)
        assertFalse("the draft must be sealed when the turn ends", assistant.streaming)
        assertEquals(1, s.summaries().size)
    }

    // ------------------------------------------------------------ 会话连续性

    @Test
    fun `a second turn still sees the first turn in context`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(
            ScriptedProvider.text("first reply"),
            ScriptedProvider.text("second reply"),
        )
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("first question")
        controller.await { it.summaries().size == 1 }
        controller.send("second question")
        controller.await { it.summaries().size == 2 && !it.running }

        // 复用同一个 loop 的证据：第二次请求带着完整历史，而不是一张白纸。
        assertEquals(
            listOf("first question", "first reply", "second question"),
            provider.requests.last().messages.map { it.text },
        )
    }

    @Test
    fun `changing the model applies to the next turn without dropping the conversation`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(ScriptedProvider.text("one"), ScriptedProvider.text("two"))
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("q1")
        controller.await { it.summaries().size == 1 }

        controller.setModel("next-model")

        controller.send("q2")
        controller.await { it.summaries().size == 2 && !it.running }

        assertEquals("next-model", provider.requests.last().model)
        assertEquals(
            "the rebuilt session must inherit the conversation",
            listOf("q1", "one", "q2"),
            provider.requests.last().messages.map { it.text },
        )
    }

    @Test
    fun `new session starts a fresh conversation`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(ScriptedProvider.text("one"), ScriptedProvider.text("two"))
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("q1")
        controller.await { it.summaries().size == 1 }
        controller.newSession()
        assertTrue(controller.state.value.items.isEmpty())

        controller.send("q2")
        controller.await { it.summaries().size == 1 && !it.running }

        assertEquals("the new session must not carry the old history", listOf("q2"), provider.requests.last().messages.map { it.text })
        assertEquals(1, controller.state.value.users().size)
    }

    @Test
    fun `a turn aborted mid-tool keeps the history usable for the next turn`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(writeCall("aborted.txt", "x"), ScriptedProvider.text("recovered"))
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        // 停在审批卡上，然后中止：AgentLoop 必须为这次未完成的 tool_use 补一个结果，
        // 否则历史违反协议配对，下一轮请求会直接 400。
        controller.send("write it")
        controller.await { it.approval != null }
        controller.stop()

        assertFalse(workspaceFile("aborted.txt").exists())

        controller.send("try something else")
        val s = controller.await { finished(it) }

        val repaired = provider.requests.last().messages
            .flatMap { it.blocks }
            .filterIsInstance<Block.ToolResult>()
            .single()
        assertTrue("interrupted tool must be backfilled as an error", repaired.isError)
        assertTrue(repaired.content.contains("aborted"))
        assertEquals("the repaired turn should still get an answer", "recovered", s.items.filterIsInstance<TimelineItem.AssistantText>().last().text)
    }

    @Test
    fun `blank input is ignored`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(ScriptedProvider.text("nope"))
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("   ")
        controller.send("\n\t")

        assertTrue(controller.state.value.items.isEmpty())
        assertEquals("provider must not be called for blank input", 0, provider.callCount)
    }

    @Test
    fun `a provider failure becomes an error note instead of a crash`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(ScriptedProvider.failure("boom 500"))
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("hi")
        val s = controller.await { !it.running && it.notes().any { n -> n.kind == TimelineItem.Note.Kind.ERROR } }

        val note = s.notes().single()
        assertTrue("the provider's reason should survive: ${note.text}", note.text.contains("boom 500"))
        assertFalse(s.running)
        assertTrue("a failed turn has no summary", s.summaries().isEmpty())
    }

    // ---------------------------------------------------------------- 工具调用

    @Test
    fun `a read-only tool runs without asking and updates one card in place`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(
            ScriptedProvider.tools("read" to Json.obj("path" to Json.Str("note.txt"))),
            ScriptedProvider.text("done"),
        )
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()
        workspaceFile("note.txt").writeText("file body")

        controller.send("read it")
        val s = controller.await { finished(it) }

        val card = s.toolCards().single()
        assertEquals("read", card.name)
        assertEquals(TimelineItem.ToolCall.Status.OK, card.status)
        assertTrue("output should carry the file: ${card.output}", card.output.contains("file body"))
        assertNull("read-only tools must never ask", s.approval)
    }

    @Test
    fun `a write suspends on the approval card and only then touches the disk`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(writeCall("out.txt", "hello"), ScriptedProvider.text("done"))
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("write it")
        val pending = controller.await { it.approval != null }

        // 卡片内容：工具名 + 人类可读摘要 + 红绿 diff（所见即所批）。
        val request = pending.approval!!.request
        assertEquals("write", request.toolName)
        assertTrue("summary should name the file: ${request.summary}", request.summary.contains("out.txt"))
        assertTrue("a write preview must carry a diff", request.hasDiff)
        assertFalse("nothing may be written before the user says yes", workspaceFile("out.txt").exists())

        controller.resolveApproval(PermissionVerdict.allowOnce())
        val done = controller.await { finished(it) }

        assertNull("card is withdrawn after the decision", done.approval)
        assertTrue("approved write must land", workspaceFile("out.txt").exists())
        assertEquals("hello", workspaceFile("out.txt").readText())
        assertEquals(TimelineItem.ToolCall.Status.OK, done.toolCards().single().status)
    }

    @Test
    fun `denying a write leaves the disk untouched and tells the model why`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(writeCall("blocked.txt", "nope"), ScriptedProvider.text("ok"))
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("write it")
        controller.await { it.approval != null }
        controller.resolveApproval(PermissionVerdict.denyOnce())
        val done = controller.await { finished(it) }

        assertFalse("denied write must not reach the disk", workspaceFile("blocked.txt").exists())
        val card = done.toolCards().single()
        assertEquals(TimelineItem.ToolCall.Status.DENIED, card.status)
        assertTrue("the card should show the reason: ${card.output}", card.output.contains("denied"))

        // 关键：拒绝必须回填成 isError 的 tool_result，模型才知道此路不通。
        val toolResult = provider.requests.last().messages.last().blocks
            .filterIsInstance<Block.ToolResult>().single()
        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("denied"))
    }

    @Test
    fun `dismissing the card counts as denying once`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(writeCall("gone.txt", "x"), ScriptedProvider.text("ok"))
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("write it")
        controller.await { it.approval != null }
        controller.dismissApproval()
        val done = controller.await { finished(it) }

        assertFalse(workspaceFile("gone.txt").exists())
        assertEquals(TimelineItem.ToolCall.Status.DENIED, done.toolCards().single().status)
    }

    @Test
    fun `allowing for the session silences the next write of the same tool`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(
            writeCall("a.txt", "A"),
            ScriptedProvider.text("ok"),
            writeCall("b.txt", "B"),
            ScriptedProvider.text("ok again"),
        )
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("first")
        controller.await { it.approval != null }
        controller.resolveApproval(PermissionVerdict.allowSession())
        controller.await { it.summaries().size == 1 }

        // 第二次不再问：如果它问了，这里的等待会超时。
        controller.send("second")
        val s = controller.await { it.summaries().size == 2 && !it.running }

        assertNull("session allow must silence the second ask", s.approval)
        assertEquals("A", workspaceFile("a.txt").readText())
        assertEquals("B", workspaceFile("b.txt").readText())
    }

    @Test
    fun `NEVER policy denies without showing a card`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(writeCall("nope.txt", "x"), ScriptedProvider.text("ok"))
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()
        controller.setPermissionMode("write", PermissionMode.NEVER)

        controller.send("write it")
        val s = controller.await { finished(it) }

        assertNull("a disabled tool must not prompt", s.approval)
        assertFalse(workspaceFile("nope.txt").exists())
        assertEquals(TimelineItem.ToolCall.Status.DENIED, s.toolCards().single().status)
    }

    // ------------------------------------------------------- fail-closed 装配

    @Test
    fun `dispose unbinds the relay and later writes fail closed`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(writeCall("after-dispose.txt", "x"), ScriptedProvider.text("ok"))
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.dispose()
        assertFalse("a dead UI must not stay bound as the approver", relay.bound)

        controller.send("write it")
        val s = controller.await { finished(it) }

        assertNull(s.approval)
        assertFalse("no approver means no silent write", workspaceFile("after-dispose.txt").exists())
        assertEquals(TimelineItem.ToolCall.Status.DENIED, s.toolCards().single().status)
    }

    // ------------------------------------------------------------ 停止 / 新会话

    @Test
    fun `send is ignored while a turn is already in flight`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(ScriptedProvider.text("slow")).apply {
            gate = CompletableDeferred()
        }
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("one")
        controller.send("two")
        settle()

        assertEquals("only the first message may be recorded", 1, controller.state.value.users().size)
        assertEquals("one", controller.state.value.users().single().text)
        assertTrue(controller.state.value.running)
        assertTrue("second send must not reach the provider", provider.callCount <= 1)
    }

    @Test
    fun `stop aborts the turn and cancels the in-flight stream`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(ScriptedProvider.text("never arrives")).apply {
            gate = CompletableDeferred()
        }
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("hi")
        controller.await { it.running && provider.callCount == 1 }

        controller.stop()

        val stopped = controller.state.value
        assertFalse(stopped.running)
        assertTrue(stopped.notes().any { it.text == ChatController.ABORTED_TEXT })

        // 放开闸门：被取消的这一轮不能再往时间轴里写任何东西。
        val frozen = stopped.items.size
        provider.gate!!.complete(Unit)
        settle()
        assertEquals("aborted run must stay silent", frozen, controller.state.value.items.size)
    }

    @Test
    fun `new session clears the timeline and abandons the running turn`() = runTest {
        val runtime = newRuntime()
        val provider = ScriptedProvider(ScriptedProvider.text("never arrives")).apply {
            gate = CompletableDeferred()
        }
        val controller = controller(backgroundScope, runtime, provider)
        controller.start()

        controller.send("hi")
        controller.await { it.running }
        controller.newSession()

        assertTrue(controller.state.value.items.isEmpty())
        assertFalse(controller.state.value.running)

        provider.gate!!.complete(Unit)
        settle()
        assertTrue("the abandoned turn must not repopulate the fresh timeline", controller.state.value.items.isEmpty())
    }

    // ------------------------------------------------------------------ 设置

    @Test
    fun `settings mutations are persisted and reflected in the snapshot`() = runTest {
        val runtime = newRuntime()
        val controller = controller(backgroundScope, runtime)
        controller.start()

        controller.setModel("custom-model")
        controller.setEffort(ReasoningEffort.HIGH)
        controller.setMaxIterations(40)
        controller.setPermissionMode("write", PermissionMode.NEVER)

        val s = controller.state.value
        assertEquals("custom-model", s.model)
        assertEquals(ReasoningEffort.HIGH, s.effort)
        assertEquals(40, s.maxIterations)
        assertEquals(PermissionMode.NEVER, s.permissionModes["write"])

        val stored = runtime.settingsStore.load()
        assertEquals("custom-model", stored.model)
        assertEquals(ReasoningEffort.HIGH, stored.effort)
        assertEquals(40, stored.maxIterations)
        assertEquals(PermissionMode.NEVER, stored.permissionModes["write"])
    }

    @Test
    fun `refresh picks up a provider that was configured after startup`() = runTest {
        val runtime = newRuntime(withKey = false)
        val controller = controller(backgroundScope, runtime)
        controller.start()
        assertEquals(ChatController.NO_PROVIDER, controller.state.value.notice)

        // 等价于用户在设置页填完 key。
        runtime.credentials.put("anthropic", "sk-later")
        controller.reloadProviders()

        assertEquals("anthropic", controller.state.value.providerId)
        assertNull(controller.state.value.notice)
        assertTrue(controller.state.value.canSend)
    }
}
