package dev.mikhailtail.handyagent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mikhailtail.handyagent.ui.theme.CcRadius2xl
import dev.mikhailtail.handyagent.ui.theme.PocketColors

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
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 上游的 composer 是一个 20dp 圆角面板 + 1px 边框，
            // 靠边框与底色分层，而不是靠阴影或明显的描边色
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 180.dp),
                placeholder = { Text(placeholder, color = PocketColors.Muted) },
                maxLines = 6,
                shape = RoundedCornerShape(CcRadius2xl),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PocketColors.Brand,
                    unfocusedBorderColor = PocketColors.Border,
                    focusedContainerColor = PocketColors.SurfaceHover.copy(alpha = 0.4f),
                    unfocusedContainerColor = PocketColors.SurfaceHover.copy(alpha = 0.25f),
                ),
            )

            // 主按钮是"墨色实心"（上游主张），形状为胶囊
            if (running) {
                OutlinedButton(
                    onClick = onStop,
                    shape = RoundedCornerShape(percent = 50),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("停止") }
            } else {
                Button(
                    onClick = onSend,
                    enabled = canSend && value.isNotBlank(),
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketColors.BtnPrimaryBg,
                        contentColor = PocketColors.BtnPrimaryFg,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
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
