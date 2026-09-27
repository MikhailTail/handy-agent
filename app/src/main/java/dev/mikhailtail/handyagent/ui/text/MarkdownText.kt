package dev.mikhailtail.handyagent.ui.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.mikhailtail.handyagent.ui.common.CodeBlock
import dev.mikhailtail.handyagent.ui.theme.MonoFontFamily
import dev.mikhailtail.handyagent.ui.theme.PocketColors

/**
 * [Markdown.parse] 的 Compose 绘制层。
 *
 * 解析（纯 Kotlin、有单测）与绘制严格分离：这里只负责把不可变块画出来，
 * 不做任何文本判断。流式输出时上层会反复调用本函数，[remember] 保证
 * 同一段文本不会重复解析。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block -> Block(block, color) }
    }
}

@Composable
private fun Block(block: MdBlock, color: Color) {
    when (block) {
        is MdBlock.Heading -> Text(
            text = inline(block.text),
            color = color,
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                3 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            },
        )

        is MdBlock.Paragraph -> Text(
            text = inline(block.text),
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )

        is MdBlock.Bullet -> BulletRow(
            marker = "•",
            depth = block.depth,
            content = inline(block.text),
            color = color,
        )

        is MdBlock.Ordered -> BulletRow(
            marker = "${block.number}.",
            depth = block.depth,
            content = inline(block.text),
            color = color,
        )

        is MdBlock.Code -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (block.lang.isNotBlank()) {
                Text(
                    block.lang,
                    style = MaterialTheme.typography.labelSmall,
                    color = PocketColors.Muted,
                )
            }
            CodeBlock(block.code, maxHeight = 360)
        }

        is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
            Spacer(
                Modifier
                    .width(3.dp)
                    .background(PocketColors.Muted, RoundedCornerShape(2.dp))
            )
            Text(
                text = inline(block.text),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun BulletRow(marker: String, depth: Int, content: AnnotatedString, color: Color) {
    Row(Modifier.padding(start = (depth * 14).dp)) {
        Text(
            marker,
            color = PocketColors.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            content,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(start = 6.dp)
                .weight(1f, fill = true),
        )
    }
}

/**
 * 行内样式。
 *
 * 颜色来自主题，所以必须是 @Composable；但解析结果按 (文本, 颜色) 缓存 ——
 * 流式输出时每一帧都会重组，不缓存的话每个字都要重新解析一遍 markdown。
 */
@Composable
private fun inline(text: String): AnnotatedString {
    val codeColor = PocketColors.ToolAccent
    val codeBg = PocketColors.CodeBg
    return remember(text, codeColor, codeBg) { buildInline(text, codeColor, codeBg) }
}

/** 纯解析：不碰主题，便于缓存与测试。 */
private fun buildInline(text: String, codeColor: Color, codeBg: Color): AnnotatedString =
    buildAnnotatedString {
        for (span in Markdown.spans(text)) {
            when (span) {
                is MdSpan.Plain -> append(span.text)
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                is MdSpan.Code -> withStyle(
                    SpanStyle(
                        fontFamily = MonoFontFamily,
                        color = codeColor,
                        background = codeBg,
                    )
                ) { append(span.text) }
            }
        }
    }
