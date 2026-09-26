package dev.pocket.agent.core.mcp

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.sandbox.PathJail
import dev.pocket.agent.core.sandbox.ProcessShell
import dev.pocket.agent.core.tool.ToolContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpToolBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx() = ToolContext(PathJail(tmp.root), ProcessShell(tmp.root, timeoutMs = 5_000))

    private fun descriptor(name: String = "read_file", description: String = "reads a file") =
        McpToolDescriptor(name, description, Json.obj("type" to Json.Str("object")))

    // ------------------------------------------------------------------ 命名

    @Test
    fun `names are namespaced by server id`() {
        assertEquals("mcp__fs__read_file", mcpToolName("fs", "read_file"))
    }

    @Test
    fun `characters illegal in provider function names are sanitized`() {
        assertEquals("mcp__git__repo_status", mcpToolName("git", "repo.status"))
        assertEquals("mcp__git__a_b_c", mcpToolName("git", "a/b c"))
        // 结果必须只含 [A-Za-z0-9_-]
        val name = mcpToolName("srv", "weird::name!")
        assertTrue(name, name.all { it.isLetterOrDigit() || it == '_' || it == '-' })
    }

    @Test
    fun `names are truncated to the provider limit`() {
        val name = mcpToolName("server", "x".repeat(200))
        assertEquals(64, name.length)
        assertTrue(name.startsWith("mcp__server__"))
    }

    // ------------------------------------------------------------------ 元数据

    @Test
    fun `bridge exposes the remote schema and is never read only`() {
        val server = ScriptedMcpServer("fs", tools = listOf(descriptor()))
        val client = McpClient(server)
        val bridge = McpToolBridge(client, descriptor(), "fs")
        assertEquals("mcp__fs__read_file", bridge.name)
        assertEquals("reads a file", bridge.description)
        assertEquals("object", bridge.inputSchema.str("type"))
        assertFalse("third-party side effects are unverifiable", bridge.readOnly)
        assertEquals("read_file", bridge.remoteName)
        assertEquals("mcp__fs__read_file", bridge.spec().name)
    }

    @Test
    fun `a blank remote description gets a usable fallback`() {
        val bridge = McpToolBridge(McpClient(ScriptedMcpServer("fs")), descriptor(description = ""), "fs")
        assertTrue(bridge.description.contains("read_file"))
        assertTrue(bridge.description.contains("fs"))
    }

    // ------------------------------------------------------------------ 执行

    @Test
    fun `execute forwards the remote name and arguments and returns content`() = runBlocking {
        val desc = descriptor()
        val server = ScriptedMcpServer(
            "fs",
            tools = listOf(desc),
            results = mapOf("read_file" to McpCallResult("file body", false)),
        )
        val client = McpClient(server)
        client.start()
        val bridge = McpToolBridge(client, desc, "fs")
        try {
            val out = bridge.execute(Json.obj("path" to Json.Str("a.txt")), ctx())
            assertFalse(out.isError)
            assertEquals("file body", out.content)
            // 远端收到的是原始名，而不是本地命名空间化后的名字。
            assertEquals(1, server.calls.size)
            assertEquals("read_file", server.calls.single().first)
            assertEquals("a.txt", server.calls.single().second.str("path"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `a remote tool failure maps to an error outcome`() = runBlocking {
        val desc = descriptor("boom")
        val server = ScriptedMcpServer(
            "fs",
            tools = listOf(desc),
            results = mapOf("boom" to McpCallResult("kaboom", true)),
        )
        val client = McpClient(server)
        client.start()
        try {
            val out = McpToolBridge(client, desc, "fs").execute(Json.obj(), ctx())
            assertTrue(out.isError)
            assertEquals("kaboom", out.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `a non object input is sent as an empty object`() = runBlocking {
        val desc = descriptor()
        val server = ScriptedMcpServer("fs", tools = listOf(desc))
        val client = McpClient(server)
        client.start()
        try {
            McpToolBridge(client, desc, "fs").execute(Json.Str("oops"), ctx())
            assertEquals(Json.obj(), server.calls.single().second)
        } finally {
            client.close()
        }
    }
}
