package dev.mikhailtail.handyagent.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mikhailtail.handyagent.ui.common.CodeBlock
import dev.mikhailtail.handyagent.ui.export.ExportChip
import dev.mikhailtail.handyagent.ui.export.ExportHost
import dev.mikhailtail.handyagent.ui.export.Exporter
import dev.mikhailtail.handyagent.ui.theme.CodeTextStyle
import dev.mikhailtail.handyagent.ui.theme.PocketColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件页：浏览 Agent 工作目录（workspace）并在下方预览文本文件。
 *
 * 读取走 [FileBrowser]（纯逻辑、有单测）：路径一律规整回 workspace 内，
 * 二进制文件只提示不预览。目录切换时清空预览，避免「左边是新目录、右边是旧文件」。
 */
@Composable
fun FilesPane(
    workspace: File,
    host: ExportHost,
    modifier: Modifier = Modifier,
) {
    var path by remember { mutableStateOf(FileBrowser.ROOT) }
    var listing by remember { mutableStateOf<DirListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<FilePreview?>(null) }
    var pendingPreview by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workspace, path) {
        loading = true
        preview = null
        listing = withContext(Dispatchers.IO) { FileBrowser.list(workspace, path) }
        loading = false
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("文件", style = MaterialTheme.typography.titleSmall)
                Breadcrumb(path)
            }
            // 导出「当前目录」：在根目录时就是整个 workspace 打包 ——
            // 这是「Agent 的产物在私有目录里看不见」的最直接出口。
            ExportChip(
                host = host,
                plan = { Exporter.plan(workspace, path) },
            )
            Spacer(Modifier.width(6.dp))
            TextButton(
                onClick = { FileBrowser.parentOf(path)?.let { path = it } },
                enabled = FileBrowser.parentOf(path) != null,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.width(16.dp),
                )
                Text("上级", Modifier.padding(start = 4.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val current = listing
        if (loading) {
            Text(
                "读取中…",
                style = MaterialTheme.typography.bodySmall,
                color = PocketColors.Muted,
                modifier = Modifier.padding(16.dp),
            )
        } else if (current == null || current.error != null) {
            Text(
                current?.error ?: "无法读取目录",
                style = MaterialTheme.typography.bodySmall,
                color = PocketColors.ToolError,
                modifier = Modifier.padding(16.dp),
            )
        } else if (current.entries.isEmpty()) {
            Text(
                "空目录",
                style = MaterialTheme.typography.bodySmall,
                color = PocketColors.Muted,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(current.entries, key = { it.path }) { entry ->
                    EntryRow(entry, selected = preview?.path == entry.path) {
                        if (entry.isDir) {
                            path = entry.path
                        } else {
                            preview = null
                            // 预览在点击后异步读，避免大文件卡住这一帧。
                            pendingPreview = entry.path
                        }
                    }
                }
            }
        }

        // 预览读取放在列表之外：切换目录/文件都不会让 LazyColumn 的 item 作用域
        // 持有副作用，也避免快速连点时读到过期结果。
        LaunchedEffect(pendingPreview) {
            val target = pendingPreview ?: return@LaunchedEffect
            val loaded = withContext(Dispatchers.IO) { FileBrowser.preview(workspace, target) }
            if (pendingPreview == target) {
                preview = loaded
                pendingPreview = null
            }
        }

        preview?.let { p ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.path,
                        style = CodeTextStyle.copy(fontWeight = FontWeight.SemiBold),
                        color = PocketColors.ToolAccent,
                        modifier = Modifier.weight(1f),
                    )
                    if (p.truncated) {
                        Text("已截断", style = MaterialTheme.typography.labelSmall, color = PocketColors.Muted)
                    }
                    // 二进制文件也能导出 —— 那正是把图片/APK 弄出沙箱的唯一办法。
                    ExportChip(
                        host = host,
                        plan = { Exporter.plan(workspace, p.path) },
                        enabled = !p.missing,
                        label = "导出此文件",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.width(0.dp))
                when {
                    p.binary -> Text(
                        "二进制文件，不预览。",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.Muted,
                    )
                    p.missing -> Text(
                        "文件不存在或无法读取。",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.ToolError,
                    )
                    else -> SelectionContainer {
                        CodeBlock(p.text.ifEmpty { "（空文件）" }, maxHeight = 260)
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(path: String) {
    val scroll = rememberScrollState()
    Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        Text(
            path.ifEmpty { "/workspace" },
            style = MaterialTheme.typography.labelSmall,
            color = PocketColors.Muted,
        )
    }
}

@Composable
private fun EntryRow(entry: FileEntry, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (entry.isDir) "📁" else "📄",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            Text(
                entry.readableSize,
                style = MaterialTheme.typography.labelSmall,
                color = PocketColors.Muted,
            )
            if (entry.isDir) {
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = PocketColors.Muted,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
