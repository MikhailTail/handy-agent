package dev.mikhailtail.handyagent.core.provider

import dev.mikhailtail.handyagent.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallAccumulatorTest {

    @Test
    fun `single call with fragmented arguments`() {
        val acc = ToolCallAccumulator()
        assertNotNull(acc.accept(0, "call_1", "read_file", "{\"pa"))
        acc.accept(0, null, null, "th\":\"a.txt\"}")
        val built = acc.build()
        assertEquals(1, built.calls.size)
        val c = built.calls[0]
        assertEquals("call_1", c.id)
        assertEquals("read_file", c.name)
        assertEquals("a.txt", c.input.str("path"))
        assertTrue(built.issues.isEmpty())
    }

    @Test
    fun `parallel calls keep index order even when interleaved`() {
        val acc = ToolCallAccumulator()
        acc.accept(1, "b", "tool_b", "{\"x\":2}")
        acc.accept(0, "a", "tool_a", "{\"x\":1}")
        val calls = acc.build().calls
        assertEquals(listOf("tool_a", "tool_b"), calls.map { it.name })
        assertEquals(1.0, calls[0].input.double("x")!!, 0.0)
        assertEquals(2.0, calls[1].input.double("x")!!, 0.0)
    }

    @Test
    fun `empty arguments become empty object`() {
        val acc = ToolCallAccumulator()
        acc.accept(0, "c1", "list_dir", null)
        assertEquals(Json.Obj(emptyMap()), acc.build().calls.single().input)
    }

    @Test
    fun `missing id gets deterministic fallback`() {
        val acc = ToolCallAccumulator()
        acc.accept(3, null, "foo", "{}")
        assertEquals("call_3", acc.build().calls.single().id)
    }

    @Test
    fun `malformed json is reported as issue and degraded to empty object`() {
        val acc = ToolCallAccumulator()
        acc.accept(0, "c1", "edit", "{\"path\": broken")
        val built = acc.build()
        assertEquals(1, built.issues.size)
        assertTrue(built.issues[0].contains("edit"))
        assertEquals(Json.Obj(emptyMap()), built.calls.single().input)
    }

    @Test
    fun `nested json arguments survive round trip`() {
        val acc = ToolCallAccumulator()
        acc.accept(0, "c1", "todo_write", "{\"items\":[{\"text\":\"a\",\"done\":false}]}")
        val input = acc.build().calls.single().input
        assertEquals("a", input.array("items")[0].str("text"))
        assertEquals(false, input.array("items")[0].bool("done"))
    }

    @Test
    fun `started event fires once per call`() {
        val acc = ToolCallAccumulator()
        assertNotNull(acc.accept(0, "c1", "bash", "{\"c"))
        assertEquals(null, acc.accept(0, null, null, "md"))
        assertEquals(null, acc.accept(0, "c1", "bash", "md\"}"))
        assertFalse(acc.hasUnnamedCalls())
    }

    @Test
    fun `clear resets state`() {
        val acc = ToolCallAccumulator()
        acc.accept(0, "c1", "bash", "{}")
        acc.clear()
        assertTrue(acc.build().calls.isEmpty())
        assertTrue(acc.isEmpty())
    }

    @Test
    fun `unnamed call is detected`() {
        val acc = ToolCallAccumulator()
        acc.accept(0, "c1", null, "{")
        assertTrue(acc.hasUnnamedCalls())
        assertTrue(acc.build().calls.isEmpty())
    }
}
