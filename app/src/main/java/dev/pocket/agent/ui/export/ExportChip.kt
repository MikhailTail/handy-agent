package dev.pocket.agent.ui.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocket.agent.ui.theme.PocketColors

/**
 * 「导出」小按钮：点开在「另存为…」与「分享」之间选。
 *
 * [plan] 是**惰性**的（`() -> ExportPlan?`）而不是直接传 [ExportPlan]：对话记录要遍历
 * 整条时间轴渲染 Markdown，只有真正点了导出才该付这份成本；同时 plan 里的路径在
 * 渲染那一刻可能已经变了（比如当前浏览的目录被删掉），惰性求值拿到的是点击时的真实状态。
 * 返回 null 表示「此刻没有可导出的东西」（目标已消失、越界），此时按钮禁用。
 */
@Composable
fun ExportChip(
    host: ExportHost,
    plan: () -> ExportPlan?,
    modifier: Modifier = Modifier,
    label: String = "导出",
    enabled: Boolean = true,
) {
    var menu by remember { mutableStateOf(false) }
    val available = enabled && plan() != null

    Box(modifier) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.clickable(enabled = available) { menu = true },
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = null,
                    tint = if (available) PocketColors.ToolAccent else PocketColors.Muted,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (available) MaterialTheme.colorScheme.onSurface else PocketColors.Muted,
                )
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("另存为…", style = MaterialTheme.typography.bodySmall) },
                onClick = {
                    menu = false
                    plan()?.let(host::saveAs)
                },
            )
            DropdownMenuItem(
                text = { Text("分享", style = MaterialTheme.typography.bodySmall) },
                onClick = {
                    menu = false
                    plan()?.let(host::share)
                },
            )
        }
    }
}
