package dev.pocket.agent.ui.text

/**
 * 助手输出的**极简** Markdown 结构。
 *
 * 刻意不引第三方 Markdown 库：Agent 的输出 90% 是「段落 + 围栏代码 + 列表」，
 * 自己解析成不可变块后，Compose 侧只做绘制，逻辑就能在宿主 JVM 上单测。
 * 解析永远是**尽力而为**的：认不出来的行退化成普通段落，绝不抛异常、绝不丢字符。
 */
sealed interface MdBlock {

    data class Heading(val level: Int, val text: String) : MdBlock

    /** 连续的非空行合成一段；行内换行原样保留。 */
    data class Paragraph(val lines: List<String>) : MdBlock {
        val text: String get() = lines.joinToString("\n")
    }

    data class Bullet(val depth: Int, val text: String) : MdBlock

    data class Ordered(val number: Int, val depth: Int, val text: String) : MdBlock

    /** 围栏代码块。[lang] 是 ``` 后的语言标记（可能为空）。 */
    data class Code(val lang: String, val code: String) : MdBlock

    data class Quote(val lines: List<String>) : MdBlock {
        val text: String get() = lines.joinToString("\n")
    }

    /** 水平分隔线（`---` / `***` / `___`）。 */
    data object Rule : MdBlock
}

/** 行内样式片段。 */
sealed interface MdSpan {
    data class Plain(val text: String) : MdSpan
    data class Bold(val text: String) : MdSpan
    data class Italic(val text: String) : MdSpan
    data class Code(val text: String) : MdSpan
}

object Markdown {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val RULE = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val FENCE = Regex("^\\s*(?:```|~~~)\\s*(\\S*)\\s*$")

    fun parse(text: String): List<MdBlock> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val blocks = ArrayList<MdBlock>()
        val paragraph = ArrayList<String>()
        val quote = ArrayList<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(paragraph.toList()))
                paragraph.clear()
            }
        }

        fun flushQuote() {
            if (quote.isNotEmpty()) {
                blocks.add(MdBlock.Quote(quote.toList()))
                quote.clear()
            }
        }

        fun flushAll() {
            flushParagraph()
            flushQuote()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // 围栏代码：一直吃到闭合围栏或文本结束（未闭合也照样成块，不吞内容）。
            val fence = FENCE.find(line)
            if (fence != null) {
                flushAll()
                val lang = fence.groupValues[1]
                val body = ArrayList<String>()
                i++
                while (i < lines.size && FENCE.find(lines[i]) == null) {
                    body.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // 跳过闭合围栏
                blocks.add(MdBlock.Code(lang, body.joinToString("\n")))
                continue
            }

            if (line.isBlank()) {
                flushAll()
                i++
                continue
            }

            if (RULE.matches(line)) {
                flushAll()
                blocks.add(MdBlock.Rule)
                i++
                continue
            }

            val heading = HEADING.find(line)
            if (heading != null) {
                flushAll()
                blocks.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim()))
                i++
                continue
            }

            val bullet = BULLET.find(line)
            if (bullet != null) {
                flushAll()
                blocks.add(MdBlock.Bullet(indentDepth(bullet.groupValues[1]), bullet.groupValues[2].trim()))
                i++
                continue
            }

            val ordered = ORDERED.find(line)
            if (ordered != null) {
                flushAll()
                blocks.add(
                    MdBlock.Ordered(
                        number = ordered.groupValues[2].toIntOrNull() ?: 1,
                        depth = indentDepth(ordered.groupValues[1]),
                        text = ordered.groupValues[3].trim(),
                    )
                )
                i++
                continue
            }

            val quoted = QUOTE.find(line)
            if (quoted != null) {
                flushParagraph()
                quote.add(quoted.groupValues[1])
                i++
                continue
            }

            flushQuote()
            paragraph.add(line)
            i++
        }

        flushAll()
        return blocks
    }

    /** 缩进 → 层级；每 2 空格算一层，制表符算一层。 */
    private fun indentDepth(indent: String): Int {
        var spaces = 0
        for (c in indent) {
            if (c == '\t') spaces += 2 else spaces++
        }
        return spaces / 2
    }

    /**
     * 行内样式切分：`code`、**bold**、*italic* / _italic_。
     * 标记未闭合时退化为纯文本（不吞掉星号），保证「所见即所输」。
     */
    fun spans(text: String): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        val plain = StringBuilder()
        var i = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out.add(MdSpan.Plain(plain.toString()))
                plain.setLength(0)
            }
        }

        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) {
                        plain.append(c); i++
                    } else {
                        flushPlain()
                        out.add(MdSpan.Code(text.substring(i + 1, end)))
                        i = end + 1
                    }
                }
                c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end < 0) {
                        plain.append("**"); i += 2
                    } else {
                        flushPlain()
                        out.add(MdSpan.Bold(text.substring(i + 2, end)))
                        i = end + 2
                    }
                }
                c == '*' || c == '_' -> {
                    val end = text.indexOf(c, i + 1)
                    if (end < 0 || end == i + 1) {
                        plain.append(c); i++
                    } else {
                        flushPlain()
                        out.add(MdSpan.Italic(text.substring(i + 1, end)))
                        i = end + 1
                    }
                }
                else -> {
                    plain.append(c); i++
                }
            }
        }
        flushPlain()
        if (out.isEmpty()) out.add(MdSpan.Plain(""))
        return out
    }
}
