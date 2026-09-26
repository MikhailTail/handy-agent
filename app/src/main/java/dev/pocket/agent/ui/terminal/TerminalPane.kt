package dev.pocket.agent.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocket.agent.ui.theme.CodeTextStyle
import dev.pocket.agent.ui.theme.PocketColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 终端页：在沙箱 workspace 里手工跑命令，用来验证 Agent 改动的结果。
 *
 * 执行走 [TerminalSession] 注入的 exec（生产 = `ProcessShell.run`），跑在 IO 线程；
 * 这里只维护「显示哪几条」以及输入框。
 */
@Composable
fun TerminalPane(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    workspaceLabel: String = "",
) {
    var command by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf(session.snapshot()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    fun submit() {
        val cmd = command.trim()
        if (cmd.isEmpty() || busy) return
        command = ""
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) { session.run(cmd) }
            entries = session.snapshot()
            busy = false
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("终端", style = MaterialTheme.typography.titleSmall)
                Text(
                    workspaceLabel.ifBlank { "workspace" },
                    style = MaterialTheme.typography.labelSmall,
                    color = PocketColors.Muted,
                    maxLines = 1,
                )
            }
            TextButton(
                onClick = {
                    session.clear()
                    entries = session.snapshot()
                },
                enabled = entries.isNotEmpty(),
            ) { Text("清屏") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = PocketColors.CodeBg,
        ) {
            if (entries.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "命令会在 workspace 内执行，环境变量已白名单化。",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketColors.Muted,
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(entries) { _, entry ->
                            Text(
                                entry.render(),
                                style = CodeTextStyle,
                                color = if (entry.ok) PocketColors.DiffAddText else PocketColors.DiffDelText,
                            )
                        }
                    }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    placeholder = { Text("ls -la", color = PocketColors.Muted) },
                    textStyle = CodeTextStyle,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedButton(
                    onClick = { submit() },
                    enabled = !busy && command.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (busy) "运行中" else "运行") }
            }
        }
    }
}
