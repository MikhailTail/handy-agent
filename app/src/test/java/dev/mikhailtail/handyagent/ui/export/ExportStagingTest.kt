package dev.mikhailtail.handyagent.ui.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

class ExportStagingTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun inline(text: String, name: String = "notes.txt"): ExportPlan =
        Exporter.inlinePlan(name, name, text)

    @Test
    fun `staged file is written and reported`() {
        val dir = tmp.newFolder("exports")
        val staged = ExportStaging.stageInto(dir, inline("hello 世界"))
        assertNotNull(staged)
        assertEquals("notes.txt", staged!!.file.name)
        assertEquals("hello 世界", staged.file.readText())
        assertEquals("hello 世界".toByteArray().size.toLong(), staged.bytes)
        assertEquals(1, staged.entries)
    }

    @Test
    fun `staging twice keeps both files instead of overwriting`() {
        val dir = tmp.newFolder("exports")
        ExportStaging.stageInto(dir, inline("first"))
        val second = ExportStaging.stageInto(dir, inline("second"))
        assertEquals("notes-1.txt", second!!.file.name)
        assertEquals("first", File(dir, "notes.txt").readText())
        assertEquals("second", second.file.readText())
    }

    @Test
    fun `unique name inserts the counter before the extension`() {
        val dir = tmp.newFolder("exports")
        assertEquals("a.zip", ExportStaging.uniqueName(dir, "a.zip"))
        File(dir, "a.zip").writeText("x")
        assertEquals("a-1.zip", ExportStaging.uniqueName(dir, "a.zip"))
        File(dir, "a-1.zip").writeText("x")
        assertEquals("a-2.zip", ExportStaging.uniqueName(dir, "a.zip"))
    }

    @Test
    fun `unique name handles extension-less and sanitizes`() {
        val dir = tmp.newFolder("exports")
        File(dir, "Makefile").writeText("x")
        assertEquals("Makefile-1", ExportStaging.uniqueName(dir, "Makefile"))
        // 分隔符不能穿过目录：'../x' 会被规整成一个普通文件名。
        val name = ExportStaging.uniqueName(dir, "../x")
        assertFalse(name.contains('/'))
    }

    @Test
    fun `staging creates the directory when missing`() {
        val dir = File(tmp.root, "cache/exports")
        assertFalse(dir.exists())
        val staged = ExportStaging.stageInto(dir, inline("hi"))
        assertNotNull(staged)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `a directory plan is zipped into the cache`() {
        val ws = tmp.newFolder("workspace")
        File(ws, "src/main").mkdirs()
        File(ws, "src/main/App.kt").writeText("fun main() {}")
        val plan = Exporter.plan(ws, "src")!!
        val dir = tmp.newFolder("exports")

        val staged = ExportStaging.stageInto(dir, plan)
        assertNotNull(staged)
        assertTrue(staged!!.file.name.endsWith(".zip"))
        val entries = ZipInputStream(staged.file.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name }.toList()
        }
        assertTrue(entries.contains("src/main/App.kt"))
    }

    @Test
    fun `prune removes only exports past the ttl`() {
        val dir = tmp.newFolder("exports")
        val now = 1_000_000_000_000L
        val fresh = File(dir, "fresh.txt").apply { writeText("f") }
        val stale = File(dir, "stale.txt").apply { writeText("s") }
        fresh.setLastModified(now - ExportStaging.TTL_MS + 1_000)
        stale.setLastModified(now - ExportStaging.TTL_MS - 1_000)

        assertEquals(1, ExportStaging.pruneOld(dir, now))
        assertTrue(fresh.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun `staging refreshes the timestamp so a fresh export is never pruned`() {
        val dir = tmp.newFolder("exports")
        val now = 1_000_000_000_000L
        // 源文件本身很旧，但导出时间应当是「现在」。
        val plan = inline("old content")
        val staged = ExportStaging.stageInto(dir, plan, now = now)
        assertEquals(now, staged!!.file.lastModified())
        assertEquals(0, ExportStaging.pruneOld(dir, now))
    }

    @Test
    fun `staging an empty directory fails without leaving a file behind`() {
        val ws = tmp.newFolder("workspace")
        File(ws, "empty").mkdirs()
        val plan = Exporter.plan(ws, "empty")!!
        val dir = tmp.newFolder("exports")

        assertNull(ExportStaging.stageInto(dir, plan))
        assertEquals("空目录不该留下半截文件", 0, dir.listFiles()!!.size)
    }

    @Test
    fun `prune tolerates a missing directory`() {
        assertEquals(0, ExportStaging.pruneOld(File(tmp.root, "nope"), 0))
    }
}
