package dev.pocket.agent.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConfigTest {

    @Test
    fun `parses the mcpServers wrapper shape`() {
        val result = McpConfigParser.parse(
            """
            {"mcpServers": {
               "fs": {"command": "npx", "args": ["-y", "server-fs", "/tmp"]},
               "git": {"command": "uvx", "args": ["mcp-git"], "enabled": false}
            }}
            """.trimIndent()
        )
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf("fs", "git"), result.servers.map { it.id })
        assertEquals(listOf("npx", "-y", "server-fs", "/tmp"), result.servers[0].command)
        assertTrue(result.servers[0].enabled)
        assertFalse(result.servers[1].enabled)
    }

    @Test
    fun `parses a bare map without the wrapper`() {
        val result = McpConfigParser.parse("""{"solo": {"command": "mcp-solo"}}""")
        assertEquals(listOf("solo"), result.servers.map { it.id })
    }

    @Test
    fun `env values are read for strings numbers and booleans`() {
        val result = McpConfigParser.parse(
            """{"s": {"command": "x", "env": {"TOKEN": "secret", "PORT": 3000, "DEBUG": true, "NESTED": {"a":1}}}}"""
        )
        val env = result.servers.single().env
        assertEquals("secret", env["TOKEN"])
        assertEquals("3000", env["PORT"])
        assertEquals("true", env["DEBUG"])
        assertFalse("nested objects are not env values", env.containsKey("NESTED"))
    }

    @Test
    fun `custom timeout is honored and non-positive falls back to default`() {
        val result = McpConfigParser.parse(
            """{"a": {"command": "x", "timeoutMs": 1500}, "b": {"command": "y", "timeoutMs": 0}}"""
        )
        assertEquals(1500L, result.servers.first { it.id == "a" }.requestTimeoutMs)
        assertEquals(McpClient.DEFAULT_TIMEOUT_MS, result.servers.first { it.id == "b" }.requestTimeoutMs)
    }

    @Test
    fun `a server without a command is reported but other servers survive`() {
        val result = McpConfigParser.parse("""{"ok": {"command": "x"}, "broken": {"args": ["y"]}}""")
        assertEquals(listOf("ok"), result.servers.map { it.id })
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("missing 'command'"))
    }

    @Test
    fun `a url transport is rejected with an explicit message`() {
        val result = McpConfigParser.parse("""{"remote": {"url": "https://example.com/mcp"}}""")
        assertTrue(result.servers.isEmpty())
        assertTrue(result.errors.single().contains("'url' transport is not supported"))
    }

    @Test
    fun `a non object server entry is reported`() {
        val result = McpConfigParser.parse("""{"s": 42}""")
        assertTrue(result.servers.isEmpty())
        assertTrue(result.errors.single().contains("entry must be an object"))
    }

    @Test
    fun `invalid json yields a single error and no servers`() {
        val result = McpConfigParser.parse("{ not json")
        assertTrue(result.servers.isEmpty())
        assertTrue(result.errors.single().contains("invalid JSON"))
    }

    @Test
    fun `a non object root is rejected`() {
        val result = McpConfigParser.parse("[1]")
        assertTrue(result.errors.single().contains("must be a JSON object"))
    }

    @Test
    fun `empty input is distinguishable from a parse failure`() {
        assertTrue(McpConfigParser.parse("{}").isEmpty)
        assertFalse(McpConfigParser.parse("{bad").isEmpty)
    }
}
