package dev.pocket.agent.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.pocket.agent.core.util.DiffLine
import dev.pocket.agent.ui.theme.CodeTextStyle
import dev.pocket.agent.ui.theme.PocketColors

/**
 * 等宽只读文本的两种呈现：整体一块的 [CodeBlock]，和按行着色的 [DiffView]。
 *
 * 抽到共用文件是因为「审批卡」与「时间轴上的工具卡片」展示的是同一类数据
 * （工具入参 JSON / 命令输出 / unified diff）——两处必须长得一样，用户才不会
 * 在「批准前看到的」与「执行后留下的记录」之间读出不一致。
 */

/** 红绿行渲染。行首保留 +/-/空格前缀，等宽字体便于对齐。 */
@Composable
fun DiffView(lines: List<DiffLine>, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = PocketColors.CodeBg,
    ) {
        Column(
            Modifier
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp)
        ) {
            lines.forEach { line ->
                val (bg, fg) = when (line.kind) {
                    DiffLine.Kind.ADD -> PocketColors.DiffAddBg to PocketColors.DiffAddText
                    DiffLine.Kind.DEL -> PocketColors.DiffDelBg to PocketColors.DiffDelText
                    DiffLine.Kind.CONTEXT -> Color.Transparent to PocketColors.DiffCtxText
                }
                Text(
                    text = line.prefix + line.text,
                    style = CodeTextStyle,
                    color = fg,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .horizontalScroll(scroll)
                        .padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** 深色底等宽块，用于输出 / JSON / diff 原文。 */
@Composable
fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Int = 300,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = PocketColors.CodeBg,
    ) {
        Column(
            Modifier
                .heightIn(max = maxHeight.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(text, style = CodeTextStyle, softWrap = false)
        }
    }
}
