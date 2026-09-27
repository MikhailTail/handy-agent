package dev.mikhailtail.handyagent.core.loop

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.mcp.McpManager
import dev.mikhailtail.handyagent.core.mcp.McpServerConfig
import dev.mikhailtail.handyagent.core.mcp.McpToolDescriptor
import dev.mikhailtail.handyagent.core.mcp.ScriptedMcpServer
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.ReasoningEffort
import dev.mikhailtail.handyagent.core.permission.PermissionApprover
import dev.mikhailtail.handyagent.core.permission.PermissionBroker
import dev.mikhailtail.handyagent.core.permission.PermissionVerdict
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import dev.mikhailtail.handyagent.core.sandbox.ProcessShell
import dev.mikhailtail.handyagent.core.tool.Tool
import dev.mikhailtail.handyagent.core.tool.ToolContext
import dev.mikhailtail.handyagent.core.tool.ToolOutcome
import dev.mikhailtail.handyagent.core.tool.ToolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Loop 7 端到端：MCP 桥接工具走完整 AgentLoop ——
 * 注入系统提示、并入工具表、经审批执行、结果回填历史。
 */
class AgentLoopMcpTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: ToolContext

    @Before
    fun setUp() {
        val root: File = tmp.newFolder("ws")
        ctx = ToolContext(PathJail(root), ProcessShell(root, timeoutMs = 10_000))
    }

    private fun desc(name: String) =
        McpToolDescriptor(name, "$name tool", Json.obj("type" to Json.Str("object")))

    private fun cfg(id: String, enabled: Boolean = true) =
        McpServerConfig(id = id, command = listOf("fake-$id"), enabled = enabled)

    /**
     * 一个服务端 id 对应一个脚本化内存服务端，并把实例暴露出来供断言。
     *
     * 必须把 [McpManager] 的调度器钉在**测试时钟**上：`McpClient` 的读循环跑在自己的 scope，
     * `withTimeout` 用的却是调用方时钟。若沿用生产默认的 `Dispatchers.Default`，
     * `runTest` 会在真实线程投递应答之前就把虚拟时间推到超时点，
     * 于是 `initialize` 随机抛「timed out after 20000ms」——一个 1/10 概率的 flake。
     */
    private fun TestScope.manager(
        configs: List<McpServerConfig>,
        tools: List<McpToolDescriptor> = listOf(desc("echo")),
        servers: MutableMap<String, ScriptedMcpServer> = LinkedHashMap(),
    ): McpManager = McpManager(
        configs,
        dispatcher = UnconfinedTestDispatcher(testScheduler),
        transportFactory = { c ->
            ScriptedMcpServer(name = c.id, serverName = "srv-${c.id}", tools = tools)
                .also { servers[c.id] = it }
        },
    )

    private fun stub(name: String) = object : Tool {
        override val name = name
        override val description = "stub"
        override val inputSchema = Json.obj("type" to Json.Str("object"))
        override suspend fun execute(input: Json, ctx: ToolContext) = ToolOutcome.ok("stub")
    }

    private fun loop(
        provider: ScriptedProvider,
        registry: ToolRegistry = ToolRegistry(listOf(stub("noop"))),
        mcpManager: McpManager? = null,
        broker: PermissionBroker = PermissionBroker(PermissionApprover { PermissionVerdict.allowOnce() }),
    ): AgentLoop = AgentLoop(
        provider = provider,
        registry = registry,
        toolContext = ctx,
        broker = broker,
        systemPrompt = "sys",
        mcpManager = mcpManager,
        model = "m",
        effort = ReasoningEffort.OFF,
    )

    // ------------------------------------------------------------------ 提示 / 工具表

    @Test
    fun `mcp section is injected and bridged tools are advertised`() = runTest {
        val servers = LinkedHashMap<String, ScriptedMcpServer>()
        val mcp = manager(listOf(cfg("a")), servers = servers)
        mcp.startAll()

        val provider = ScriptedProvider(ScriptedProvider.text("ok"))
        loop(provider, mcpManager = mcp).run("hi").toList()

        val req = provider.requests.single()
        assertTrue(req.system.contains("## MCP servers"))
        assertTrue(req.system.contains("a (srv-a): mcp__a__echo"))
        assertTrue("bridged tool must be callable by the model", req.tools.any { it.name == "mcp__a__echo" })
    }

    @Test
    fun `a server started after the loop was built is still picked up`() = runTest {
        val mcp = manager(listOf(cfg("a")))
        val provider = ScriptedProvider(ScriptedProvider.text("ok"), ScriptedProvider.text("ok"))
        val agent = loop(provider, mcpManager = mcp)

        // 构造期尚未连接：第一轮里既无提示片段也无工具。
        agent.run("one").toList()
        assertFalse(provider.requests[0].tools.any { it.name.startsWith("mcp__") })
        assertFalse(provider.requests[0].system.contains("## MCP servers"))

        // 连接发生在构造之后，第二轮必须动态生效。
        mcp.startAll()
        agent.run("two").toList()
        assertTrue(provider.requests[1].tools.any { it.name == "mcp__a__echo" })
        assertTrue(provider.requests[1].system.contains("## MCP servers"))
    }

    @Test
    fun `a bridged name conflicting with the base registry is skipped not overwritten`() = runTest {
        val mcp = manager(listOf(cfg("a")))
        mcp.startAll()

        // base 里已有同名工具：合并时必须保留它，远端同名者被丢弃。
        val registry = ToolRegistry(listOf(stub("mcp__a__echo")))
        val provider = ScriptedProvider(ScriptedProvider.text("ok"))
        loop(provider, registry = registry, mcpManager = mcp).run("hi").toList()

        assertEquals(1, provider.requests.single().tools.size)
        assertEquals("mcp__a__echo", provider.requests.single().tools.single().name)
        assertEquals("base implementation wins", "stub", provider.requests.single().tools.single().description)
    }

    // ------------------------------------------------------------------ 端到端调用

    @Test
    fun `an mcp tool call round trips through bridge approval and history`() = runTest {
        val servers = LinkedHashMap<String, ScriptedMcpServer>()
        val mcp = manager(listOf(cfg("a")), servers = servers)
        mcp.startAll()

        val provider = ScriptedProvider(
            ScriptedProvider.tools(
                "mcp__a__echo" to Json.obj("text" to Json.Str("hello")),
            ),
            ScriptedProvider.text("done"),
        )
        val agent = loop(provider, mcpManager = mcp)

        val events = agent.run("echo please").toList()

        // 远端收到的是**原始工具名与参数**，不是本地命名空间名。
        assertEquals(listOf("echo" to Json.obj("text" to Json.Str("hello"))), servers["a"]!!.calls)

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("mcp__a__echo", finished.name)
        assertFalse(finished.outcome.isError)
        assertTrue(finished.outcome.content, finished.outcome.content.contains("ok from echo"))

        // 结果必须回填给模型的第二轮请求，历史协议合法。
        val toolResult = provider.requests[1].messages
            .flatMap { it.blocks }
            .filterIsInstance<Block.ToolResult>()
            .single()
        assertTrue(toolResult.content.contains("ok from echo"))
        assertEquals(null, agent.validatePairing(agent.history()))
    }

    @Test
    fun `remote tools are fail closed and require approval`() = runTest {
        val mcp = manager(listOf(cfg("a")))
        mcp.startAll()

        val provider = ScriptedProvider(
            ScriptedProvider.tools("mcp__a__echo" to Json.obj("text" to Json.Str("x"))),
            ScriptedProvider.text("ok"),
        )
        // 默认策略：无审批者 → 应被拒绝，绝不执行。
        val agent = loop(provider, mcpManager = mcp, broker = PermissionBroker())

        val events = agent.run("go").toList()

        assertEquals(1, events.count { it is AgentEvent.ToolDenied })
        assertFalse(events.any { it is AgentEvent.ToolStarted })
    }

    @Test
    fun `a failing server never breaks the turn`() = runTest {
        val good = LinkedHashMap<String, ScriptedMcpServer>()
        val mcp = McpManager(
            listOf(cfg("good"), cfg("bad")),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            transportFactory = { c ->
                if (c.id == "bad") {
                    ScriptedMcpServer(name = c.id, tools = listOf(desc("echo")), initError = "boom")
                } else {
                    ScriptedMcpServer(name = c.id, serverName = "srv-good", tools = listOf(desc("echo")))
                        .also { good[c.id] = it }
                }
            },
        )
        mcp.startAll()

        val provider = ScriptedProvider(ScriptedProvider.text("ok"))
        val agent = loop(provider, mcpManager = mcp)

        val events = agent.run("hi").toList()

        assertTrue(events.any { it is AgentEvent.Completed })
        val req = provider.requests.single()
        assertTrue(req.tools.any { it.name == "mcp__good__echo" })
        assertFalse("failed server's tools must not be advertised", req.tools.any { it.name.startsWith("mcp__bad__") })
        assertFalse(req.system.contains("bad"))
    }
}
