package dev.mikhailtail.handyagent.ui.timeline

import dev.mikhailtail.handyagent.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSummaryTest {

    @Test
    fun `read shows the path and a range when present`() {
        assertEquals("读取 src/a.kt", ToolSummary.describe("read", Json.obj("path" to Json.Str("src/a.kt"))))
        assertEquals(
            "读取 src/a.kt（offset=10, limit=50）",
            ToolSummary.describe(
                "read",
                Json.obj("path" to Json.Str("src/a.kt"), "offset" to Json.of(10), "limit" to Json.of(50)),
            ),
        )
    }

    @Test
    fun `write reports the line count it would create`() {
        val summary = ToolSummary.describe(
            "write",
            Json.obj("path" to Json.Str("x.txt"), "content" to Json.Str("a\nb\nc")),
        )
        assertEquals("写入 x.txt（3 行）", summary)
    }

    @Test
    fun `edit mentions replace_all`() {
        assertEquals("编辑 a.txt", ToolSummary.describe("edit", Json.obj("path" to Json.Str("a.txt"))))
        assertEquals(
            "编辑 a.txt（全部替换）",
            ToolSummary.describe(
                "edit",
                Json.obj("path" to Json.Str("a.txt"), "replace_all" to Json.Bool(true)),
            ),
        )
    }

    @Test
    fun `bash shows the command`() {
        assertEquals("运行 ls -la", ToolSummary.describe("bash", Json.obj("command" to Json.Str("ls -la"))))
    }

    @Test
    fun `ls without a path means the workspace root`() {
        assertEquals("列出 workspace", ToolSummary.describe("ls", Json.obj()))
    }

    @Test
    fun `todo counts unfinished items`() {
        val input = Json.obj(
            "items" to Json.arr(
                Json.obj("content" to Json.Str("a"), "status" to Json.Str("completed")),
                Json.obj("content" to Json.Str("b"), "status" to Json.Str("pending")),
            )
        )
        assertEquals("更新待办（2 项，1 项未完成）", ToolSummary.describe("todo", input))
    }

    @Test
    fun `unknown tools fall back to compacted json`() {
        val summary = ToolSummary.describe("mcp__x__y", Json.obj("q" to Json.Str("hi")))
        assertEquals("""{"q":"hi"}""", summary)
    }

    @Test
    fun `long summaries are truncated`() {
        val summary = ToolSummary.describe("bash", Json.obj("command" to Json.Str("x".repeat(400))))
        assertTrue(summary.length <= 96)
        assertTrue(summary.endsWith("…"))
    }
}
