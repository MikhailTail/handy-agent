package dev.pocket.agent.core.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test fun parsesScalars() {
        assertEquals(Json.Null, Json.parse("null"))
        assertEquals(Json.TRUE, Json.parse("true"))
        assertEquals(Json.FALSE, Json.parse("false"))
        assertEquals(Json.Num(42.0), Json.parse("42"))
        assertEquals(Json.Num(-1.5), Json.parse("-1.5"))
        assertEquals(Json.Num(1e3), Json.parse("1e3"))
        assertEquals(Json.Str("hi"), Json.parse("\"hi\""))
    }

    @Test fun parsesNestedStructures() {
        val j = Json.parse("""{"a":[1,2,{"b":true}],"c":null}""")
        assertEquals(3, j.array("a").size)
        assertEquals(true, j.array("a")[2].bool("b"))
        assertTrue(j["c"]!!.isNull)
        assertNull(j["missing"])
    }

    @Test fun handlesStringEscapes() {
        val src = "\"line1\\nline2\\t\\\"q\\\" \\\\ \\u4e2d\\u6587\""
        val s = Json.parse(src).asStringOrNull()!!
        assertEquals("line1\nline2\t\"q\" \\ 中文", s)
    }

    @Test fun decodesSurrogatePairEscape() {
        // U+1F600 -> 😀
        val s = Json.parse("\"\\uD83D\\uDE00\"").asStringOrNull()!!
        assertEquals("😀", s)
        assertEquals(1, s.codePointCount(0, s.length))
    }

    @Test fun writesEscapesAndRoundTrips() {
        val original = Json.obj(
            "text" to Json.Str("a\nb\t\"c\"\\d"),
            "n" to Json.of(7),
            "f" to Json.of(1.25),
            "b" to Json.of(true),
            "arr" to Json.arr(Json.of("x"), Json.Null),
        )
        val encoded = original.encode()
        val decoded = Json.parse(encoded)
        assertEquals(original, decoded)
    }

    @Test fun encodesIntegersWithoutDecimalPoint() {
        assertEquals("""{"n":42}""", Json.obj("n" to Json.of(42)).encode())
        assertEquals("""{"n":1.5}""", Json.obj("n" to Json.of(1.5)).encode())
    }

    @Test fun preservesFieldOrder() {
        val e = Json.obj("z" to Json.of(1), "a" to Json.of(2), "m" to Json.of(3)).encode()
        assertEquals("""{"z":1,"a":2,"m":3}""", e)
    }

    @Test fun rejectsMalformedInput() {
        val bad = listOf("", "{", "[1,", "{\"a\":}", "tru", "\"unterminated", "{\"a\" 1}", "1 2", "[1]x")
        for (b in bad) {
            assertNull("should reject: <$b>", Json.parseOrNull(b))
        }
    }

    @Test fun rejectsRawControlCharInString() {
        assertNull(Json.parseOrNull("\"a${1.toChar()}b\""))
    }

    @Test fun rejectsDeepNestingWithoutStackOverflow() {
        val deep = "[".repeat(400) + "]".repeat(400)
        assertNull(Json.parseOrNull(deep))
    }

    @Test fun mergesObjects() {
        val a = Json.obj("x" to Json.of(1), "y" to Json.of(2))
        val b = Json.obj("y" to Json.of(9))
        val m = a.merge(b)
        assertEquals(9, m.int("y"))
        assertEquals(1, m.int("x"))
    }
}
