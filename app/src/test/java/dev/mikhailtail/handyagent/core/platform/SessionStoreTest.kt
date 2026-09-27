package dev.mikhailtail.handyagent.core.platform

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root by lazy { tmp.newFolder("sessions") }
    private var clock = 1_000L
    private var seq = 0

    private fun store() = FileSessionStore(
        root = root,
        now = { clock },
        newId = { "s${++seq}" },
    )

    private fun indexFile() = File(root, FileSessionStore.INDEX_FILE)

    private fun toolCallHistory(): List<Message> = listOf(
        Message.user("hi"),
        Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "read", Json.obj()))),
        Message.toolResults(listOf(Block.ToolResult("t1", "ok"))),
        Message.assistant("done"),
    )

    // ---------------------------------------------------------------- 基本 CRUD

    @Test
    fun `create persists a session and lists it`() {
        val s = store()
        val rec = s.create(title = "首个会话", providerId = "deepseek")

        assertEquals("s1", rec.meta.id)
        assertEquals("首个会话", rec.meta.title)
        assertEquals("deepseek", rec.meta.providerId)
        assertEquals(1, s.list().size)
        assertEquals("s1", s.load("s1")?.meta?.id)
    }

    @Test
    fun `create falls back to a default title`() {
        assertEquals(FileSessionStore.DEFAULT_TITLE, store().create().meta.title)
    }

    @Test
    fun `append grows the history and bumps updatedAt`() {
        val s = store()
        s.create("a")
        clock = 2_000

        val after = s.append("s1", listOf(Message.user("hi"), Message.assistant("yo")))!!

        assertEquals(2, after.messages.size)
        assertEquals(2_000L, after.meta.updatedAt)
        assertEquals(2, s.list().single().messageCount)
    }

    @Test
    fun `append to a missing session returns null`() {
        assertNull(store().append("nope", listOf(Message.user("x"))))
    }

    @Test
    fun `a long session survives a round trip intact`() {
        val s = store()
        s.create("a")
        val history = toolCallHistory()
        s.append("s1", history)

        val loaded = s.load("s1")!!
        assertEquals(history, loaded.messages)
    }

    // ------------------------------------------------------------------ 列表

    @Test
    fun `list puts pinned first then sorts by recency`() {
        val s = store()
        s.create("old")
        clock = 2_000
        s.create("new")
        clock = 3_000
        s.create("newest")
        s.setPinned("s1", true)

        assertEquals(listOf("old", "newest", "new"), s.list().map { it.title })
    }

    @Test
    fun `archived sessions are hidden unless asked for`() {
        val s = store()
        s.create("a")
        val meta = s.list().single()
        // 直接改 meta 走 save，模拟归档
        s.save(SessionRecord(meta.copy(archived = true), emptyList()))

        assertEquals(0, s.list().size)
        assertEquals(1, s.list(includeArchived = true).size)
    }

    @Test
    fun `rename and setPinned persist`() {
        val s = store()
        s.create("old")

        assertTrue(s.rename("s1", "改名了"))
        assertTrue(s.setPinned("s1", true))

        val meta = s.list().single()
        assertEquals("改名了", meta.title)
        assertTrue(meta.pinned)
    }

    @Test
    fun `mutating a missing session reports failure`() {
        val s = store()
        assertFalse(s.rename("nope", "x"))
        assertFalse(s.setPinned("nope", true))
        assertFalse(s.delete("nope"))
    }

    @Test
    fun `delete removes both the file and the index entry`() {
        val s = store()
        s.create("a")
        s.create("b")

        assertTrue(s.delete("s1"))

        assertEquals(listOf("s2"), s.list().map { it.id })
        assertNull(s.load("s1"))
    }

    // -------------------------------------------------------------- 容错恢复

    @Test
    fun `a corrupt index is rebuilt by scanning the directory`() {
        val s = store()
        s.create("a")
        s.create("b")
        indexFile().writeText("{ this is not valid json")

        // 索引坏掉不该让会话列表整个消失
        assertEquals(2, s.list().size)
        assertTrue(indexFile().readText().contains("s1"))
    }

    @Test
    fun `a missing index is rebuilt too`() {
        val s = store()
        s.create("a")
        indexFile().delete()

        assertEquals(1, s.list().size)
    }

    @Test
    fun `a corrupt session file is skipped while the others still load`() {
        val s = store()
        s.create("a")
        s.create("b")
        File(root, "s1.json").writeText("{ truncated")

        assertNull(s.load("s1"))
        assertNotNull(s.load("s2"))
        // 索引仍在，列表不因单个坏文件而失败
        assertEquals(2, s.list().size)
    }

    @Test
    fun `path traversal ids are refused`() {
        val s = store()
        assertNull(s.load("../evil"))
        assertFalse(s.delete("../evil"))
    }

    // ------------------------------------------------------------------ fork

    @Test
    fun `fork snaps the cut so tool pairing stays intact`() {
        val s = store()
        s.create("a")
        s.append("s1", toolCallHistory())

        // 切在 2 会正好拆散 tool_use / tool_result，必须左移到 1
        val forked = s.fork("s1", uptoMessageIndex = 2, title = "分支")!!

        assertEquals(1, forked.messages.size)
        assertEquals(1, forked.meta.forkedAtMessage)
        assertEquals("s1", forked.meta.parentId)
        assertEquals("分支", forked.meta.title)
    }

    @Test
    fun `fork defaults to copying the whole history`() {
        val s = store()
        s.create("a")
        s.append("s1", toolCallHistory())

        val forked = s.fork("s1")!!

        assertEquals(4, forked.messages.size)
        assertEquals("s1", forked.meta.parentId)
        assertEquals(4, forked.meta.forkedAtMessage)
    }

    @Test
    fun `fork of a missing session returns null`() {
        assertNull(store().fork("nope"))
    }

    // ---------------------------------------------------------------- search

    @Test
    fun `search finds text and returns a snippet`() {
        val s = store()
        s.create("关于部署的会话")
        s.append("s1", listOf(Message.user("帮我看看 nginx 的反向代理配置")))

        val hits = s.search("nginx")

        assertEquals(1, hits.size)
        assertTrue(hits.single().snippet.contains("nginx"))
    }

    @Test
    fun `search matches the title as well`() {
        val s = store()
        s.create("部署手册")

        assertEquals(1, s.search("部署").size)
    }

    @Test
    fun `search ignores blank queries and respects the limit`() {
        val s = store()
        s.create("a")
        s.append("s1", listOf(Message.user("hello")))
        s.create("b")
        s.append("s2", listOf(Message.user("hello again")))

        assertEquals(0, s.search("   ").size)
        assertEquals(2, s.search("hello").size)
        assertEquals(1, s.search("hello", limit = 1).size)
    }

    @Test
    fun `search does not return sessions that do not match`() {
        val s = store()
        s.create("a")
        s.append("s1", listOf(Message.user("完全无关的内容")))
        assertEquals(0, s.search("nginx").size)
    }
}
