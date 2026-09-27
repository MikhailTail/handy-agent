package dev.mikhailtail.handyagent.ui.doctor

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mikhailtail.handyagent.core.mobile.Capability
import dev.mikhailtail.handyagent.core.mobile.CapabilityStatus
import dev.mikhailtail.handyagent.core.mobile.MobileBridge
import dev.mikhailtail.handyagent.ui.theme.PocketColors

/**
 * 能力体检页。
 *
 * 存在的理由很具体：**Android 13+ 的侧载应用默认开不了无障碍**（受"受限设置"保护）。
 * 用户点开关时会看到"出于安全考虑，此设置当前不可用"，而系统**不会告诉他**要去
 * 应用详情页右上角点「允许受限设置」。没有这页引导，mobile use 根本跑不起来。
 */
@Composable
fun CapabilityDoctorScreen(
    bridge: MobileBridge?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 每次进入或从设置返回都重新读一遍：用户去开了权限回来应当立刻看到变化
    var tick by remember { mutableStateOf(0) }
    val statuses = remember(tick, bridge) { bridge?.capability?.list().orEmpty() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("能力体检", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "手机操作能力依赖系统授权。下面每一项都告诉你当前状态和开启方式。",
            style = MaterialTheme.typography.bodySmall,
            color = PocketColors.Muted,
        )

        if (bridge == null) {
            Text(
                "当前版本没有设备操作能力。",
                style = MaterialTheme.typography.bodyMedium,
                color = PocketColors.ToolWarn,
            )
            return@Column
        }

        statuses.forEach { status ->
            CapabilityCard(
                status = status,
                onFix = { openSettingsFor(context, status.capability) },
            )
        }

        Spacer(Modifier.height(8.dp))

        // 受限设置专项引导：这是侧载包最容易卡住的地方
        RestrictedSettingsCard(context)

        TextButton(onClick = { tick++ }) {
            Text("重新检测")
        }
    }
}

@Composable
private fun CapabilityCard(status: CapabilityStatus, onFix: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status.available) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态点：绿=可用，红=缺失
            Spacer(
                Modifier
                    .size(9.dp)
                    .background(
                        if (status.available) PocketColors.ToolOk else PocketColors.ToolError,
                        CircleShape,
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    labelOf(status.capability),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (status.detail.isNotBlank()) {
                    Text(
                        status.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = PocketColors.Muted,
                    )
                }
            }
            if (!status.available) {
                Button(onClick = onFix) { Text("去开启") }
            }
        }
    }
}

/**
 * 受限设置说明。
 *
 * 不试图检测"是否被受限"——系统没有公开 API 能问。
 * 所以当无障碍开着却没生效时，这页把最可能的原因直接摊开讲。
 */
@Composable
private fun RestrictedSettingsCard(context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "开关点不动 / 提示「当前不可用」？",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Android 13 及以后，从浏览器或文件管理器安装的应用（侧载）默认不允许开启无障碍，" +
                    "需要手动放行一次：\n\n" +
                    "1. 点下面的「打开应用信息」\n" +
                    "2. 点右上角「⋮」\n" +
                    "3. 选择「允许受限设置」\n" +
                    "4. 验证指纹或密码\n" +
                    "5. 再去无障碍里打开本服务",
                style = MaterialTheme.typography.bodySmall,
                color = PocketColors.Muted,
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clickable {
                    openAppDetails(context)
                },
            ) {
                Text(
                    "打开应用信息",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun labelOf(capability: Capability): String = when (capability) {
    Capability.ACCESSIBILITY -> "无障碍服务（读屏幕 / 模拟点击）"
    Capability.NOTIFICATION_LISTENER -> "通知读取"
    Capability.IME -> "自带输入法（中文输入）"
    Capability.FOREGROUND_SERVICE -> "前台服务（后台运行）"
    Capability.BATTERY_UNRESTRICTED -> "电池不受限制"
    Capability.OVERLAY -> "悬浮窗（后台审批卡）"
}

/** 跳到对应的系统设置页；Intent 不存在时静默忽略（不同 ROM 差异很大）。 */
private fun openSettingsFor(context: Context, capability: Capability) {
    val action = when (capability) {
        Capability.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        Capability.NOTIFICATION_LISTENER -> "android.settings.NOTIFICATION_LISTENER_SETTINGS"
        Capability.IME -> Settings.ACTION_INPUT_METHOD_SETTINGS
        Capability.BATTERY_UNRESTRICTED -> Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        else -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    }
    launchSafely(context, Intent(action).takeIf { it.resolveActivity(context.packageManager) != null })
}

private fun openAppDetails(context: Context) {
    launchSafely(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null)),
    )
}

private fun launchSafely(context: Context, intent: Intent?) {
    if (intent == null) return
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** 供设置页判断用：本机是否是 Android 13+（受限设置的适用版本）。 */
fun restrictedSettingsApplies(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
