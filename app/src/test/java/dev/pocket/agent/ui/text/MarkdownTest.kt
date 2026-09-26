package dev.pocket.agent.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `plain lines group into one paragraph`() {
        val blocks = Markdown.parse("hello\nworld")
        assertEquals(1, blocks.size)
        assertEquals("hello\nworld", (blocks.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `blank line splits paragraphs`() {
        val blocks = Markdown.parse("a\n\nb")
        assertEquals(2, blocks.size)
        assertEquals(listOf("a", "b"), blocks.map { (it as MdBlock.Paragraph).text })
    }

    @Test
    fun `fenced code keeps language and body verbatim`() {
        val md = "before\n```kotlin\nval x = 1\n\nval y = 2\n```\nafter"
        val blocks = Markdown.parse(md)
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.lang)
        assertEquals("val x = 1\n\nval y = 2", code.code)
        assertEquals(3, blocks.size)
    }

    @Test
    fun `unterminated fence still yields a code block`() {
        val code = Markdown.parse("```\nno closing fence").filterIsInstance<MdBlock.Code>().single()
        assertEquals("no closing fence", code.code)
    }

    @Test
    fun `headings carry their level`() {
        val blocks = Markdown.parse("## Title\n### Sub")
        assertEquals(MdBlock.Heading(2, "Title"), blocks[0])
        assertEquals(MdBlock.Heading(3, "Sub"), blocks[1])
    }

    @Test
    fun `nested bullets report depth`() {
        val blocks = Markdown.parse("- one\n  - nested\n")
        assertEquals(MdBlock.Bullet(0, "one"), blocks[0])
        assertEquals(MdBlock.Bullet(1, "nested"), blocks[1])
    }

    @Test
    fun `ordered list keeps the number`() {
        assertEquals(MdBlock.Ordered(3, 0, "third"), Markdown.parse("3. third").single())
    }

    @Test
    fun `rule is not mistaken for a bullet`() {
        assertEquals(MdBlock.Rule, Markdown.parse("---").single())
        assertEquals(MdBlock.Bullet(0, "--x"), Markdown.parse("- --x").single())
    }

    @Test
    fun `quote lines join`() {
        assertEquals("a\nb", (Markdown.parse("> a\n> b").single() as MdBlock.Quote).text)
    }

    @Test
    fun `carriage returns are normalised`() {
        assertEquals(2, Markdown.parse("a\r\n\r\nb").size)
    }

    @Test
    fun `spans split code and bold`() {
        val spans = Markdown.spans("use `read` to **win**")
        assertEquals(MdSpan.Plain("use "), spans[0])
        assertEquals(MdSpan.Code("read"), spans[1])
        assertEquals(MdSpan.Plain(" to "), spans[2])
        assertEquals(MdSpan.Bold("win"), spans[3])
    }

    @Test
    fun `unterminated markers degrade to plain text`() {
        assertEquals(MdSpan.Plain("a `b"), Markdown.spans("a `b").single())
        assertEquals(MdSpan.Plain("a **b"), Markdown.spans("a **b").single())
    }

    @Test
    fun `spans never lose characters`() {
        // 全部标记都闭合：此时标记字符被消费（渲染为样式），正文一个不差地保留。
        val input = "mix `code` and **bold** and *it*"
        val rendered = Markdown.spans(input).joinToString("") {
            when (it) {
                is MdSpan.Plain -> it.text
                is MdSpan.Code -> it.text
                is MdSpan.Bold -> it.text
                is MdSpan.Italic -> it.text
            }
        }
        val stripped = input.replace("`", "").replace("*", "")
        assertEquals(stripped, rendered)
    }

    @Test
    fun `unclosed markers are preserved verbatim, not eaten`() {
        // 未闭合的标记按文档约定原样显示（所见即所输），因此结尾的 ` 必须留在文本里。
        val input = "and `x"
        val rendered = Markdown.spans(input).joinToString("") {
            when (it) {
                is MdSpan.Plain -> it.text
                is MdSpan.Code -> it.text
                is MdSpan.Bold -> it.text
                is MdSpan.Italic -> it.text
            }
        }
        assertEquals(input, rendered)
    }

    @Test
    fun `empty input yields no blocks`() {
        assertTrue(Markdown.parse("").isEmpty())
        assertEquals(listOf<MdSpan>(MdSpan.Plain("")), Markdown.spans(""))
    }
}
