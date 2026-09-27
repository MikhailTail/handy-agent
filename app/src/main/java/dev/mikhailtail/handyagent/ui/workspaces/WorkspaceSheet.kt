package dev.mikhailtail.handyagent.ui.workspaces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mikhailtail.handyagent.core.platform.WorkspaceLayout
import dev.mikhailtail.handyagent.core.platform.WorkspaceMeta
import dev.mikhailtail.handyagent.ui.theme.CcRadiusMd
import dev.mikhailtail.handyagent.ui.theme.PocketColors

/**
 * workspace 列表。
 *
 * 一个 workspace = 一块独立的文件沙箱；同一 workspace 下的会话**共享**它，
 * 不同 workspace 之间互不可见。这样"改 A 项目的代码"和"写 B 的笔记"不会互相污染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSheet(
    workspaces: List<WorkspaceMeta>,
    activeId: String,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "工作区",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "同一工作区的会话共享文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = PocketColors.Muted,
                )
            }

            // 新建
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("新工作区名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        onCreate(newName)
                        newName = ""
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("新建") }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(workspaces, key = { it.id }) { meta ->
                    WorkspaceRow(
                        meta = meta,
                        active = meta.id == activeId,
                        isDefault = meta.id == WorkspaceLayout.DEFAULT_WORKSPACE_ID,
                        renaming = renaming == meta.id,
                        renameDraft = renameDraft,
                        onRenameDraft = { renameDraft = it },
                        onStartRename = {
                            renaming = meta.id
                            renameDraft = meta.name
                        },
                        onCommitRename = {
                            onRename(meta.id, renameDraft)
                            renaming = null
                        },
                        onCancelRename = { renaming = null },
                        onSelect = { onSelect(meta.id) },
                        onDelete = { onDelete(meta.id) },
                    )
                }
            }

            Text(
                "删除工作区只会从列表里移除，沙箱里的文件不会被删。",
                style = MaterialTheme.typography.labelSmall,
                color = PocketColors.Muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun WorkspaceRow(
    meta: WorkspaceMeta,
    active: Boolean,
    isDefault: Boolean,
    renaming: Boolean,
    renameDraft: String,
    onRenameDraft: (String) -> Unit,
    onStartRename: () -> Unit,
    onCommitRename: () -> Unit,
    onCancelRename: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    // 与会话行保持同一套列表语言：10dp 圆角 + 选中用浅色底
    val bg = if (active) PocketColors.AccentSoft else androidx.compose.ui.graphics.Color.Transparent

    if (renaming) {
        Row(
            Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = renameDraft,
                onValueChange = onRenameDraft,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCommitRename) { Text("保存") }
            TextButton(onClick = onCancelRename) { Text("取消") }
        }
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(CcRadiusMd))
            .background(bg)
            .clickable { onSelect() }
            .padding(start = 10.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    meta.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    Text("当前", style = MaterialTheme.typography.labelSmall, color = PocketColors.ToolOk)
                }
            }
            Text(
                if (isDefault) "内置" else "自建",
                style = MaterialTheme.typography.labelSmall,
                color = PocketColors.Muted,
            )
        }

        TextButton(onClick = onStartRename) { Text("重命名") }

        // 默认工作区不允许摘除，否则老数据会突然"找不到家"
        if (!isDefault) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "移除工作区",
                    modifier = Modifier.size(18.dp),
                    tint = PocketColors.Muted,
                )
            }
        }
    }
}
