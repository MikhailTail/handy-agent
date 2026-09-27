package dev.mikhailtail.handyagent.core.platform

import dev.mikhailtail.handyagent.core.model.ImageRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class ImageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = FileImageBytesStore(tmp.newFolder("images"))

    private fun ref(id: String = "img_1.jpg") =
        ImageRef(id = id, mediaType = "image/jpeg", width = 10, height = 10, byteSize = 3)

    @Test
    fun `put and get round trip`() {
        val s = store()
        assertTrue(s.put("img_1.jpg", byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), s.get("img_1.jpg"))
    }

    @Test
    fun `missing id returns null instead of throwing`() {
        assertNull(store().get("nope.jpg"))
    }

    @Test
    fun `path traversal ids are refused`() {
        val s = store()
        // id 可能来自模型输出或落盘的会话文件，属于不可信输入
        assertFalse(s.put("../evil.jpg", byteArrayOf(1)))
        assertFalse(s.put("a/b.jpg", byteArrayOf(1)))
        assertFalse(s.put("a\\b.jpg", byteArrayOf(1)))
        assertFalse(s.put("..", byteArrayOf(1)))
        assertNull(s.get("../evil.jpg"))
    }

    @Test
    fun `list skips half written temp files`() {
        val s = store()
        s.put("img_1.jpg", byteArrayOf(1))
        s.put("img_2.jpg", byteArrayOf(2))
        assertEquals(listOf("img_1.jpg", "img_2.jpg"), s.list())
        assertEquals(2L, s.totalBytes())
    }

    @Test
    fun `purge drops everything beyond the count cap`() {
        val s = store()
        repeat(5) { i -> s.put("img_$i.jpg", byteArrayOf(i.toByte())) }

        val removed = s.purge(now = System.currentTimeMillis(), maxCount = 2, maxAgeDays = 365)

        assertEquals(3, removed)
        assertEquals(2, s.list().size)
    }

    @Test
    fun `purge keeps recent images under the cap`() {
        val s = store()
        repeat(3) { i -> s.put("img_$i.jpg", byteArrayOf(i.toByte())) }

        assertEquals(0, s.purge(now = System.currentTimeMillis(), maxCount = 10, maxAgeDays = 7))
        assertEquals(3, s.list().size)
    }

    @Test
    fun `resolver encodes the stored bytes as base64`() {
        val s = store()
        s.put("img_1.jpg", "ABC".toByteArray(Charsets.UTF_8))

        val data = StoreImageResolver(s).resolve(ref())

        assertEquals("image/jpeg", data?.mediaType)
        assertEquals(Base64.getEncoder().encodeToString("ABC".toByteArray(Charsets.UTF_8)), data?.base64)
    }

    @Test
    fun `resolver returns null for a missing file or an invalid ref`() {
        val resolver = StoreImageResolver(store())
        assertNull(resolver.resolve(ref("absent.jpg")))
        assertNull(resolver.resolve(ImageRef("", "", 0, 0, 0)))
    }
}
