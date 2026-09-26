package dev.pocket.agent.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocket.agent.core.model.ReasoningEffort
import dev.pocket.agent.ui.theme.PocketColors
import dev.pocket.agent.ui.timeline.TimelineItem
import dev.pocket.agent.ui.timeline.TimelinePane

/**
 * 对话页：顶栏（模型 / 新会话）+ 时间轴 + 输入栏。
 *
 * 全部状态来自 [ChatUiState]；这里不持有会话逻辑，只负责把意图转成
 * [ChatController] 的方法调用。
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    controller: ChatController,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 跟随最后一条：新条目或流式追加时滚到底。用户手动上滚后仍会被拉回，
    // 因为「正在生成」时跟随底部才是预期行为。
    val last = state.items.lastOrNull()
    val tailSignal = when (last) {
        is TimelineItem.AssistantText -> last.text.length + last.reasoning.length
        is TimelineItem.ToolCall -> last.output.length
        else -> 0
    }
    LaunchedEffect(state.items.size, tailSignal) {
        if (state.items.isNotEmpty()) {
            listState.animateScrollToItem(state.items.lastIndex)
        }
    }

    Column(modifier.fillMaxSize()) {
        ChatHeader(state, controller, onOpenSettings)

        state.notice?.let { notice ->
            NoticeBanner(
                notice,
                tone = if (state.providerId == null) Tone.WARN else Tone.ERR,
                modifier = Modifier.clickable(enabled = state.providerId == null) { onOpenSettings() },
            )
        }

        TimelinePane(
            items = state.items,
            listState = listState,
            modifier = Modifier.weight(1f),
        )

        ComposerBar(
            value = draft,
            onValueChange = { draft = it },
            canSend = state.canSend,
            running = state.running,
            onSend = {
                val text = draft
                draft = ""
                controller.send(text)
            },
            onStop = controller::stop,
        )
    }
}

@Composable
private fun ChatHeader(
    state: ChatUiState,
    controller: ChatController,
    onOpenSettings: () -> Unit,
) {
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.providerName ?: "Pocket Agent",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (state.model.isBlank()) "未配置模型" else state.model,
                        style = MaterialTheme.typography.labelSmall,
                        color = PocketColors.Muted,
                    )
                }

                Box {
                    HeaderChip(
                        text = "模型",
                        onClick = { modelMenu = true },
                        enabled = state.models.isNotEmpty(),
                    )
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        state.models.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m, style = MaterialTheme.typography.bodySmall) },
                                trailingIcon = {
                                    if (m == state.model) {
                                        Text("✓", color = PocketColors.ToolOk)
                                    }
                                },
                                onClick = {
                                    modelMenu = false
                                    controller.setModel(m)
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                Box {
                    HeaderChip(
                        text = state.effort.label,
                        onClick = { effortMenu = true },
                        prefix = "思考",
                    )
                    DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                        ReasoningEffort.entries.forEach { e ->
                            DropdownMenuItem(
                                text = { Text(e.label, style = MaterialTheme.typography.bodySmall) },
                                trailingIcon = {
                                    if (e == state.effort) Text("✓", color = PocketColors.ToolOk)
                                },
                                onClick = {
                                    effortMenu = false
                                    controller.setEffort(e)
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                HeaderChip(
                    text = "新会话",
                    onClick = controller::newSession,
                    chevron = false,
                    enabled = state.items.isNotEmpty() || state.running,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun HeaderChip(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    chevron: Boolean = true,
    prefix: String? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (text == "新会话") {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = if (enabled) PocketColors.ToolAccent else PocketColors.Muted,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            if (prefix != null) {
                Text(
                    "$prefix ",
                    style = MaterialTheme.typography.labelMedium,
                    color = PocketColors.Muted,
                )
            }
            Text(text, style = MaterialTheme.typography.labelMedium)
            if (chevron) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = PocketColors.Muted,
                    modifier = Modifier.width(14.dp),
                )
            }
        }
    }
}
