package dev.pocket.agent.core.mcp

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.tool.Tool
import dev.pocket.agent.core.tool.ToolContext
import dev.pocket.agent.core.tool.ToolOutcome
import dev.pocket.agent.core.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpManagerTest {

    private fun desc(name: String) = McpToolDescriptor(name, "d", Json.obj("type" to Json.Str("object")))

    /** 每个 server id 得到一个脚本化内存服务端；[broken] 里的 id 会在 initialize 时失败。 */
    private fun manager(
        configs: List<McpServerConfig>,
        broken: Set<String> = emptySet(),
        tools: List<McpToolDescriptor> = listOf(desc("alpha"), desc("beta")),
        servers: MutableMap<String, ScriptedMcpServer> = LinkedHashMap(),
    ): McpManager = McpManager(
        configs,
        transportFactory = { cfg ->
            ScriptedMcpServer(
                name = cfg.id,
                serverName = "srv-${cfg.id}",
                tools = tools,
                initError = if (cfg.id in broken) "boom: cannot init" else null,
            ).also { servers[cfg.id] = it }
        },
    )

    private fun cfg(id: String, enabled: Boolean = true) =
        McpServerConfig(id = id, command = listOf("fake-$id"), enabled = enabled)

    // ------------------------------------------------------------------ 启动

    @Test
    fun `no tools are exposed before startAll`() {
        val m = manager(listOf(cfg("a")))
        assertTrue(m.tools.isEmpty())
        assertEquals(0, m.connectedCount)
        assertEquals("", m.promptSection())
    }

    @Test
    fun `startAll connects every server and bridges its tools`() = runBlocking {
        val m = manager(listOf(cfg("a"), cfg("b")))
        val statuses = m.startAll()

        assertEquals(2, statuses.size)
        assertTrue(statuses.all { it.connected })
        assertEquals(2, m.connectedCount)
        assertEquals(listOf("srv-a", "srv-b"), statuses.map { it.serverName })
        assertEquals(listOf("mcp__a__alpha", "mcp__a__beta", "mcp__b__alpha", "mcp__b__beta"), m.tools.map { it.name })
        assertEquals(2, statuses.single { it.id == "a" }.toolCount)

        val section = m.promptSection()
        assertTrue(section.startsWith("## MCP servers"))
        assertTrue(section.contains("a (srv-a): mcp__a__alpha, mcp__a__beta"))
    }

    @Test
    fun `a disabled server is never started`() = runBlocking {
        val m = manager(listOf(cfg("a"), cfg("off", enabled = false)))
        val statuses = m.startAll()
        assertEquals(listOf("a"), statuses.map { it.id })
        assertTrue(m.tools.all { it.name.startsWith("mcp__a__") })
    }

    @Test
    fun `one failing server does not take down the others`() = runBlocking {
        val m = manager(listOf(cfg("good"), cfg("bad")), broken = setOf("bad"))
        val statuses = m.startAll()

        assertEquals(2, statuses.size)
        val good = statuses.single { it.id == "good" }
        val bad = statuses.single { it.id == "bad" }
        assertTrue(good.connected)
        assertFalse(bad.connected)
        assertFalse(bad.failed.not()) // failed == true
        assertTrue(bad.error!!.contains("boom"))
        assertEquals(1, m.connectedCount)
        // 失败服务端的工具一个都不能进表。
        assertTrue(m.tools.none { it.name.startsWith("mcp__bad__") })
        assertTrue(m.promptSection().contains("good"))
        assertFalse(m.promptSection().contains("bad"))
    }

    @Test
    fun `a server that fails during tools_list is also isolated`() = runBlocking {
        val m = McpManager(
            listOf(cfg("broken")),
            transportFactory = { cfg -> ScriptedMcpServer(cfg.id, toolsError = "no tools for you") },
        )
        val status = m.startAll().single()
        assertFalse(status.connected)
        assertTrue(status.error!!.contains("no tools for you"))
        assertTrue(m.tools.isEmpty())
    }

    @Test
    fun `a transport that cannot even be created is reported`() = runBlocking {
        val m = McpManager(
            listOf(cfg("x")),
            transportFactory = { throw IllegalStateException("no socket") },
        )
        val status = m.startAll().single()
        assertFalse(status.connected)
        assertTrue(status.error!!.contains("cannot create transport"))
    }

    // ------------------------------------------------------------------ 合并 / 关闭

    /** 最小可注册的占位工具，用于人为制造重名。 */
    private fun stub(name: String) = object : Tool {
        override val name = name
        override val description = "stub"
        override val inputSchema = Json.obj("type" to Json.Str("object"))
        override suspend fun execute(input: Json, ctx: ToolContext) = ToolOutcome.ok("stub")
    }

    @Test
    fun `combining with a registry skips name conflicts and reports them`() = runBlocking {
        val m = manager(listOf(cfg("a")), tools = listOf(desc("read"), desc("fresh")))
        m.startAll()
        // 桥接名带 mcp__<server>__ 前缀，天然不会与内置短名（read/write/...）冲突，
        // 所以冲突只能来自「base 里已存在同名工具」——显式构造它，才能真正走到跳过分支。
        val base = ToolRegistry.builtin().register(stub("mcp__a__read"))
        val merge = m.combineRegistry(base)
        // 重名者必须被跳过而不是顶掉已有实现。
        assertEquals(listOf("mcp__a__read"), merge.skipped)
        assertTrue(merge.registry.names().contains("mcp__a__fresh"))
        assertEquals(base.size + 1, merge.registry.size)
    }

    @Test
    fun `close is idempotent and clears bridges`() = runBlocking {
        val servers = LinkedHashMap<String, ScriptedMcpServer>()
        val m = manager(listOf(cfg("a")), servers = servers)
        m.startAll()
        assertTrue(m.tools.isNotEmpty())
        m.close()
        assertTrue(m.tools.isEmpty())
        assertTrue(servers["a"]!!.closed)
        m.close() // 第二次必须不抛异常
        assertTrue(m.tools.isEmpty())
    }
}
