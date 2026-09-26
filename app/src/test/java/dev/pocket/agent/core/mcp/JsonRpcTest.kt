package dev.pocket.agent.core.mcp

import dev.pocket.agent.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcTest {

    // ------------------------------------------------------------------ 出站编码

    @Test
    fun `request carries version id and method`() {
        val encoded = JsonRpc.request(7, "tools/list", Json.obj()).encode()
        assertEquals("""{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{}}""", encoded)
    }

    @Test
    fun `request omits params when null`() {
        assertEquals("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""", JsonRpc.request(1, "initialize").encode())
    }

    @Test
    fun `notification has no id`() {
        val encoded = JsonRpc.notification("notifications/initialized").encode()
        assertEquals("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", encoded)
    }

    @Test
    fun `error response echoes the id verbatim`() {
        val encoded = JsonRpc.errorResponse(Json.Str("abc"), JsonRpcErrorCode.METHOD_NOT_FOUND, "nope").encode()
        assertEquals(
            """{"jsonrpc":"2.0","id":"abc","error":{"code":-32601,"message":"nope"}}""",
            encoded,
        )
    }

    // ------------------------------------------------------------------ 入站解析

    @Test
    fun `parses a successful response`() {
        val msg = JsonRpc.parseIncoming("""{"jsonrpc":"2.0","id":3,"result":{"ok":true}}""")
        assertTrue(msg is IncomingMessage.Response)
        val r = msg as IncomingMessage.Response
        assertEquals(3L, r.id)
        assertNull(r.error)
        assertEquals(true, r.result?.bool("ok"))
    }

    @Test
    fun `accepts a string numeric id`() {
        val r = JsonRpc.parseIncoming("""{"jsonrpc":"2.0","id":"12","result":null}""") as IncomingMessage.Response
        assertEquals(12L, r.id)
    }

    @Test
    fun `parses an error response into a fault`() {
        val r = JsonRpc.parseIncoming(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"bad params","data":{"x":1}}}"""
        ) as IncomingMessage.Response
        assertEquals(-32602, r.error!!.code)
        assertEquals("bad params", r.error!!.message)
        assertEquals(1, r.error!!.data?.int("x"))
        assertTrue(r.error!!.describe().startsWith("JSON-RPC error -32602"))
    }

    @Test
    fun `a method with an id is a server request`() {
        val m = JsonRpc.parseIncoming("""{"jsonrpc":"2.0","id":9,"method":"sampling/createMessage","params":{}}""")
        assertTrue(m is IncomingMessage.ServerRequest)
        val r = m as IncomingMessage.ServerRequest
        assertEquals("sampling/createMessage", r.method)
        assertEquals(Json.Num(9.0), r.id)
    }

    @Test
    fun `a method without an id is a notification`() {
        val m = JsonRpc.parseIncoming("""{"jsonrpc":"2.0","method":"notifications/message"}""")
        assertTrue(m is IncomingMessage.Notification)
        assertEquals("notifications/message", (m as IncomingMessage.Notification).method)
    }

    @Test
    fun `a null id is treated as absent so a notification stays a notification`() {
        val m = JsonRpc.parseIncoming("""{"jsonrpc":"2.0","id":null,"method":"notifications/x"}""")
        assertTrue(m is IncomingMessage.Notification)
    }

    // ------------------------------------------------------------------ 畸形输入

    @Test
    fun `rejects non json`() {
        val m = JsonRpc.parseIncoming("not json at all") as IncomingMessage.Malformed
        assertTrue(m.reason.contains("not valid JSON"))
    }

    @Test
    fun `rejects a non object top level`() {
        val m = JsonRpc.parseIncoming("[1,2,3]") as IncomingMessage.Malformed
        assertTrue(m.reason.contains("not an object"))
    }

    @Test
    fun `rejects a message with neither method nor id`() {
        val m = JsonRpc.parseIncoming("""{"jsonrpc":"2.0","result":1}""") as IncomingMessage.Malformed
        assertTrue(m.reason.contains("missing both"))
    }

    @Test
    fun `rejects a non numeric id`() {
        val m = JsonRpc.parseIncoming("""{"jsonrpc":"2.0","id":"abc","result":1}""") as IncomingMessage.Malformed
        assertTrue(m.reason.contains("non-numeric id"))
    }

    @Test
    fun `rejects a response with neither result nor error`() {
        val m = JsonRpc.parseIncoming("""{"jsonrpc":"2.0","id":1}""") as IncomingMessage.Malformed
        assertTrue(m.reason.contains("neither"))
    }

    @Test
    fun `a null result is a valid response not a malformed one`() {
        // result:null 与「缺少 result」语义不同，不能混为一谈。
        val msg = JsonRpc.parseIncoming("""{"jsonrpc":"2.0","id":4,"result":null}""")
        assertTrue(msg is IncomingMessage.Response)
        val r = msg as IncomingMessage.Response
        assertNull(r.error)
        assertTrue("result:null decodes to Json.Null", r.result!!.isNull)
    }
}
