package dev.pocket.agent.core.tool

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.sandbox.PathJail
import dev.pocket.agent.core.sandbox.ProcessShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: ToolContext
    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        val jail = PathJail(tmp.root)
        ctx = ToolContext(jail, ProcessShell(tmp.root))
        registry = ToolRegistry(
            listOf(ReadFileTool, WriteFileTool, EditFileTool, ListDirTool, GlobTool, GrepTool)
        )
    }

    private fun args(vararg pairs: Pair<String, Json>): Json = Json.obj(*pairs)
    private fun str(v: String) = Json.Str(v)
    private fun run(tool: Tool, input: Json) = runBlocking { registry.execute(tool.name, input, ctx) }

    private fun file(rel: String, content: String): File {
        val f = File(tmp.root, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    // ---- write ------------------------------------------------------------

    @Test
    fun `write creates nested file and reports line count`() {
        val out = run(WriteFileTool, args("path" to str("a/b/c.txt"), "content" to str("one\ntwo\n")))
        assertFalse(out.isError)
        assertTrue(out.content.contains("created a/b/c.txt"))
        assertEquals("one\ntwo\n", File(tmp.root, "a/b/c.txt").readText())
    }

    @Test
    fun `write over an existing file reports overwrite`() {
        file("x.txt", "old")
        val out = run(WriteFileTool, args("path" to str("x.txt"), "content" to str("new")))
        assertTrue(out.content.contains("overwrote x.txt"))
        assertEquals("new", File(tmp.root, "x.txt").readText())
    }

    @Test
    fun `write requires content`() {
        val out = run(WriteFileTool, args("path" to str("x.txt")))
        assertTrue(out.isError)
        assertTrue(out.content.contains("missing 'content'"))
    }

    // ---- read -------------------------------------------------------------

    @Test
    fun `read returns numbered lines`() {
        file("r.txt", "alpha\nbeta\ngamma")
        val out = run(ReadFileTool, args("path" to str("r.txt")))
        assertEquals("     1\talpha\n     2\tbeta\n     3\tgamma", out.content)
    }

    @Test
    fun `read honours offset and limit`() {
        file("r.txt", "1\n2\n3\n4\n5")
        val out = run(ReadFileTool, args("path" to str("r.txt"), "offset" to Json.of(2), "limit" to Json.of(2)))
        assertEquals("     2\t2\n     3\t3\n[truncated: lines 2-3 of 5]", out.content)
    }

    @Test
    fun `read marks truncation when file exceeds the line limit`() {
        file("big.txt", (1..3000).joinToString("\n") { "line$it" })
        val out = run(ReadFileTool, args("path" to str("big.txt")))
        assertFalse(out.isError)
        assertTrue(out.content.contains("[truncated: lines 1-2000 of 3000]"))
    }

    @Test
    fun `read reports empty file`() {
        file("e.txt", "")
        assertEquals("(empty file)", run(ReadFileTool, args("path" to str("e.txt"))).content)
    }

    @Test
    fun `read missing file is an error`() {
        val out = run(ReadFileTool, args("path" to str("nope.txt")))
        assertTrue(out.isError)
        assertTrue(out.content.contains("file not found"))
    }

    @Test
    fun `read on a directory is an error`() {
        file("d/keep.txt", "x")
        val out = run(ReadFileTool, args("path" to str("d")))
        assertTrue(out.isError)
        assertTrue(out.content.contains("is a directory"))
    }

    @Test
    fun `read rejects binary files`() {
        File(tmp.root, "b.bin").writeBytes(byteArrayOf(0x41, 0x00, 0x42))
        val out = run(ReadFileTool, args("path" to str("b.bin")))
        assertTrue(out.isError)
        assertTrue(out.content.contains("binary file"))
    }

    @Test
    fun `read offset past EOF is an error`() {
        file("r.txt", "a\nb")
        val out = run(ReadFileTool, args("path" to str("r.txt"), "offset" to Json.of(99)))
        assertTrue(out.isError)
        assertTrue(out.content.contains("past EOF"))
    }

    // ---- edit -------------------------------------------------------------

    @Test
    fun `edit replaces a unique occurrence`() {
        file("e.txt", "hello world")
        val out = run(EditFileTool, args(
            "path" to str("e.txt"),
            "old_string" to str("world"),
            "new_string" to str("there"),
        ))
        assertFalse(out.isError)
        assertEquals("hello there", File(tmp.root, "e.txt").readText())
        assertTrue(out.content.contains("1 replacement"))
    }

    @Test
    fun `edit refuses an ambiguous match`() {
        file("e.txt", "aa aa")
        val out = run(EditFileTool, args(
            "path" to str("e.txt"),
            "old_string" to str("aa"),
            "new_string" to str("b"),
        ))
        assertTrue(out.isError)
        assertTrue(out.content.contains("appears 2 times"))
        assertEquals("aa aa", File(tmp.root, "e.txt").readText())
    }

    @Test
    fun `edit replace_all rewrites every occurrence`() {
        file("e.txt", "aa aa")
        val out = run(EditFileTool, args(
            "path" to str("e.txt"),
            "old_string" to str("aa"),
            "new_string" to str("b"),
            "replace_all" to Json.TRUE,
        ))
        assertFalse(out.isError)
        assertEquals("b b", File(tmp.root, "e.txt").readText())
        assertTrue(out.content.contains("2 replacements"))
    }

    @Test
    fun `edit reports a missing needle without touching the file`() {
        file("e.txt", "abc")
        val out = run(EditFileTool, args(
            "path" to str("e.txt"),
            "old_string" to str("zzz"),
            "new_string" to str("y"),
        ))
        assertTrue(out.isError)
        assertTrue(out.content.contains("not found"))
        assertEquals("abc", File(tmp.root, "e.txt").readText())
    }

    @Test
    fun `edit rejects empty or identical needles`() {
        file("e.txt", "abc")
        val empty = run(EditFileTool, args(
            "path" to str("e.txt"), "old_string" to str(""), "new_string" to str("y"),
        ))
        assertTrue(empty.isError)
        val same = run(EditFileTool, args(
            "path" to str("e.txt"), "old_string" to str("abc"), "new_string" to str("abc"),
        ))
        assertTrue(same.isError)
    }

    @Test
    fun `edit can delete text with an empty replacement`() {
        file("e.txt", "keepDELETEkeep")
        val out = run(EditFileTool, args(
            "path" to str("e.txt"), "old_string" to str("DELETE"), "new_string" to str(""),
        ))
        assertFalse(out.isError)
        assertEquals("keepkeep", File(tmp.root, "e.txt").readText())
    }

    // ---- ls ---------------------------------------------------------------

    @Test
    fun `ls lists directories before files`() {
        file("z.txt", "x")
        file("adir/inside.txt", "x")
        file("b.txt", "x")
        val out = run(ListDirTool, args("path" to str(".")))
        val names = out.content.lines().map { it.substringBefore(" (").trimEnd('/') }
        assertEquals(listOf("adir", "b.txt", "z.txt"), names)
    }

    @Test
    fun `ls reports an empty directory`() {
        File(tmp.root, "empty").mkdirs()
        assertEquals("(empty directory)", run(ListDirTool, args("path" to str("empty"))).content)
    }

    // ---- glob -------------------------------------------------------------

    @Test
    fun `glob finds files recursively`() {
        file("src/Main.kt", "")
        file("src/util/Helper.kt", "")
        file("README.md", "")
        val out = run(GlobTool, args("pattern" to str("**/*.kt")))
        assertFalse(out.isError)
        assertEquals(listOf("src/Main.kt", "src/util/Helper.kt"), out.content.lines())
    }

    @Test
    fun `glob ignores git internals`() {
        file(".git/config", "")
        file("a.txt", "")
        val out = run(GlobTool, args("pattern" to str("**/*")))
        assertEquals(listOf("a.txt"), out.content.lines())
    }

    @Test
    fun `glob with no hits says so`() {
        file("a.txt", "")
        assertEquals("no files match '*.rs'", run(GlobTool, args("pattern" to str("*.rs"))).content)
    }

    // ---- grep -------------------------------------------------------------

    @Test
    fun `grep reports path line and text`() {
        file("g.txt", "alpha\nbeta\ngamma")
        val out = run(GrepTool, args("pattern" to str("beta")))
        assertFalse(out.isError)
        assertEquals("g.txt:2: beta", out.content)
    }

    @Test
    fun `grep honours the glob filter`() {
        file("a.kt", "needle")
        file("a.md", "needle")
        val out = run(GrepTool, args("pattern" to str("needle"), "glob" to str("**/*.md")))
        assertEquals("a.md:1: needle", out.content)
    }

    @Test
    fun `grep is case insensitive on request`() {
        file("g.txt", "Hello")
        assertEquals("no matches for /hello/", run(GrepTool, args("pattern" to str("hello"))).content)
        assertEquals("g.txt:1: Hello", run(GrepTool, args(
            "pattern" to str("hello"), "ignore_case" to Json.TRUE,
        )).content)
    }

    @Test
    fun `grep rejects an invalid regex`() {
        val out = run(GrepTool, args("pattern" to str("(")))
        assertTrue(out.isError)
        assertTrue(out.content.contains("invalid regex"))
    }

    @Test
    fun `grep can target a single file`() {
        file("one.txt", "hit")
        file("two.txt", "hit")
        val out = run(GrepTool, args("pattern" to str("hit"), "path" to str("one.txt")))
        assertEquals("one.txt:1: hit", out.content)
    }

    // ---- sandbox ----------------------------------------------------------

    @Test
    fun `parent traversal is blocked by the jail`() {
        val out = run(ReadFileTool, args("path" to str("../../etc/passwd")))
        assertTrue(out.isError)
        assertTrue(out.content.contains("sandbox violation"))
    }

    @Test
    fun `absolute path is chrooted into the workspace`() {
        file("inside.txt", "ok")
        val out = run(ReadFileTool, args("path" to str("/inside.txt")))
        assertFalse(out.isError)
        assertTrue(out.content.contains("ok"))
    }
}
