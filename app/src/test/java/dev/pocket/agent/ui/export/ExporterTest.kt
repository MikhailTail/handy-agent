package dev.pocket.agent.ui.export

import dev.pocket.agent.ui.files.FileBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

class ExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 22, 14, 30, 5)

    private fun workspace(): File = tmp.newFolder("workspace")

    private fun zipEntries(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    out[entry.name] = ""
                } else {
                    out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return out
    }

    private fun export(plan: ExportPlan, maxEntries: Int = Exporter.MAX_ENTRIES): Pair<ExportResult, ByteArray> {
        val sink = ByteArrayOutputStream()
        val result = Exporter.write(plan, sink, maxEntries)
        return result to sink.toByteArray()
    }

    private fun relOf(plan: ExportPlan): String =
        (plan.source as ExportPlan.Source.Workspace).relPath

    // ---- 命名与类型 ----

    @Test
    fun `single file keeps its own name and mime`() {
        val ws = workspace()
        File(ws, "notes.md").writeText("hi")

        val plan = Exporter.plan(ws, "notes.md", now)!!
        assertEquals("notes.md", plan.suggestedName)
        assertEquals("text/markdown", plan.mimeType)
        assertFalse(plan.zipped)
        assertEquals("notes.md", plan.label)
    }

    @Test
    fun `directory plan is a timestamped zip named after the directory`() {
        val ws = workspace()
        File(ws, "src/lib").mkdirs()

        val plan = Exporter.plan(ws, "src", now)!!
        assertEquals("src-20260922-143005.zip", plan.suggestedName)
        assertEquals("application/zip", plan.mimeType)
        assertTrue(plan.zipped)
        assertEquals("src", plan.label)
    }

    @Test
    fun `whole workspace exports as workspace zip`() {
        val ws = workspace()
        File(ws, "a.txt").writeText("a")

        val plan = Exporter.plan(ws, "", now)!!
        assertEquals("workspace-20260922-143005.zip", plan.suggestedName)
        assertEquals("整个 workspace", plan.label)
        assertTrue(plan.zipped)
    }

    @Test
    fun `plan returns null for missing or escaping paths`() {
        val ws = workspace()
        assertNull(Exporter.plan(ws, "nope.txt", now))
        assertNull(Exporter.plan(ws, "../outside.txt", now))
    }

    @Test
    fun `save as mime never makes the system append a second extension`() {
        // main.kt 是 text/plain，但 text/plain 的扩展名是 txt —— 报 octet-stream 才不会变 main.kt.txt
        assertEquals("application/octet-stream", Exporter.saveAsMime("main.kt"))
        assertEquals("application/octet-stream", Exporter.saveAsMime("Makefile"))
        // 扩展名与类型对得上时才报具体 MIME
        assertEquals("text/plain", Exporter.saveAsMime("readme.txt"))
        assertEquals("application/json", Exporter.saveAsMime("config.json"))
        assertEquals("text/markdown", Exporter.saveAsMime("log.md"))
        assertEquals("application/zip", Exporter.saveAsMime("workspace-1.zip"))
        assertEquals("image/png", Exporter.saveAsMime("shot.png"))
    }

    @Test
    fun `share mime is the real type even when save-as degrades`() {
        assertEquals("text/plain", Exporter.mimeOf("main.kt"))
        assertEquals("image/jpeg", Exporter.mimeOf("photo.JPG"))
        assertEquals("application/octet-stream", Exporter.mimeOf("blob"))
    }

    @Test
    fun `sanitize strips separators and keeps something usable`() {
        assertEquals("a_b", Exporter.sanitizeName("a/b"))
        assertEquals("export", Exporter.sanitizeName("   "))
        assertEquals("x", Exporter.sanitizeName("x."))
        assertFalse(Exporter.sanitizeName("../evil").contains('/'))
    }

    // ---- 写文件 ----

    @Test
    fun `single file export copies bytes verbatim`() {
        val ws = workspace()
        File(ws, "a.txt").writeText("hello 世界")

        val (result, bytes) = export(Exporter.plan(ws, "a.txt", now)!!)
        assertTrue(result.ok)
        assertEquals(1, result.entries)
        assertEquals("hello 世界", bytes.toString(Charsets.UTF_8))
        assertEquals(bytes.size.toLong(), result.bytes)
    }

    @Test
    fun `directory export zips nested files with the top folder preserved`() {
        val ws = workspace()
        File(ws, "src/main").mkdirs()
        File(ws, "src/main/App.kt").writeText("fun main() {}")
        File(ws, "src/readme.md").writeText("# src")

        val (result, bytes) = export(Exporter.plan(ws, "src", now)!!)
        assertTrue(result.ok)
        assertEquals(2, result.entries)

        val entries = zipEntries(bytes)
        assertEquals("fun main() {}", entries["src/main/App.kt"])
        assertEquals("# src", entries["src/readme.md"])
        assertTrue("目录条目要保留", entries.containsKey("src/main/"))
    }

    @Test
    fun `zip output is deterministic regardless of creation order`() {
        val ws = workspace()
        listOf("b.txt", "a.txt", "c.txt").forEach { File(ws, it).writeText(it) }

        val (_, first) = export(Exporter.plan(ws, "", now)!!)
        val (_, second) = export(Exporter.plan(ws, "", now)!!)

        // 条目顺序按名字排序，两次导出逐字节一致 —— 便于比对、也不会因为目录项顺序抖动而误判
        assertEquals(
            listOf("workspace/a.txt", "workspace/b.txt", "workspace/c.txt"),
            zipEntries(first).keys.toList(),
        )
        assertEquals(first.toList(), second.toList())
    }

    @Test
    fun `empty directory is reported instead of writing an empty zip`() {
        val ws = workspace()
        File(ws, "empty").mkdirs()

        val (result, bytes) = export(Exporter.plan(ws, "empty", now)!!)
        assertFalse(result.ok)
        assertEquals("目录是空的", result.error)
        assertEquals(0, bytes.size)
    }

    @Test
    fun `traversal is folded back inside the workspace and never leaks the outside file`() {
        val ws = workspace()
        val outside = File(ws.parentFile, "secret.txt").apply { writeText("OUTSIDE") }
        File(ws, "secret.txt").writeText("INSIDE")

        // "../secret.txt" 会被规整成 "secret.txt"：导出的是沙箱内那份，外面那份碰不到。
        val plan = Exporter.plan(ws, "../secret.txt", now)!!
        assertEquals("secret.txt", relOf(plan))
        val (result, bytes) = export(plan)
        assertTrue(result.ok)
        assertEquals("INSIDE", bytes.toString(Charsets.UTF_8))
        assertEquals("OUTSIDE", outside.readText())
    }

    @Test
    fun `a symlink pointing outside the workspace is refused`() {
        val ws = workspace()
        val outsideDir = tmp.newFolder("outside")
        val secret = File(outsideDir, "secret.txt").apply { writeText("OUTSIDE") }
        val link = File(ws, "link.txt")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), secret.toPath())
        } catch (e: Exception) {
            org.junit.Assume.assumeNoException("文件系统不支持符号链接", e)
        }

        assertNull("解析阶段就该拦掉", Exporter.plan(ws, "link.txt", now))
        assertEquals("路径越界", FileBrowser.list(ws, "link.txt").error)
    }

    @Test
    fun `inline text plan writes utf8 without touching the workspace`() {
        val ws = workspace()
        val plan = Exporter.inlinePlan("chat-1.md", "对话记录", "# 记录\n内容")

        val (result, bytes) = export(plan)
        assertTrue(result.ok)
        assertEquals("# 记录\n内容", bytes.toString(Charsets.UTF_8))
        assertEquals("chat-1.md", plan.suggestedName)
        assertEquals("text/markdown", plan.mimeType)
    }

    @Test
    fun `entry cap fails before writing any bytes`() {
        val ws = workspace()
        val big = File(ws, "big").apply { mkdirs() }
        repeat(8) { File(big, "f$it.txt").writeText("x") }

        // 上限收敛成 5（产品默认是 20000）：要验的是「超限时先失败、不留半个 zip」这个语义
        val (result, bytes) = export(Exporter.plan(ws, "big", now)!!, maxEntries = 5)
        assertFalse(result.ok)
        assertEquals("条目超过 5 个，请改为导出子目录", result.error)
        assertEquals("超限时不能留下半个 zip", 0, bytes.size)
    }

    @Test
    fun `result describes itself for the toast`() {
        assertEquals("已导出 · 512 B", ExportResult(512, 1).describe())
        assertEquals("已导出 3 个文件 · 1.0 KB", ExportResult(1024, 3).describe())
        assertTrue(ExportResult(error = "文件不存在").describe().contains("导出失败"))
    }
}
