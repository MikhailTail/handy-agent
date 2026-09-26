package dev.pocket.agent.ui.timeline

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocket.agent.ui.common.CodeBlock
import dev.pocket.agent.ui.common.DiffView
import dev.pocket.agent.ui.text.JsonPretty
import dev.pocket.agent.ui.text.MarkdownText
import dev.pocket.agent.ui.theme.CodeTextStyle
import dev.pocket.agent.ui.theme.PocketColors

/**
 * 时间轴：把 [TimelineItem] 列表画成一条可滚动的对话记录。
 *
 * 纯展示层 —— 状态归约全在 [TimelineReducer]，这里只做绘制与「展开/收起」这类
 * 纯视图状态。每条目以 [TimelineItem.id] 为 key，因此流式追加文本时行不会重建，
 * 滚动位置也不会跳。
 */
@Composable
fun TimelinePane(
    items: List<TimelineItem>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        EmptyHint(modifier)
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item ->
            when (item) {
                is TimelineItem.UserText -> UserBubble(item)
                is TimelineItem.AssistantText -> AssistantBlock(item)
                is TimelineItem.ToolCall -> ToolCallCard(item)
                is TimelineItem.Note -> NoteLine(item)
                is TimelineItem.RunSummary -> RunFooter(item)
            }
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("还没有对话", style = MaterialTheme.typography.titleSmall)
            Text(
                "在下面输入任务，Agent 会在沙箱 workspace 里读写文件、跑命令。\n" +
                    "每次写盘或执行命令前都会先弹审批卡。",
                style = MaterialTheme.typography.bodySmall,
                color = PocketColors.Muted,
            )
        }
    }
}

@Composable
private fun UserBubble(item: TimelineItem.UserText) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun AssistantBlock(item: TimelineItem.AssistantText) {
    var showReasoning by rememberSaveable(item.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.reasoning.isNotBlank()) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showReasoning = !showReasoning },
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text(
                        if (showReasoning) "▾ 思考过程" else "▸ 思考过程（${item.reasoning.length} 字）",
                        style = MaterialTheme.typography.labelSmall,
                        color = PocketColors.Muted,
                    )
                    if (showReasoning) {
                        Text(
                            item.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        if (item.text.isNotBlank()) {
            MarkdownText(item.text, Modifier.fillMaxWidth())
        }
        if (item.streaming) {
            Text("…", style = CodeTextStyle, color = PocketColors.ToolAccent)
        }
    }
}

/**
 * 一张工具卡片：标题栏一行人话（[ToolSummary]），点开看完整入参与输出。
 * 状态就地更新 —— 同一张卡从 RUNNING 变 OK/ERROR/DENIED，不新增行。
 */
@Composable
private fun ToolCallCard(item: TimelineItem.ToolCall) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val accent = when (item.status) {
        TimelineItem.ToolCall.Status.RUNNING -> PocketColors.ToolAccent
        TimelineItem.ToolCall.Status.OK -> PocketColors.ToolOk
        TimelineItem.ToolCall.Status.ERROR -> PocketColors.ToolError
        TimelineItem.ToolCall.Status.DENIED -> PocketColors.ToolWarn
    }
    val statusText = when (item.status) {
        TimelineItem.ToolCall.Status.RUNNING -> "运行中"
        TimelineItem.ToolCall.Status.OK -> "完成"
        TimelineItem.ToolCall.Status.ERROR -> "失败"
        TimelineItem.ToolCall.Status.DENIED -> "被拒绝"
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            if (item.status == TimelineItem.ToolCall.Status.RUNNING) {
                                accent.copy(alpha = 0.9f)
                            } else accent,
                            CircleShape,
                        )
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    item.name,
                    style = CodeTextStyle.copy(fontWeight = FontWeight.SemiBold),
                    color = accent,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    ToolSummary.describe(item.name, item.input),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = if (expanded) 6 else 1,
                )
                if (item.durationMs > 0) {
                    Text(
                        "${item.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = PocketColors.Muted,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = PocketColors.Muted,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (!expanded) {
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = PocketColors.Muted)
            } else {
                Text("入参", style = MaterialTheme.typography.labelSmall, color = PocketColors.Muted)
                CodeBlock(JsonPretty.pretty(item.input), maxHeight = 220)

                if (item.output.isNotBlank()) {
                    Text(
                        if (item.status == TimelineItem.ToolCall.Status.DENIED) "说明" else "输出",
                        style = MaterialTheme.typography.labelSmall,
                        color = PocketColors.Muted,
                    )
                    if (item.status == TimelineItem.ToolCall.Status.OK) {
                        CodeBlock(item.output, maxHeight = 320)
                    } else {
                        Text(
                            item.output,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.status == TimelineItem.ToolCall.Status.ERROR) {
                                PocketColors.ToolError
                            } else {
                                PocketColors.ToolWarn
                            },
                        )
                    }
                }
                Text(statusText, style = MaterialTheme.typography.labelSmall, color = accent)
            }
        }
    }
}

@Composable
private fun NoteLine(item: TimelineItem.Note) {
    val color = when (item.kind) {
        TimelineItem.Note.Kind.INFO -> PocketColors.Muted
        TimelineItem.Note.Kind.COMPACTION -> PocketColors.ToolAccent
        TimelineItem.Note.Kind.ERROR -> PocketColors.ToolError
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .height(1.dp)
                .weight(1f)
                .background(color.copy(alpha = 0.3f))
        )
        Text(
            item.text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(
            Modifier
                .height(1.dp)
                .weight(1f)
                .background(color.copy(alpha = 0.3f))
        )
    }
}

@Composable
private fun RunFooter(item: TimelineItem.RunSummary) {
    val tint: Color = when (item.stopReason) {
        dev.pocket.agent.core.model.StopReason.ERROR -> PocketColors.ToolError
        dev.pocket.agent.core.model.StopReason.ABORTED -> PocketColors.ToolWarn
        else -> PocketColors.Muted
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            "${item.stopReason.shortLabel()} · ${item.iterations} 轮 · ${item.usage.compactLabel()}",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
