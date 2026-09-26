package dev.pocket.agent.core.provider

import dev.pocket.agent.core.provider.sse.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    private fun parseAll(lines: List<String>): List<Pair<String?, String>> {
        val p = SseParser()
        val out = ArrayList<Pair<String?, String>>()
        for (l in lines) p.feedLine(l)?.let { out.add(it.event to it.data) }
        p.flush()?.let { out.add(it.event to it.data) }
        return out
    }

    @Test
    fun `data only event dispatches on blank line`() {
        val events = parseAll(listOf("data: hello", ""))
        assertEquals(1, events.size)
        assertEquals(null to "hello", events[0])
    }

    @Test
    fun `event name is captured and cleared`() {
        val events = parseAll(
            listOf(
                "event: delta", "data: {\"a\":1}", "",
                "data: plain", "",
            )
        )
        assertEquals(listOf("delta" to "{\"a\":1}", null to "plain"), events)
    }

    @Test
    fun `multi line data joins with newline`() {
        val events = parseAll(listOf("data: line1", "data: line2", ""))
        assertEquals("line1\nline2", events.single().second)
    }

    @Test
    fun `comments and heartbeat are ignored`() {
        assertEquals(emptyList<Pair<String?, String>>(), parseAll(listOf(": ping", "", ": keep-alive")))
    }

    @Test
    fun `colon without space keeps leading char`() {
        val events = parseAll(listOf("data:no-space", ""))
        assertEquals("no-space", events.single().second)
    }

    @Test
    fun `value with colon inside is preserved`() {
        val events = parseAll(listOf("data: {\"url\":\"http://x/y\"}", ""))
        assertEquals("{\"url\":\"http://x/y\"}", events.single().second)
    }

    @Test
    fun `field without colon yields empty data`() {
        val events = parseAll(listOf("data", ""))
        assertEquals(1, events.size)
        assertEquals("", events.single().second)
    }

    @Test
    fun `blank line without pending data emits nothing`() {
        val p = SseParser()
        assertNull(p.feedLine(""))
        assertNull(p.feedLine(""))
    }

    @Test
    fun `flush emits trailing event without blank line`() {
        val p = SseParser()
        assertNull(p.feedLine("data: tail"))
        assertEquals("tail", p.flush()?.data)
        assertNull(p.flush())
    }

    @Test
    fun `consecutive events are isolated`() {
        val events = parseAll(listOf("event: a", "data: 1", "", "event: b", "data: 2", ""))
        assertEquals(listOf("a" to "1", "b" to "2"), events)
    }

    @Test
    fun `id and retry do not pollute data`() {
        val events = parseAll(listOf("id: 42", "retry: 100", "data: x", ""))
        assertEquals("x", events.single().second)
    }

    @Test
    fun `data starting with brace is not treated as comment`() {
        val events = parseAll(listOf("data: {", ""))
        assertEquals("{", events.single().second)
    }
}
