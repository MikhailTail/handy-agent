package dev.pocket.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocket.agent.ui.theme.PocketColors

/**
 * 底部输入栏。
 *
 * 运行中把「发送」换成「停止」：一轮未结束时不接受新输入（[ChatController.send]
 * 也会再挡一次），避免两轮并发写同一条历史。
 */
@Composable
fun ComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    canSend: Boolean,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "让 Agent 做点什么…",
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 180.dp),
                placeholder = { Text(placeholder, color = PocketColors.Muted) },
                maxLines = 6,
                shape = MaterialTheme.shapes.medium,
            )

            if (running) {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("停止") }
            } else {
                Button(
                    onClick = onSend,
                    enabled = canSend && value.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("发送", Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/** 顶部横幅：未配置 provider、加载出错等需要用户注意的状态。 */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.WARN) {
    val color = when (tone) {
        Tone.WARN -> PocketColors.ToolWarn
        Tone.ERR -> PocketColors.ToolError
        Tone.INFO -> PocketColors.ToolAccent
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

enum class Tone { INFO, WARN, ERR }
