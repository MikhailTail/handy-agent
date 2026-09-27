package dev.mikhailtail.handyagent.core.provider

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.ChatRequest
import dev.mikhailtail.handyagent.core.model.ImageData
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.ReasoningEffort
import dev.mikhailtail.handyagent.core.model.Role
import dev.mikhailtail.handyagent.core.provider.anthropic.AnthropicProvider
import dev.mikhailtail.handyagent.core.provider.openai.OpenAiCompatProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多模态序列化：Anthropic 的 image block 与 OpenAI 兼容协议的 image_url part。
 *
 * 这些断言锁住的是「图片确实被发出去了」——改造前两个 Provider 都完全不处理图片，
 * 其中 OpenAI 那侧还是**静默丢图**（不报错、不提示，历史里的图凭空消失）。
 */
class ImagePayloadTest {

    private val ref = ImageRef(
        id = "img_1.jpg",
        mediaType = "image/jpeg",
        width = 800,
        height = 600,
        byteSize = 1_234,
    )

    /** 假的 base64 载荷，内容不重要，只验证它被原样送进请求体。 */
    private val b64 = "QUJD"

    private fun resolver(known: Map<String, ImageData> = mapOf(ref.id to ImageData(ref.mediaType, b64))) =
        ImageResolver { known[it.id] }

    private fun anthropic(images: ImageResolver = resolver()) = AnthropicProvider(
        ProviderConfig(
            id = "anthropic",
            name = "Anthropic",
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-test",
            defaultModel = "claude-sonnet-4-5",
        ),
        FakeHttpEngine(),
        images = images,
    )

    private fun openai(images: ImageResolver = resolver()) = OpenAiCompatProvider(
        ProviderConfig(
            id = "openai",
            name = "OpenAI",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            defaultModel = "gpt-4o",
        ),
        FakeHttpEngine(),
        images = images,
    )

    private fun req(messages: List<Message>) = ChatRequest(
        model = "m",
        system = "",
        messages = messages,
        effort = ReasoningEffort.OFF,
    )

    // ------------------------------------------------------------ Anthropic

    @Test
    fun `anthropic user attachment becomes an image block`() {
        val msgs = listOf(Message.userWithImages("看看这个", listOf(ref)))
        val content = anthropic().buildBody(req(msgs)).array("messages").single().array("content")

        assertEquals(listOf("text", "image"), content.mapNotNull { it.str("type") })
        val source = content.last().obj("source")
        assertEquals("base64", source?.str("type"))
        assertEquals("image/jpeg", source?.str("media_type"))
        assertEquals(b64, source?.str("data"))
    }

    @Test
    fun `anthropic tool result without images keeps string content`() {
        val msgs = listOf(Message.toolResults(listOf(Block.ToolResult("t1", "ok"))))
        val tr = anthropic().buildBody(req(msgs)).array("messages").single().array("content").single()

        assertEquals("tool_result", tr.str("type"))
        // 无图时必须保持裸字符串 —— 与改造前逐字一致，不打扰既有测试。
        assertEquals("ok", tr.str("content"))
    }

    @Test
    fun `anthropic tool result with images upgrades content to an array`() {
        val result = Block.ToolResult("t1", "screenshot 800x600", images = listOf(ref))
        val tr = anthropic()
            .buildBody(req(listOf(Message.toolResults(listOf(result)))))
            .array("messages").single().array("content").single()

        assertEquals("tool_result", tr.str("type"))
        val inner = tr.array("content")
        assertEquals(listOf("text", "image"), inner.mapNotNull { it.str("type") })
        assertEquals("screenshot 800x600", inner.first().str("text"))
        assertEquals(b64, inner.last().obj("source")?.str("data"))
    }

    @Test
    fun `anthropic unresolvable image degrades to text instead of failing`() {
        val result = Block.ToolResult("t1", "shot", images = listOf(ref))
        val tr = anthropic(ImageResolver.NONE)
            .buildBody(req(listOf(Message.toolResults(listOf(result)))))
            .array("messages").single().array("content").single()

        // 解析器什么都不认 → 退回字符串 content，绝不抛异常中断整轮
        assertEquals("shot", tr.str("content"))
    }

    // ------------------------------------------------------------ OpenAI 兼容

    @Test
    fun `openai user attachment becomes content parts`() {
        val msgs = listOf(Message.userWithImages("看看这个", listOf(ref)))
        val msg = openai().buildBody(req(msgs)).array("messages").single()

        assertEquals("user", msg.str("role"))
        val parts = msg.array("content")
        assertEquals(listOf("text", "image_url"), parts.mapNotNull { it.str("type") })
        assertEquals("data:image/jpeg;base64,$b64", parts.last().obj("image_url")?.str("url"))
    }

    @Test
    fun `openai text only message keeps a plain string content`() {
        val msg = openai().buildBody(req(listOf(Message.user("hi")))).array("messages").single()
        assertEquals("hi", msg.str("content"))
    }

    @Test
    fun `openai tool images are appended as a trailing user message`() {
        val msgs = listOf(
            Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "mobile_screenshot", Json.obj("label" to Json.of("x"))))),
            Message.toolResults(listOf(Block.ToolResult("t1", "shot 800x600", images = listOf(ref)))),
        )
        val out = openai().buildBody(req(msgs)).array("messages")

        // tool 消息必须紧随 assistant.tool_calls，补的图只能排在全部 tool 之后
        assertEquals(listOf("assistant", "tool", "user"), out.mapNotNull { it.str("role") })
        val last = out.last()
        assertTrue(last.array("content").any { it.str("type") == "image_url" })
    }

    @Test
    fun `openai unresolvable tool image adds no extra message`() {
        val result = Block.ToolResult("t1", "shot", images = listOf(ref))
        val out = openai(ImageResolver.NONE)
            .buildBody(req(listOf(Message.toolResults(listOf(result)))))
            .array("messages")

        assertEquals(listOf("tool"), out.mapNotNull { it.str("role") })
    }
}
