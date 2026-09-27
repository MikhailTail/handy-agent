package dev.mikhailtail.handyagent.ui.sessions

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mikhailtail.handyagent.core.platform.SessionMeta
import dev.mikhailtail.handyagent.ui.theme.CcRadiusMd
import dev.mikhailtail.handyagent.ui.theme.PocketColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表。
 *
 * 之前所有会话逻辑都在 [dev.mikhailtail.handyagent.ui.chat.ChatController] 里，但界面上没有入口 ——
 * 用户能"新建"却看不到建过的会话，更切不回去。这个弹层把那部分能力露出来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListSheet(
    sessions: List<SessionMeta>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "会话",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${sessions.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = PocketColors.Muted,
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable { onNew() },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "新建会话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (sessions.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有历史会话。\n发第一条消息后会自动保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.Muted,
                    )
                }
                return@Column
            }

            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(sessions, key = { it.id }) { meta ->
                    SessionRow(
                        meta = meta,
                        active = meta.id == activeId,
                        onSelect = { onSelect(meta.id) },
                        onDelete = { onDelete(meta.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    meta: SessionMeta,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    // 上游列表行：10dp 圆角 + 左右 8dp 外边距，选中用浅色底而不是高亮条
    val bg = if (active) PocketColors.AccentSoft else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(CcRadiusMd))
            .background(bg)
            .clickable { onSelect() }
            .padding(start = 10.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meta.pinned) {
                    Text("📌 ", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    meta.title.ifBlank { "未命名会话" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = PocketColors.OnAccentSoft,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(meta.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = PocketColors.Muted,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${meta.messageCount} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = PocketColors.Muted,
                )
            }
            if (meta.preview.isNotBlank()) {
                Text(
                    meta.preview,
                    style = MaterialTheme.typography.labelSmall,
                    color = PocketColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除会话",
                modifier = Modifier.size(18.dp),
                tint = PocketColors.Muted,
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun formatTime(epochMs: Long): String =
    if (epochMs <= 0L) "" else timeFormat.format(Date(epochMs))
