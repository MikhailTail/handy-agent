package dev.mikhailtail.handyagent.core.mcp

import dev.mikhailtail.handyagent.core.json.Json
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class McpClientTest {

    private fun reply(id: Long, result: String): String =
        """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    private fun error(id: Long, code: Int, message: String): String =
        """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"}}"""

    private fun idOf(request: String): Long = Json.parse(request).long("id")!!

    // ------------------------------------------------------------------ 握手

    @Test
    fun `initialize performs the handshake and records server info`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t, clientName = "pocket", clientVersion = "9.9")
        try {
            client.start()
            val job = async { client.initialize() }

            val request = Json.parse(t.awaitSent())
            assertEquals("initialize", request.str("method"))
            assertEquals("2.0", request.str("jsonrpc"))
            assertEquals("pocket", request.obj("params")!!.obj("clientInfo")!!.str("name"))
            assertEquals("9.9", request.obj("params")!!.obj("clientInfo")!!.str("version"))

            t.feed(
                reply(
                    idOf(request.encode()),  // 重新编码仍是同一条
                    """{"protocolVersion":"2024-11-05","serverInfo":{"name":"fs","version":"1.0"},"capabilities":{"tools":{}}}"""
                )
            )
            val info = job.await()
            assertEquals("fs", info.name)
            assertEquals("1.0", info.version)
            assertEquals("2024-11-05", info.protocolVersion)
            assertTrue(info.capabilities != null)

            // 握手第二步：initialized 通知必须发出，否则部分服务端会拒绝后续请求。
            val initialized = Json.parse(t.awaitSent())
            assertEquals("notifications/initialized", initialized.str("method"))
            assertNull(initialized["id"])
        } finally {
            client.close()
        }
    }

    @Test
    fun `start is idempotent`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t)
        try {
            client.start()
            client.start()
            assertEquals(1, t.startCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun `server info falls back to the transport name when the server omits it`() = runBlocking<Unit> {
        val t = FakeMcpTransport(name = "fallback")
        val client = McpClient(t)
        try {
            client.start()
            val job = async { client.initialize() }
            val req = t.awaitSent()
            t.feed(reply(idOf(req), """{"protocolVersion":"2025-06-18"}"""))
            val info = job.await()
            assertEquals("fallback", info.name)
            assertEquals("2025-06-18", info.protocolVersion)
            t.awaitSent() // notifications/initialized
        } finally {
            client.close()
        }
    }

    // ------------------------------------------------------------------ tools/list

    @Test
    fun `listTools follows the pagination cursor`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t)
        try {
            client.start()
            val job = async { client.listTools() }

            val first = Json.parse(t.awaitSent())
            assertEquals("tools/list", first.str("method"))
            assertNull("first page must not send a cursor", first.obj("params")!!["cursor"])
            t.feed(
                reply(
                    idOf(first.encode()),
                    """{"tools":[{"name":"read","description":"r","inputSchema":{"type":"object"}}],"nextCursor":"c1"}"""
                )
            )

            val second = Json.parse(t.awaitSent())
            assertEquals("c1", second.obj("params")!!.str("cursor"))
            t.feed(reply(idOf(second.encode()), """{"tools":[{"name":"write","description":"w","inputSchema":{}}]}"""))

            val tools = job.await()
            assertEquals(listOf("read", "write"), tools.map { it.name })
            assertEquals("r", tools[0].description)
            assertEquals("object", tools[0].inputSchema.str("type"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `a looping nextCursor cannot cause an infinite paginate`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t, maxListPages = 3)
        try {
            client.start()
            val job = async { client.listTools() }
            repeat(3) { i ->
                val req = Json.parse(t.awaitSent())
                // 服务端永远回同一个 cursor —— 客户端必须靠页数上限兜住。
                t.feed(reply(idOf(req.encode()), """{"tools":[{"name":"t$i"}],"nextCursor":"same"}"""))
            }
            val tools = withTimeout(2_000) { job.await() }
            assertEquals(3, tools.size)
        } finally {
            client.close()
        }
    }

    @Test
    fun `tool entries without a name are skipped`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t)
        try {
            client.start()
            val job = async { client.listTools() }
            val req = t.awaitSent()
            t.feed(reply(idOf(req), """{"tools":[{"description":"nameless"},{"name":"ok"}]}"""))
            assertEquals(listOf("ok"), job.await().map { it.name })
        } finally {
            client.close()
        }
    }

    // ------------------------------------------------------------------ tools/call

    @Test
    fun `callTool sends the remote name and arguments and renders text content`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t)
        try {
            client.start()
            val job = async { client.callTool("read_file", Json.obj("path" to Json.Str("a.txt"))) }
            val req = Json.parse(t.awaitSent())
            assertEquals("tools/call", req.str("method"))
            assertEquals("read_file", req.obj("params")!!.str("name"))
            assertEquals("a.txt", req.obj("params")!!.obj("arguments")!!.str("path"))

            t.feed(reply(idOf(req.encode()), """{"content":[{"type":"text","text":"hello"}]}"""))
            val result = job.await()
            assertEquals("hello", result.content)
            assertFalse(result.isError)
        } finally {
            client.close()
        }
    }

    @Test
    fun `a tool level failure is surfaced as isError not an exception`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t)
        try {
            client.start()
            val job = async { client.callTool("boom", Json.obj()) }
            val req = t.awaitSent()
            t.feed(reply(idOf(req), """{"content":[{"type":"text","text":"kaboom"}],"isError":true}"""))
            val result = job.await()
            assertTrue(result.isError)
            assertEquals("kaboom", result.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `structured content is the fallback when there is no text part`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t)
        try {
            client.start()
            val job = async { client.callTool("count", Json.obj()) }
            val req = t.awaitSent()
            t.feed(reply(idOf(req), """{"structuredContent":{"n":42}}"""))
            assertEquals("""{"n":42}""", job.await().content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `renderContent covers text image audio resource and unknown parts`() {
        val list = (Json.parse(
            """
            [{"type":"text","text":"line1"},
             {"type":"image","mimeType":"image/png"},
             {"type":"audio","mimeType":"audio/wav"},
             {"type":"resource","resource":{"uri":"file:///x"}},
             {"type":"weird"}]
            """.trimIndent()
        ) as Json.Arr).items
        val rendered = McpClient.renderContent(list)
        assertEquals(
            listOf("line1", "[image image/png]", "[audio audio/wav]", "[resource file:///x]", "[weird]"),
            rendered.split("\n"),
        )
    }

    @Test
    fun `renderContent of nothing is empty`() {
        assertEquals("", McpClient.renderContent(emptyList()))
    }

    // ------------------------------------------------------------------ 失败路径

    @Test
    fun `a protocol error response becomes an McpProtocolException with the code`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t)
        try {
            client.start()
            val job = async { runCatching { client.listTools() } }
            val req = t.awaitSent()
            t.feed(error(idOf(req), JsonRpcErrorCode.INVALID_PARAMS, "bad cursor"))
            val e = job.await().exceptionOrNull()
            assertTrue(e is McpProtocolException)
            assertEquals(JsonRpcErrorCode.INVALID_PARAMS, (e as McpProtocolException).code)
            assertTrue(e.message!!.contains("bad cursor"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `a request times out when the server never answers`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t, requestTimeoutMs = 150)
        try {
            client.start()
            val e = runCatching { client.initialize() }.exceptionOrNull()
            assertTrue(e is McpProtocolException)
            assertTrue("was: ${e!!.message}", e.message!!.contains("timed out"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `closing the connection fails every pending request with stderr diagnostics`() = runBlocking {
        val t = FakeMcpTransport(stderrText = "boom: cannot open db")
        val client = McpClient(t, requestTimeoutMs = 5_000)
        try {
            client.start()
            val job = async { runCatching { client.initialize() } }
            t.awaitSent()
            t.endIncoming() // 对端关闭
            val e = job.await().exceptionOrNull()
            assertTrue(e is McpProtocolException)
            assertTrue(e!!.message!!.contains("closed the connection"))
            assertTrue("diagnostics must be attached: ${e.message}", e.message!!.contains("cannot open db"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `a response for an unknown id is ignored and the next request still works`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t)
        try {
            client.start()
            t.feed(reply(9_999, """{"tools":[]}"""))
            val job = async { client.listTools() }
            val req = t.awaitSent()
            t.feed(reply(idOf(req), """{"tools":[{"name":"ok"}]}"""))
            assertEquals(listOf("ok"), job.await().map { it.name })
        } finally {
            client.close()
        }
    }

    @Test
    fun `malformed inbound lines are dropped without breaking the loop`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t)
        try {
            client.start()
            t.feed("this is not json")
            t.feed("""[1,2,3]""")
            val job = async { client.listTools() }
            val req = t.awaitSent()
            t.feed(reply(idOf(req), """{"tools":[{"name":"survived"}]}"""))
            assertEquals(listOf("survived"), job.await().map { it.name })
        } finally {
            client.close()
        }
    }

    // ------------------------------------------------------------------ 反向请求 / 通知

    @Test
    fun `a server request is answered with method not found`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t)
        try {
            client.start()
            t.feed("""{"jsonrpc":"2.0","id":"srv-1","method":"sampling/createMessage","params":{}}""")
            val replyLine = Json.parse(t.awaitSent())
            assertEquals("srv-1", replyLine["id"]?.asStringOrNull())
            assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND, replyLine.obj("error")!!.int("code"))
            assertNull("a reply must not be confused with a request", replyLine.str("method"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `notifications are emitted on the notification flow`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t)
        try {
            client.start()
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) { client.notifications.first() }
            }
            t.feed("""{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info"}}""")
            val notif = job.await()
            assertEquals("notifications/message", notif.method)
            assertEquals("info", notif.params!!.str("level"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `closing fails a request that is still in flight`() = runBlocking {
        val t = FakeMcpTransport()
        val client = McpClient(t, requestTimeoutMs = 5_000)
        client.start()
        val job = async { runCatching { client.initialize() } }
        t.awaitSent()
        client.close()
        val e = job.await().exceptionOrNull()
        assertTrue(e is McpProtocolException)
        assertTrue(e!!.message!!.contains("closed"))
    }
    // ------------------------------------------------------------------ 调度器契约（回归）

    /**
     * 记录型调度器：用来证明「读循环确实跑在注入的调度器上」。
     * 委托给 [StandardTestDispatcher]（它允许被包装），每次派发都计数。
     */
    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {

        val dispatches = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    /**
     * 回归：读循环必须使用**注入的** dispatcher，而不是内部写死的 `Dispatchers.Default`。
     *
     * 背景：`withTimeout` 用的是调用方时钟，读循环跑在自己的 scope 上。若两者不是同一个
     * 调度器，`runTest` 的虚拟时间会抢在真实线程投递应答之前把时间推到超时点，
     * 造成随机「initialize timed out」的 flake。此测试把注入的调度器当作契约钉死。
     */
    @Test
    fun `the read loop runs on the injected dispatcher`() = runTest {
        val t = FakeMcpTransport()
        val rec = RecordingDispatcher(StandardTestDispatcher(testScheduler))
        val client = McpClient(t, dispatcher = rec, requestTimeoutMs = 20_000)
        try {
            client.start()
            val job = async { client.initialize() }

            val req = t.awaitSent()
            t.feed(reply(idOf(req), """{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"s","version":"1"}}"""))

            assertEquals("s", job.await().name)
            assertTrue(
                "read loop must be dispatched on the injected dispatcher",
                rec.dispatches.get() > 0,
            )
        } finally {
            client.close()
        }
    }
}
