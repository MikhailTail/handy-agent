package dev.pocket.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.pocket.agent.core.permission.PermissionVerdict
import dev.pocket.agent.ui.common.CodeBlock
import dev.pocket.agent.ui.common.DiffView
import dev.pocket.agent.ui.theme.CodeTextStyle
import dev.pocket.agent.ui.theme.PocketColors

/**
 * 审批卡：写盘 / 执行命令前让用户「所见即所批」。
 *
 * 只展示 [PendingApproval.request] 里已经算好的 diff（[dev.pocket.agent.core.permission.ToolPreviewer]
 * 在挂起前就完成了预演），UI 不做第二套判断，也不去读文件 —— 因此弹出的内容与
 * 工具真正要做的改动严格一致，不会出现「批的是 A、写的是 B」。
 */
@Composable
fun ApprovalDialog(
    pending: PendingApproval,
    onResolve: (PermissionVerdict) -> Unit,
    onDismiss: () -> Unit,
) {
    val request = pending.request
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("需要审批", style = MaterialTheme.typography.titleMedium)
                Text(
                    request.toolName,
                    style = CodeTextStyle.copy(fontWeight = FontWeight.SemiBold),
                    color = PocketColors.ToolAccent,
                )
                Text(
                    request.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (request.hasDiff) {
                    Text(
                        "变更 ${request.stats}",
                        style = MaterialTheme.typography.labelMedium,
                        color = PocketColors.Muted,
                    )
                    DiffView(request.diffLines)
                } else if (request.diffText.isNotBlank()) {
                    CodeBlock(request.diffText)
                } else if (!request.readOnly) {
                    Text(
                        "此操作没有可预览的文件差异。",
                        style = MaterialTheme.typography.labelMedium,
                        color = PocketColors.Muted,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onResolve(PermissionVerdict.allowOnce()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("允许一次") }
                    Button(
                        onClick = { onResolve(PermissionVerdict.allowSession()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("本会话允许") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onResolve(PermissionVerdict.denyOnce()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("拒绝") }
                    OutlinedButton(
                        onClick = { onResolve(PermissionVerdict.denySession()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("本会话拒绝") }
                }
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}
