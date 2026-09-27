package dev.mikhailtail.handyagent.core.model

import dev.mikhailtail.handyagent.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCodecTest {

    private val ref = ImageRef(
        id = "img_1.jpg",
        mediaType = "image/jpeg",
        width = 800,
        height = 600,
        byteSize = 1_234,
    )

    private fun roundTrip(m: Message): Message =
        MessageCodec.decodeMessage(MessageCodec.encode(m))!!

    @Test
    fun `round trips every block type`() {
        val m = Message(
            Role.USER,
            listOf(
                Block.Text("hi"),
                Block.Image(ref),
                Block.ToolResult("t1", "ok", images = listOf(ref)),
            ),
        )
        assertEquals(m, roundTrip(m))
    }

    @Test
    fun `round trips tool use with its json input`() {
        val m = Message(
            Role.ASSISTANT,
            listOf(Block.ToolUse("t1", "mobile_tap", Json.obj("index" to Json.of(3)))),
        )
        val back = roundTrip(m)
        val use = back.toolUses.single()
        assertEquals("t1", use.id)
        assertEquals("mobile_tap", use.name)
        assertEquals(3, use.input.int("index"))
    }

    @Test
    fun `round trips reasoning signature so thinking can be replayed`() {
        val m = Message(Role.ASSISTANT, listOf(Block.Reasoning("thought", "SIG", null)))
        assertEquals("SIG", roundTrip(m).blocks.filterIsInstance<Block.Reasoning>().single().signature)
    }

    @Test
    fun `round trips redacted thinking`() {
        val m = Message(Role.ASSISTANT, listOf(Block.Reasoning("", null, "ENCRYPTED")))
        val back = roundTrip(m).blocks.filterIsInstance<Block.Reasoning>().single()
        assertEquals("ENCRYPTED", back.redactedData)
        assertNull(back.signature)
    }

    @Test
    fun `image refs never carry base64`() {
        val encoded = MessageCodec.encode(Message(Role.USER, listOf(Block.Image(ref)))).encode()

        assertFalse("会话文件里绝不能出现 base64", encoded.contains("base64"))
        assertTrue(encoded.contains("img_1.jpg"))
        // 一张图元数据的体积应当是几百字节量级，而不是几百 KB
        assertTrue("单块消息 JSON 过长: ${encoded.length}", encoded.length < 300)
    }

    @Test
    fun `unknown block types are skipped instead of crashing`() {
        val json = Json.parse(
            """{"role":"USER","blocks":[{"t":"future_kind","x":1},{"t":"text","text":"ok"}]}"""
        )
        val m = MessageCodec.decodeMessage(json)!!
        assertEquals(listOf("ok"), m.blocks.filterIsInstance<Block.Text>().map { it.text })
    }

    @Test
    fun `a message whose blocks are all undecodable is dropped`() {
        val json = Json.parse("""{"role":"USER","blocks":[{"t":"nope"}]}""")
        assertNull(MessageCodec.decodeMessage(json))
    }

    @Test
    fun `unknown role is rejected`() {
        val json = Json.parse("""{"role":"SYSTEM_X","blocks":[{"t":"text","text":"hi"}]}""")
        assertNull(MessageCodec.decodeMessage(json))
    }

    @Test
    fun `decodeAll skips malformed entries but keeps the good ones`() {
        val arr = Json.parse(
            """
            [{"role":"USER","blocks":[{"t":"text","text":"a"}]},
             {"role":"BOGUS","blocks":[{"t":"text","text":"b"}]},
             {"role":"ASSISTANT","blocks":[{"t":"text","text":"c"}]}]
            """.trimIndent()
        )
        val messages = MessageCodec.decodeAll(arr)
        assertEquals(listOf("a", "c"), messages.map { it.text })
    }

    @Test
    fun `a ref with a corrupt id is dropped rather than half restored`() {
        val json = Json.parse(
            """{"role":"USER","blocks":[{"t":"image","ref":{"id":"","mt":"image/jpeg","w":10,"h":10,"n":1}}]}"""
        )
        // 唯一的块无效 → 整条消息被丢弃，避免留下没有图的空壳
        assertNull(MessageCodec.decodeMessage(json))
    }

    @Test
    fun `tool result images survive the round trip`() {
        val m = Message.toolResults(listOf(Block.ToolResult("t1", "shot", images = listOf(ref))))
        assertEquals(listOf(ref), roundTrip(m).toolResults.single().images)
    }
}
