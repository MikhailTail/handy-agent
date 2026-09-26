package dev.pocket.agent.core.mcp

import dev.pocket.agent.core.json.Json
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StdioMcpTransportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun transport(
        factory: McpProcessFactory,
        command: List<String> = listOf("fake-server", "--flag"),
        dir: File? = null,
        env: Map<String, String> = emptyMap(),
    ) = StdioMcpTransport("fake", command, dir, env, factory)

    /** 轮询等待客户端写出至少 n 行，避免依赖 sleep 的固定时长。 */
    private suspend fun awaitLines(proc: FakeMcpProcess, n: Int): List<String> {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val lines = proc.sentLines()
            if (lines.size >= n) return lines
            delay(5)
        }
        error("timed out waiting for $n stdin lines; saw ${proc.sentLines()}")
    }

    // ------------------------------------------------------------------ 启动

    @Test
    fun `start passes command working dir and env to the factory once`() = runBlocking {
        val dir = tmp.newFolder("ws")
        val f = FakeProcessFactory()
        val t = transport(f, listOf("node", "srv.js"), dir, mapOf("TOKEN" to "x"))
        try {
            t.start()
            t.start() // 第二次必须是空操作
            assertEquals(1, f.startCount)
            assertEquals(listOf("node", "srv.js"), f.lastCommand)
            assertEquals(dir, f.lastWorkingDir)
            assertEquals(mapOf("TOKEN" to "x"), f.lastEnv)
        } finally {
            t.close()
        }
    }

    @Test
    fun `a factory failure is wrapped into an McpProtocolException`() = runBlocking {
        val f = FakeProcessFactory(failWith = IllegalStateException("no such binary"))
        val t = transport(f)
        val e = runCatching { t.start() }.exceptionOrNull()
        assertTrue(e is McpProtocolException)
        assertTrue(e!!.message!!.contains("no such binary"))
        assertTrue(e.message!!.contains("fake"))
    }

    @Test
    fun `an empty command is rejected`() = runBlocking {
        val t = transport(FakeProcessFactory(), command = emptyList())
        val e = runCatching { t.start() }.exceptionOrNull()
        assertTrue("was: $e", e is IllegalArgumentException)
    }

    @Test
    fun `starting after close is refused`() = runBlocking {
        val t = transport(FakeProcessFactory())
        t.close()
        val e = runCatching { t.start() }.exceptionOrNull()
        assertTrue("was: $e", e is IllegalStateException)
    }

    // ------------------------------------------------------------------ 行框定

    @Test
    fun `stdout lines are framed and blank lines skipped`() = runBlocking {
        val f = FakeProcessFactory()
        val t = transport(f)
        try {
            t.start()
            f.proc.serverSays("first")
            f.proc.serverSaysRaw("\n".toByteArray())
            f.proc.serverSays("second")
            val lines = withTimeout(3_000) { t.incoming.take(2).toList() }
            assertEquals(listOf("first", "second"), lines)
        } finally {
            t.close()
        }
    }

    @Test
    fun `stderr is kept out of the protocol channel`() = runBlocking {
        val f = FakeProcessFactory()
        val t = transport(f)
        try {
            t.start()
            f.proc.serverLogs("warning: deprecated")
            f.proc.serverSays("payload")
            // 若日志混进 stdout，这里会先收到 "warning..."。
            val lines = withTimeout(3_000) { t.incoming.take(1).toList() }
            assertEquals(listOf("payload"), lines)
            withTimeout(3_000) {
                while (!t.diagnostics().contains("warning: deprecated")) delay(5)
            }
        } finally {
            t.close()
        }
    }

    // ------------------------------------------------------------------ 发送

    @Test
    fun `send terminates the message with exactly one newline`() = runBlocking {
        val f = FakeProcessFactory()
        val t = transport(f)
        try {
            t.start()
            t.send("""{"jsonrpc":"2.0"}""")
            assertEquals(listOf("""{"jsonrpc":"2.0"}"""), awaitLines(f.proc, 1))
        } finally {
            t.close()
        }
    }

    @Test
    fun `send before start fails`() = runBlocking {
        val t = transport(FakeProcessFactory())
        val e = runCatching { t.send("{}") }.exceptionOrNull()
        assertTrue(e is McpProtocolException)
        assertTrue(e!!.message!!.contains("not started"))
    }

    // ------------------------------------------------------------------ 关闭

    @Test
    fun `close destroys the process and is idempotent`() = runBlocking {
        val f = FakeProcessFactory()
        val t = transport(f)
        t.start()
        t.close()
        t.close()
        assertTrue(f.proc.destroyed)
        assertFalse(f.proc.isAlive())
    }

    @Test
    fun `closing stdout ends the incoming flow`() = runBlocking {
        val f = FakeProcessFactory()
        val t = transport(f)
        try {
            t.start()
            f.proc.serverSays("only")
            val lines = withTimeout(3_000) { t.incoming.take(1).toList() }
            assertEquals(listOf("only"), lines)
            f.proc.closeStdout()
            // 流应以结束（而非异常）表示对端关闭。
            val rest = withTimeout(3_000) { t.incoming.toList() }
            assertTrue(rest.isEmpty())
        } finally {
            t.close()
        }
    }

    // ------------------------------------------------------------------ 与 McpClient 端到端

    @Test
    fun `a full handshake and tool call work over the stdio transport`() = runBlocking {
        val f = FakeProcessFactory()
        val t = transport(f, command = listOf("fake-server"))
        val client = McpClient(t)
        try {
            client.start()

            // --- initialize
            val initJob = async { client.initialize() }
            val initReq = Json.parse(awaitLines(f.proc, 1).last())
            assertEquals("initialize", initReq.str("method"))
            f.proc.serverSays(
                """{"jsonrpc":"2.0","id":${initReq.long("id")},"result":{"serverInfo":{"name":"fs","version":"2.0"}}}"""
            )
            assertEquals("fs", initJob.await().name)

            // 握手第二步
            val initialized = Json.parse(awaitLines(f.proc, 2).last())
            assertEquals("notifications/initialized", initialized.str("method"))

            // --- tools/list
            val listJob = async { client.listTools() }
            val listReq = Json.parse(awaitLines(f.proc, 3).last())
            assertEquals("tools/list", listReq.str("method"))
            f.proc.serverSays(
                """{"jsonrpc":"2.0","id":${listReq.long("id")},"result":{"tools":[{"name":"read","description":"r","inputSchema":{"type":"object"}}]}}"""
            )
            assertEquals(listOf("read"), listJob.await().map { it.name })

            // --- tools/call
            val callJob = async { client.callTool("read", Json.obj("path" to Json.Str("a.txt"))) }
            val callReq = Json.parse(awaitLines(f.proc, 4).last())
            assertEquals("read", callReq.obj("params")!!.str("name"))
            f.proc.serverSays(
                """{"jsonrpc":"2.0","id":${callReq.long("id")},"result":{"content":[{"type":"text","text":"file body"}]}}"""
            )
            assertEquals("file body", callJob.await().content)
        } finally {
            client.close()
        }
    }
}
