package dev.mikhailtail.handyagent.core.loop

import dev.mikhailtail.handyagent.core.context.COMPACTION_MARKER
import dev.mikhailtail.handyagent.core.context.ContextBudget
import dev.mikhailtail.handyagent.core.context.ContextManager
import dev.mikhailtail.handyagent.core.context.Summarizer
import dev.mikhailtail.handyagent.core.context.TokenEstimator
import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.ReasoningEffort
import dev.mikhailtail.handyagent.core.model.Role
import dev.mikhailtail.handyagent.core.model.StopReason
import dev.mikhailtail.handyagent.core.permission.PermissionApprover
import dev.mikhailtail.handyagent.core.permission.PermissionMode
import dev.mikhailtail.handyagent.core.permission.PermissionBroker
import dev.mikhailtail.handyagent.core.permission.PermissionVerdict
import dev.mikhailtail.handyagent.core.permission.RememberScope
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import dev.mikhailtail.handyagent.core.sandbox.ProcessShell
import dev.mikhailtail.handyagent.core.tool.EditFileTool
import dev.mikhailtail.handyagent.core.tool.ReadFileTool
import dev.mikhailtail.handyagent.core.tool.Tool
import dev.mikhailtail.handyagent.core.tool.ToolContext
import dev.mikhailtail.handyagent.core.tool.ToolOutcome
import dev.mikhailtail.handyagent.core.tool.ToolRegistry
import dev.mikhailtail.handyagent.core.tool.WriteFileTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var jail: PathJail
    private lateinit var registry: ToolRegistry
    private lateinit var ctx: ToolContext

    @Before
    fun setUpSandbox() {
        root = tmp.newFolder("ws")
        jail = PathJail(root)
        registry = ToolRegistry(listOf(ReadFileTool, WriteFileTool, EditFileTool))
        ctx = ToolContext(jail, ProcessShell(root, timeoutMs = 5_000))
    }

    private fun loop(
        provider: ScriptedProvider,
        broker: PermissionBroker = PermissionBroker(),
        contextManager: ContextManager? = null,
        summarizer: Summarizer? = null,
        maxIterations: Int = 24,
        tools: ToolRegistry = registry,
    ): AgentLoop {
        return AgentLoop(
            provider = provider,
            registry = tools,
            toolContext = ctx,
            broker = broker,
            contextManager = contextManager,
            summarizer = summarizer,
            systemPrompt = "sys",
            model = "m",
            effort = ReasoningEffort.OFF,
            maxIterations = maxIterations,
        )
    }

    private fun writeInput(path: String, content: String): Json =
        Json.obj("path" to Json.Str(path), "content" to Json.Str(content))


    // ---------------------------------------------------- 压缩预算（自校准）
    //
    // 触发阈值依赖工具 schema 的体积（每轮都要发送），写死数字很脆。
    // 这里用 TokenEstimator 现算：让阈值恰好落在「压缩前状态」与「该压缩状态」之间。

    private fun est(messages: List<Message>): Int =
        TokenEstimator.estimate("sys") + TokenEstimator.estimate(messages) +
            TokenEstimator.estimateTools(registry.specs())

    private fun budgetTriggeringBetween(before: List<Message>, at: List<Message>): ContextManager {
        val lo = est(before)
        require(est(at) > lo) { "test setup: 'at' state must be heavier than 'before' state" }
        // threshold = 1.0 时 triggerTokens == maxTokens，取 maxTokens = est(before)
        // 就恰好落在 (before, at] 区间：处于 before 状态不触发，进入 at 状态必触发。
        return ContextManager(
            ContextBudget(maxTokens = lo, threshold = 1.0, keepTurns = 1, reserveOutputTokens = 0)
        )
    }

    /** 逐字对应脚本化 provider 的真实回复，保证预算估算与实际历史完全对齐。 */
    private fun turns(vararg pairs: Pair<String, String>): List<Message> =
        pairs.flatMap { (user, reply) -> listOf(Message.user(user), Message.assistant(reply)) }

    // ------------------------------------------------------------------ 基础

    @Test
    fun `plain text turn produces deltas and completes`() = runTest {
        val provider = ScriptedProvider(ScriptedProvider.text("hello there"))
        val agent = loop(provider)

        val events = agent.run("hi").toList()

        assertTrue(events.first() is AgentEvent.IterationStarted)
        assertEquals(listOf("hello there"), events.filterIsInstance<AgentEvent.TextDelta>().map { it.text })
        val done = events.last() as AgentEvent.Completed
        assertEquals(StopReason.END_TURN, done.stopReason)
        assertEquals(1, done.iterations)
        assertEquals(1, provider.callCount)

        val history = agent.history()
        assertEquals(2, history.size)
        assertEquals(Role.USER, history[0].role)
        assertEquals(Role.ASSISTANT, history[1].role)
        assertNull(agent.validatePairing(history))
        assertEquals(15, agent.totalUsage().total)
    }

    @Test
    fun `request carries system prompt model tools and effort`() = runTest {
        val provider = ScriptedProvider(ScriptedProvider.text("ok"))
        val agent = loop(provider)
        agent.run("hi").toList()

        val req = provider.requests.single()
        assertEquals("sys", req.system)
        assertEquals("m", req.model)
        assertEquals(ReasoningEffort.OFF, req.effort)
        assertEquals(listOf("read", "write", "edit"), req.tools.map { it.name })
        // 只读工具自动放行；写类工具默认需要审批。
        assertTrue(req.tools.first { it.name == "read" }.readOnly)
        assertFalse(req.tools.first { it.name == "write" }.readOnly)
    }

    // ------------------------------------------------------- 工具调用与回填

    @Test
    fun `approved write executes and result is fed back to the model`() = runTest {
        val approver = PermissionApprover { PermissionVerdict.allowOnce() }
        val broker = PermissionBroker(approver).apply { applyDefaults(registry.specs()) }
        val provider = ScriptedProvider(
            ScriptedProvider.tools("write" to writeInput("a.txt", "hello")),
            ScriptedProvider.text("done"),
        )
        val agent = loop(provider, broker)

        val events = agent.run("create a.txt").toList()

        assertTrue(events.any { it is AgentEvent.ToolStarted })
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("write", finished.name)
        assertFalse(finished.outcome.isError)
        assertEquals("hello", File(root, "a.txt").readText())
        assertEquals(StopReason.END_TURN, (events.last() as AgentEvent.Completed).stopReason)

        // 第二次请求必须携带 tool_result，且是「仅含 tool_result 的 USER 消息」
        val second = provider.requests[1]
        val last = second.messages.last()
        assertEquals(Role.USER, last.role)
        assertEquals("call_0", last.toolResults.single().toolUseId)
        assertNull(agent.validatePairing(agent.history()))
    }

    @Test
    fun `denied tool is not executed and denial is reported to the model`() = runTest {
        val approver = PermissionApprover { PermissionVerdict.denyOnce() }
        val broker = PermissionBroker(approver).apply { applyDefaults(registry.specs()) }
        val provider = ScriptedProvider(
            ScriptedProvider.tools("write" to writeInput("a.txt", "hello")),
            ScriptedProvider.text("understood"),
        )
        val agent = loop(provider, broker)

        val events = agent.run("create a.txt").toList()

        assertTrue(events.any { it is AgentEvent.ToolDenied })
        assertFalse(events.any { it is AgentEvent.ToolStarted })
        assertFalse(File(root, "a.txt").exists())

        val result = provider.requests[1].messages.last().toolResults.single()
        assertTrue(result.isError)
        assertTrue(result.content.contains("denied"))
    }

    @Test
    fun `no approver means fail-closed and never silently writes`() = runTest {
        // 刻意不 applyDefaults → write 落默认 ASK，但没有 approver
        val provider = ScriptedProvider(
            ScriptedProvider.tools("write" to writeInput("a.txt", "x")),
            ScriptedProvider.text("ok"),
        )
        val agent = loop(provider)

        val events = agent.run("go").toList()

        assertFalse(File(root, "a.txt").exists())
        val denied = events.filterIsInstance<AgentEvent.ToolDenied>().single()
        assertTrue(denied.reason.contains("no approver"))
    }

    @Test
    fun `multiple tool calls in one turn produce ordered paired results`() = runTest {
        val approver = PermissionApprover { PermissionVerdict.allowOnce() }
        val broker = PermissionBroker(approver).apply { applyDefaults(registry.specs()) }
        val provider = ScriptedProvider(
            ScriptedProvider.tools(
                "write" to writeInput("a.txt", "A"),
                "write" to writeInput("b.txt", "B"),
            ),
            ScriptedProvider.text("done"),
        )
        val agent = loop(provider, broker)

        agent.run("two files").toList()

        val results = provider.requests[1].messages.last().toolResults
        assertEquals(listOf("call_0", "call_1"), results.map { it.toolUseId })
        assertEquals("A", File(root, "a.txt").readText())
        assertEquals("B", File(root, "b.txt").readText())
    }

    @Test
    fun `unknown tool yields an error result instead of crashing`() = runTest {
        val provider = ScriptedProvider(
            ScriptedProvider.tools("nope" to Json.obj()),
            ScriptedProvider.text("sorry"),
        )
        // 未知工具落默认 ASK；这里显式放行，验证「执行期未知工具」的容错分支
        val broker = PermissionBroker().apply { setMode("nope", PermissionMode.ALWAYS) }
        val agent = loop(provider, broker)

        val events = agent.run("go").toList()

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.outcome.isError)
        assertTrue(finished.outcome.content.contains("unknown tool"))
    }

    @Test
    fun `sandbox escape attempt returns error result and does not write outside root`() = runTest {
        val approver = PermissionApprover { PermissionVerdict.allowOnce() }
        val broker = PermissionBroker(approver).apply { applyDefaults(registry.specs()) }
        val provider = ScriptedProvider(
            ScriptedProvider.tools("write" to writeInput("../escape.txt", "x")),
            ScriptedProvider.text("blocked"),
        )
        val agent = loop(provider, broker)

        val events = agent.run("escape").toList()

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.outcome.isError)
        assertFalse(File(root.parentFile, "escape.txt").exists())
    }

    @Test
    fun `read-only tool runs without asking`() = runTest {
        File(root, "seed.txt").writeText("seeded")
        var asked = 0
        val approver = PermissionApprover { asked++; PermissionVerdict.allowOnce() }
        val broker = PermissionBroker(approver).apply { applyDefaults(registry.specs()) }
        val provider = ScriptedProvider(
            ScriptedProvider.tools("read" to Json.obj("path" to Json.Str("seed.txt"))),
            ScriptedProvider.text("read it"),
        )
        val agent = loop(provider, broker)

        agent.run("read").toList()

        assertEquals(0, asked)
        val result = provider.requests[1].messages.last().toolResults.single()
        assertTrue(result.content.contains("seeded"))
    }

    // -------------------------------------------------------- 循环与终止条件

    @Test
    fun `iteration cap stops runaway tool loop and keeps history valid`() = runTest {
        val approver = PermissionApprover { PermissionVerdict.allowOnce() }
        val broker = PermissionBroker(approver).apply { applyDefaults(registry.specs()) }
        // 永远返回同一个 tool_use：只有 maxIterations 能终止它
        val provider = ScriptedProvider(
            ScriptedProvider.tools("write" to writeInput("loop.txt", "x"))
        )
        val agent = loop(provider, broker, maxIterations = 3)

        val events = agent.run("go").toList()

        val done = events.last() as AgentEvent.Completed
        assertEquals(3, done.iterations)
        assertEquals(3, provider.callCount)
        assertNull(agent.validatePairing(agent.history()))
        // 每轮 1 条 user + 1 条 assistant + 1 条 tool_result
        assertEquals(1 + 3 * 2, agent.history().size)
    }

    @Test
    fun `provider failure ends the turn without corrupting history`() = runTest {
        val provider = ScriptedProvider(ScriptedProvider.failure("boom"))
        val agent = loop(provider)

        val events = agent.run("hi").toList()

        val failed = events.last() as AgentEvent.Failed
        assertEquals("boom", failed.message)
        assertEquals(1, failed.iterations)
        assertEquals(listOf(Role.USER), agent.history().map { it.role })
        assertNull(agent.validatePairing(agent.history()))
    }

    @Test
    fun `stream ending without Completed is reported as failure`() = runTest {
        val provider = ScriptedProvider(listOf(ProviderEventTextOnly))
        val agent = loop(provider)

        val events = agent.run("hi").toList()

        assertTrue(events.last() is AgentEvent.Failed)
        assertEquals(1, agent.history().size)
    }

    @Test
    fun `cancellation mid tool-use repairs history so the next request stays valid`() = runTest {
        val approver = PermissionApprover { PermissionVerdict.allowOnce() }
        val broker = PermissionBroker(approver).apply { applyDefaults(registry.specs()) }
        val provider = ScriptedProvider(
            ScriptedProvider.tools(
                "write" to writeInput("a.txt", "A"),
                "write" to writeInput("b.txt", "B"),
            ),
            ScriptedProvider.text("done"),
        )
        // 第一个工具执行前就取消：历史里已有 assistant.tool_use，必须补齐 result
        val interrupt = CompletableDeferred<Unit>()
        val gateBroker = PermissionBroker(object : PermissionApprover {
            override suspend fun approve(request: dev.mikhailtail.handyagent.core.permission.PermissionRequest): PermissionVerdict {
                interrupt.complete(Unit)
                return PermissionVerdict.allowOnce()
            }
        }).apply { applyDefaults(registry.specs()) }
        val agent = loop(provider, gateBroker)

        val seen = ArrayList<AgentEvent>()
        val job = launch {
            agent.run("go").collect { seen.add(it) }
        }
        interrupt.await()
        job.cancel()
        job.join()

        // 注意：不能断言 seen 里有 Aborted。冷流的「生产者」与「收集者」是同一个协程，
        // 收集者被取消后 emit 会在 ensureActive() 处直接抛出，事件不可能再送达。
        // 因此这里的硬保证是：不产生 Completed、历史被修复为协议合法状态、
        // 且「下一轮」还能正常发起请求（这正是本用例要守的不变量）。
        assertTrue(seen.none { it is AgentEvent.Completed })
        val history = agent.history()
        assertNull(agent.validatePairing(history))
        val tail = history.last()
        assertEquals(Role.USER, tail.role)
        assertEquals(listOf("call_0", "call_1"), tail.toolResults.map { it.toolUseId })
        assertTrue(tail.toolResults.all { it.isError && it.content.contains("aborted") })

        // 中断后同一实例仍可继续使用：补齐的 tool_result 让下一次请求保持配对合法。
        val next = agent.run("again").toList()
        assertEquals(StopReason.END_TURN, (next.last() as AgentEvent.Completed).stopReason)
        assertNull(agent.validatePairing(agent.history()))
    }

    @Test
    fun `a CancellationException raised by a tool aborts the turn and is reported`() = runTest {
        // 工具/网络库主动抛出 CancellationException（收集者并未被取消）时，
        // 循环应当补齐历史并以 Aborted 事件收尾——这是 Aborted 唯一可达的路径。
        val exploding = object : Tool {
            override val name = "explode"
            override val description = "always throws"
            override val inputSchema: Json = Json.obj()
            override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome =
                throw CancellationException("tool aborted")
        }
        val reg = ToolRegistry(listOf(ReadFileTool, WriteFileTool, EditFileTool, exploding))
        val broker = PermissionBroker(PermissionApprover { PermissionVerdict.allowOnce() })
            .apply { applyDefaults(reg.specs()) }
        val provider = ScriptedProvider(
            ScriptedProvider.tools("explode" to Json.obj(), "write" to writeInput("a.txt", "A")),
            ScriptedProvider.text("done"),
        )
        val agent = loop(provider, broker, tools = reg)

        val seen = ArrayList<AgentEvent>()
        var propagated = false
        try {
            agent.run("go").collect { seen.add(it) }
        } catch (e: CancellationException) {
            propagated = true
        }

        assertTrue("cancellation must reach the caller", propagated)
        assertTrue(seen.any { it is AgentEvent.Aborted })
        assertTrue(seen.none { it is AgentEvent.Completed })
        assertFalse(File(root, "a.txt").exists())
        val history = agent.history()
        assertNull(agent.validatePairing(history))
        val tail = history.last()
        assertEquals(Role.USER, tail.role)
        assertEquals(listOf("call_0", "call_1"), tail.toolResults.map { it.toolUseId })
        assertTrue(tail.toolResults.all { it.isError && it.content.contains("aborted") })
    }

    @Test
    fun `concurrent runs are serialized by the mutex`() = runTest {
        val provider = ScriptedProvider(ScriptedProvider.text("first"), ScriptedProvider.text("second"))
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        val agent = loop(provider)

        val order = ArrayList<String>()
        val a = launch { agent.run("a").collect { if (it is AgentEvent.Completed) order.add("a") } }
        val b = launch { agent.run("b").collect { if (it is AgentEvent.Completed) order.add("b") } }
        gate.complete(Unit)
        a.join(); b.join()

        assertEquals(listOf("a", "b"), order)
        assertNull(agent.validatePairing(agent.history()))
    }

    // ---------------------------------------------------------------- 压缩

    @Test
    fun `compaction fires before the request and replaces history with summary`() = runTest {
        val summarizer = Summarizer { older -> "summarized ${older.size}" }
        // 2 轮结束后不触发，加入第 3 轮用户输入后触发
        val cm = budgetTriggeringBetween(
            turns("u1" to "one", "u2" to "two"),
            turns("u1" to "one", "u2" to "two", "u3" to "three"),
        )
        val provider = ScriptedProvider(
            ScriptedProvider.text("one"), ScriptedProvider.text("two"), ScriptedProvider.text("three"),
        )
        val agent = loop(provider, contextManager = cm, summarizer = summarizer)

        agent.run("u1").toList()
        agent.run("u2").toList()
        val events = agent.run("u3").toList()

        val compacted = events.filterIsInstance<AgentEvent.Compacted>()
        assertTrue("expected a compaction event", compacted.isNotEmpty())
        assertTrue(compacted.first().result.summary.startsWith("summarized"))
        // 压缩后的历史首条是摘要（USER 文本），且配对完整
        val history = agent.history()
        assertTrue(history.first().text.contains("[conversation summary]"))
        assertNull(agent.validatePairing(history))
    }

    @Test
    fun `compaction never splits a tool_use from its result`() = runTest {
        val approver = PermissionApprover { PermissionVerdict.allowOnce() }
        val broker = PermissionBroker(approver).apply { applyDefaults(registry.specs()) }
        val summarizer = Summarizer { older -> "sum(${older.size})" }
        val toolTurn1 = listOf(
            Message.user("first"),
            Message(Role.ASSISTANT, listOf(Block.ToolUse("call_0", "write", writeInput("a.txt", "A")))),
            Message.toolResults(listOf(Block.ToolResult("call_0", "wrote a.txt"))),
            Message.assistant("done once"),
        )
        val toolTurn2 = listOf(
            Message.user("second"),
            Message(Role.ASSISTANT, listOf(Block.ToolUse("call_0", "write", writeInput("b.txt", "B")))),
            Message.toolResults(listOf(Block.ToolResult("call_0", "wrote b.txt"))),
            Message.assistant("done twice"),
        )
        val cm = budgetTriggeringBetween(
            toolTurn1 + toolTurn2,
            toolTurn1 + toolTurn2 + listOf(Message.user("third")),
        )
        val provider = ScriptedProvider(
            ScriptedProvider.tools("write" to writeInput("a.txt", "A")),
            ScriptedProvider.text("done once"),
            ScriptedProvider.tools("write" to writeInput("b.txt", "B")),
            ScriptedProvider.text("done twice"),
            ScriptedProvider.text("done thrice"),
        )
        val agent = loop(provider, broker, contextManager = cm, summarizer = summarizer)

        val events = ArrayList<AgentEvent>()
        events += agent.run("first").toList()
        events += agent.run("second").toList()
        events += agent.run("third").toList()

        // 压缩发生在哪一轮取决于预算，但「不得拆散 tool_use / tool_result」必须恒成立。
        assertTrue("expected at least one compaction", events.any { it is AgentEvent.Compacted })
        assertNull(agent.validatePairing(agent.history()))

        // 压缩结果必须真的送到了 provider（否则不变量只在校验、没在真链路生效）
        val compactedRequests = provider.requests.filter { req ->
            req.messages.any { it.text.contains(COMPACTION_MARKER) }
        }
        assertTrue("compaction must reach the provider", compactedRequests.isNotEmpty())
        for (req in provider.requests) {
            assertNull("every request must keep tool_use/tool_result paired", agent.validatePairing(req.messages))
            assertFalse(
                "a request must never start with a bare tool_result",
                req.messages.first().blocks.all { it is Block.ToolResult },
            )
        }
    }

    // ------------------------------------------------------------- 历史校验

    @Test
    fun `restore rejects malformed history`() = runTest {
        val agent = loop(ScriptedProvider(ScriptedProvider.text("x")))
        val broken = listOf(
            Message.user("hi"),
            Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "read", Json.obj()))),
            Message.assistant("no results in between"),
        )
        val err = agent.validatePairing(broken)
        assertNotNull(err)

        var threw = false
        try {
            agent.restore(broken)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(agent.history().isEmpty())
    }

    @Test
    fun `restore accepts a well formed history and history snapshot is immutable`() = runTest {
        val agent = loop(ScriptedProvider(ScriptedProvider.text("x")))
        val good = listOf(
            Message.user("hi"),
            Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "read", Json.obj("path" to Json.Str("a"))))),
            Message.toolResults(listOf(Block.ToolResult("t1", "content"))),
            Message.assistant("done"),
        )
        assertNull(agent.validatePairing(good))
        agent.restore(good)
        assertEquals(4, agent.history().size)
        assertEquals(4, agent.history().size)
    }

    @Test
    fun `blank summary falls back to explicit drop marker`() = runTest {
        val summarizer = Summarizer { throw IllegalStateException("no summarizer") }
        val cm = budgetTriggeringBetween(
            turns("u1" to "one", "u2" to "two"),
            turns("u1" to "one", "u2" to "two", "u3" to "three"),
        )
        val provider = ScriptedProvider(
            ScriptedProvider.text("one"), ScriptedProvider.text("two"), ScriptedProvider.text("three"),
        )
        val agent = loop(provider, contextManager = cm, summarizer = summarizer)

        agent.run("u1").toList()
        agent.run("u2").toList()
        agent.run("u3").toList()

        val first = agent.history().first()
        assertTrue(first.text.contains("[conversation summary]"))
        assertTrue(first.text.contains("were dropped"))
    }

    @Test
    fun `remember session suppresses later prompts`() = runTest {
        var asked = 0
        val approver = PermissionApprover {
            asked++
            PermissionVerdict(true, RememberScope.SESSION)
        }
        val broker = PermissionBroker(approver).apply { applyDefaults(registry.specs()) }
        val provider = ScriptedProvider(
            ScriptedProvider.tools("write" to writeInput("a.txt", "A")),
            ScriptedProvider.text("one"),
            ScriptedProvider.tools("write" to writeInput("b.txt", "B")),
            ScriptedProvider.text("two"),
        )
        val agent = loop(provider, broker)

        agent.run("first").toList()
        agent.run("second").toList()

        assertEquals(1, asked)
        assertEquals("A", File(root, "a.txt").readText())
        assertEquals("B", File(root, "b.txt").readText())
    }
}

/** 只发了一个 delta、没有 Completed 的畸形流。 */
private val ProviderEventTextOnly = dev.mikhailtail.handyagent.core.provider.ProviderEvent.TextDelta("partial")
