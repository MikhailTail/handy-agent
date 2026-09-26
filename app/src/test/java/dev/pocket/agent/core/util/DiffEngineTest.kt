package dev.pocket.agent.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffEngineTest {

    private fun kinds(lines: List<DiffLine>) = lines.map { it.prefix }.joinToString("")
    private fun k(kind: DiffLine.Kind, text: String) = DiffLine(kind, text)

    @Test fun identicalTextIsAllContext() {
        val d = DiffEngine.compute("a\nb\nc", "a\nb\nc")
        assertEquals("   ", kinds(d))
        assertTrue(DiffEngine.stats(d).isEmpty)
    }

    @Test fun detectsAddition() {
        val d = DiffEngine.compute("a\nc", "a\nb\nc")
        assertEquals(" + ", kinds(d))
        assertEquals(DiffStats(1, 0), DiffEngine.stats(d))
    }

    @Test fun detectsRemoval() {
        val d = DiffEngine.compute("a\nb\nc", "a\nc")
        assertEquals(" - ", kinds(d))
        assertEquals(DiffStats(0, 1), DiffEngine.stats(d))
    }

    @Test fun detectsModification() {
        val d = DiffEngine.compute("a\nb\nc", "a\nB\nc")
        assertEquals(" -+ ", kinds(d))
        assertEquals(DiffStats(1, 1), DiffEngine.stats(d))
    }

    @Test fun reconstructsBothSidesLosslessly() {
        val old = "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8"
        val new = "l1\nl2\nX\nl4\nl6\nl7\nY\nl8"
        val d = DiffEngine.compute(old, new)
        assertEquals(old, DiffEngine.joinLines(d.filter { it.kind != DiffLine.Kind.ADD }.map { it.text }))
        assertEquals(new, DiffEngine.joinLines(d.filter { it.kind != DiffLine.Kind.DEL }.map { it.text }))
    }

    @Test fun handlesEmptySides() {
        assertEquals("++", kinds(DiffEngine.compute("", "a\nb")))
        assertEquals(3, DiffEngine.stats(DiffEngine.compute("", "a\nb\nc")).added)
        assertEquals(2, DiffEngine.stats(DiffEngine.compute("a\nb", "")).removed)
        assertEquals(DiffStats(0, 0), DiffEngine.stats(DiffEngine.compute("", "")))
        assertEquals(emptyList<DiffLine>(), DiffEngine.compute("", ""))
    }

    @Test fun handlesTrailingNewlineDifference() {
        assertEquals(DiffStats(0, 1), DiffEngine.stats(DiffEngine.compute("a\n", "a")))
        assertEquals(DiffStats(1, 0), DiffEngine.stats(DiffEngine.compute("a", "a\n")))
    }

    @Test fun unifiedReturnsEmptyWhenNoChange() {
        assertEquals("", DiffEngine.unified("same\ntext", "same\ntext"))
    }

    @Test fun unifiedHeaderAndBodyShape() {
        val old = (1..20).joinToString("\n") { "line$it" }
        val new = (1..20).joinToString("\n") { if (it == 10) "CHANGED" else "line$it" }
        val u = DiffEngine.unified(old, new, "old.txt", "new.txt")
        val lines = u.split("\n").dropLast(1)
        assertEquals("--- old.txt", lines[0])
        assertEquals("+++ new.txt", lines[1])
        assertTrue("header was ${lines[2]}", Regex("""^@@ -\d+,\d+ \+\d+,\d+ @@$""").matches(lines[2]))
        assertEquals("-line10", lines.first { it.startsWith("-") && !it.startsWith("---") })
        assertEquals("+CHANGED", lines.first { it.startsWith("+") && !it.startsWith("+++") })
        assertTrue(lines.contains(" line7"))
        assertTrue(lines.contains(" line13"))
        assertTrue(!lines.contains(" line14"))
    }

    @Test fun applyUnifiedReproducesNewTextAcrossManyCases() {
        val cases = listOf(
            "" to "",
            "" to "a",
            "a" to "",
            "a" to "b",
            "a\nb\nc" to "a\nB\nc",
            "a\nc" to "a\nb\nc",
            "a\nb\nc" to "a\nc",
            "a\n" to "a",
            (1..30).joinToString("\n") { "l$it" } to (1..30).joinToString("\n") { if (it % 7 == 0) "M$it" else "l$it" },
            "x\ny\nz" to "z\ny\nx",
            "\n\n\n" to "\n",
            "keep\n" to "keep\nadded\nmore\n",
            "dup\ndup\ndup\ndup" to "dup\ndup",
        )
        for ((old, new) in cases) {
            val u = DiffEngine.unified(old, new, "a", "b")
            val applied = applyUnified(u, old)
            assertEquals("apply mismatch for old=<$old> new=<$new>", new, applied)
        }
    }

    @Test fun diffIsMinimalForRepeatedLines() {
        assertEquals(DiffStats(0, 1), DiffEngine.stats(DiffEngine.compute("a\na\na\n", "a\na\n")))
    }

    @Test fun fallsBackForHugeInputs() {
        val old = (1..5000).joinToString("\n") { "o$it" }
        val new = (1..5000).joinToString("\n") { "n$it" }
        val d = DiffEngine.compute(old, new)
        assertEquals(DiffStats(5000, 5000), DiffEngine.stats(d))
        assertEquals(old, DiffEngine.joinLines(d.filter { it.kind != DiffLine.Kind.ADD }.map { it.text }))
        assertEquals(new, DiffEngine.joinLines(d.filter { it.kind != DiffLine.Kind.DEL }.map { it.text }))
    }

    @Test fun splitJoinRoundTripKeepsTrailingNewline() {
        for (s in listOf("", "a", "a\n", "a\nb", "a\nb\n", "\n", "\n\n")) {
            assertEquals(s, DiffEngine.joinLines(DiffEngine.splitLines(s)))
        }
    }

    // ---- 测试内实现一个 unified diff 应用器，用来独立校验 DiffEngine.unified 的正确性 ----
    private val hunkHeader = Regex("""^@@ -(\d+),(\d+) \+(\d+),(\d+) @@$""")

    private fun applyUnified(unified: String, oldText: String): String {
        if (unified.isEmpty()) return oldText
        val oldLines = DiffEngine.splitLines(oldText)
        val lines = unified.split("\n").dropLast(1)
        val out = ArrayList<String>()
        var oldIdx = 0
        var i = 2
        while (i < lines.size) {
            val m = hunkHeader.find(lines[i]) ?: error("bad hunk header: ${lines[i]}")
            val oldStart = m.groupValues[1].toInt()
            val oldCount = m.groupValues[2].toInt()
            val copyUntil = if (oldCount == 0) oldStart else oldStart - 1
            while (oldIdx < copyUntil) { out.add(oldLines[oldIdx]); oldIdx++ }
            i++
            var consumed = 0
            while (i < lines.size && !lines[i].startsWith("@@")) {
                val l = lines[i]
                when {
                    l.startsWith(" ") -> { out.add(l.substring(1)); oldIdx++; consumed++ }
                    l.startsWith("-") -> { oldIdx++; consumed++ }
                    l.startsWith("+") -> out.add(l.substring(1))
                    else -> error("bad body line: <$l>")
                }
                i++
            }
            assertEquals("oldCount mismatch in hunk ${lines[i - 1]}", oldCount, consumed)
        }
        while (oldIdx < oldLines.size) { out.add(oldLines[oldIdx]); oldIdx++ }
        return DiffEngine.joinLines(out)
    }
}
