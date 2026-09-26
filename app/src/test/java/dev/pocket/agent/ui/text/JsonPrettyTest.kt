package dev.pocket.agent.ui.text

import dev.pocket.agent.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPrettyTest {

    private val sample = Json.obj(
        "path" to Json.Str("a.txt"),
        "n" to Json.Num(3.0),
        "flag" to Json.Bool(true),
        "empty" to Json.Null,
    )

    @Test
    fun `compact encoding drops whitespace`() {
        assertEquals("""{"path":"a.txt","n":3,"flag":true,"empty":null}""", JsonPretty.compact(sample))
    }

    @Test
    fun `pretty encoding indents nested structures`() {
        val node = Json.obj("a" to Json.arr(Json.of(1), Json.obj("b" to Json.Str("c"))))
        val expected = """
            {
              "a": [
                1,
                {
                  "b": "c"
                }
              ]
            }
        """.trimIndent()
        assertEquals(expected, JsonPretty.pretty(node))
    }

    @Test
    fun `empty containers stay on one line`() {
        assertEquals("{}", JsonPretty.pretty(Json.obj()))
        assertEquals("[]", JsonPretty.pretty(Json.Arr(emptyList())))
    }

    @Test
    fun `integral doubles are not rendered with a decimal point`() {
        assertEquals("3", JsonPretty.formatNumber(3.0))
        assertEquals("2.5", JsonPretty.formatNumber(2.5))
    }

    @Test
    fun `one line truncates long payloads`() {
        val long = Json.obj("content" to Json.Str("x".repeat(500)))
        val line = JsonPretty.oneLine(long, max = 20)
        assertEquals(20, line.length)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun `pretty output re-parses to the same value`() {
        val roundTrip = Json.parse(JsonPretty.pretty(sample))
        assertEquals(sample, roundTrip)
    }
}
