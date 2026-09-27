package dev.mikhailtail.handyagent.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileBrowserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun workspace(): File = tmp.newFolder("workspace")

    @Test
    fun `normalize collapses dots and never escapes the root`() {
        assertEquals("a/b", FileBrowser.normalize("/a//b/"))
        assertEquals("b", FileBrowser.normalize("a/../b"))
        assertEquals("", FileBrowser.normalize("../../.."))
        assertEquals("a/c", FileBrowser.normalize("a/./c"))
    }

    @Test
    fun `join and parent round-trip`() {
        assertEquals("a/b/c", FileBrowser.join("a/b", "c"))
        assertEquals("a/b", FileBrowser.parentOf("a/b/c"))
        assertEquals("", FileBrowser.parentOf("a"))
        assertNull(FileBrowser.parentOf(""))
    }

    @Test
    fun `breadcrumb walks from the root`() {
        assertEquals(listOf("", "a", "a/b"), FileBrowser.breadcrumb("a/b"))
        assertEquals(listOf(""), FileBrowser.breadcrumb(""))
    }

    @Test
    fun `listing puts directories first then sorts by name`() {
        val ws = workspace()
        File(ws, "z.txt").writeText("z")
        File(ws, "a.txt").writeText("a")
        File(ws, "sub").mkdirs()

        val listing = FileBrowser.list(ws, "")
        assertNull(listing.error)
        assertEquals(listOf("sub", "a.txt", "z.txt"), listing.entries.map { it.name })
        assertTrue(listing.entries.first().isDir)
        assertEquals("1 B", listing.entries[1].readableSize)
    }

    @Test
    fun `listing a nested directory returns workspace relative paths`() {
        val ws = workspace()
        File(ws, "sub/deep").mkdirs()
        File(ws, "sub/x.md").writeText("# hi")

        val listing = FileBrowser.list(ws, "sub")
        assertEquals(listOf("sub/deep", "sub/x.md"), listing.entries.map { it.path })
    }

    @Test
    fun `missing directory is reported not thrown`() {
        val listing = FileBrowser.list(workspace(), "nope")
        assertEquals("目录不存在", listing.error)
        assertTrue(listing.entries.isEmpty())
    }

    @Test
    fun `preview reads text and flags truncation`() {
        val ws = workspace()
        File(ws, "a.txt").writeText("hello world")
        val ok = FileBrowser.preview(ws, "a.txt", maxBytes = 5)
        assertEquals("hello", ok.text)
        assertTrue(ok.truncated)
        assertTrue(ok.ok)
    }

    @Test
    fun `binary files are flagged instead of rendered`() {
        val ws = workspace()
        File(ws, "bin").writeBytes(byteArrayOf(1, 2, 0, 3))
        val preview = FileBrowser.preview(ws, "bin")
        assertTrue(preview.binary)
        assertFalse(preview.ok)
    }

    @Test
    fun `preview of a directory or missing file is reported`() {
        val ws = workspace()
        File(ws, "d").mkdirs()
        assertTrue(FileBrowser.preview(ws, "d").missing)
        assertTrue(FileBrowser.preview(ws, "gone").missing)
    }

    @Test
    fun `human sizes are readable`() {
        assertEquals("512 B", FileBrowser.humanSize(512))
        assertEquals("1.0 KB", FileBrowser.humanSize(1024))
        assertEquals("2.0 MB", FileBrowser.humanSize(2L * 1024 * 1024))
    }
}
