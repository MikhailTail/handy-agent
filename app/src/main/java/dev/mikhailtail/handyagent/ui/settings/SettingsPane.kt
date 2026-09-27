package dev.mikhailtail.handyagent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.mikhailtail.handyagent.core.mcp.McpConfigParser
import dev.mikhailtail.handyagent.ui.doctor.CapabilityDoctorScreen
import dev.mikhailtail.handyagent.ui.theme.CcThemeId
import dev.mikhailtail.handyagent.core.model.ReasoningEffort
import dev.mikhailtail.handyagent.core.permission.PermissionMode
import dev.mikhailtail.handyagent.core.platform.AgentRuntime
import dev.mikhailtail.handyagent.core.provider.ProviderConfig
import dev.mikhailtail.handyagent.ui.chat.ChatController
import dev.mikhailtail.handyagent.ui.chat.ChatUiState
import dev.mikhailtail.handyagent.ui.theme.CodeTextStyle
import dev.mikhailtail.handyagent.ui.theme.PocketColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页：填密钥、选模型、调权限、配 MCP、看启动状态。
 *
 * 两条刻意的约束：
 * 1. **密钥只进不出**：输入框从不回显已存的 key（存储层只留密文，读回的是明文但不该
 *    在界面上长驻），只显示「已配置」，要换就重填、要删就清除。
 * 2. **改完立刻可用**：所有修改写回 [AgentRuntime] 的存储后调用
 *    [ChatController.reloadProviders]，因此下一轮会话就用新配置；正在跑的一轮不受影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPane(
    state: ChatUiState,
    runtime: AgentRuntime,
    controller: ChatController,
    modifier: Modifier = Modifier,
    themeId: CcThemeId = CcThemeId.DEFAULT,
    onThemeChange: (CcThemeId) -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    // provider 列表（预置 + 用户覆盖，均不含密钥）。
    val providers = remember(state.runtime, state.providerId) { runtime.configuredProviders() }

    // 没配任何密钥时 state.providerId 为 null（[ChatController.refresh] 只认「有密钥的」provider），
    // 所以选择要落成一份本地状态：否则用户切到一个还没填 key 的 provider 时，界面会立刻弹回去，
    // 也就永远填不了那个 key。
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedId = pickedId ?: state.providerId ?: providers.firstOrNull()?.id
    val selected = providers.firstOrNull { it.id == selectedId }

    var keyDraft by rememberSaveable(selectedId) { mutableStateOf("") }
    // 保存/清除密钥后自增，用来强制重新读一遍「已配置」状态。
    var keySavedTick by remember { mutableStateOf(0) }
    val installedKeys = remember(state.providerId, keySavedTick) { runtime.credentials.ids() }

    var mcpText by remember { mutableStateOf(runtime.mcpConfigText()) }
    var mcpStatuses by remember { mutableStateOf(runtime.mcpStatus()) }
    var toolsTick by remember { mutableStateOf(0) }

    // 能力体检：从设置返回后要重新读一遍权限状态，所以用 tick 驱动
    var doctorOpen by rememberSaveable { mutableStateOf(false) }
    var doctorTick by remember { mutableStateOf(0) }

    if (doctorOpen) {
        ModalBottomSheet(onDismissRequest = { doctorOpen = false }) {
            CapabilityDoctorScreen(
                bridge = runtime.toolContext.mobile,
                modifier = Modifier.heightIn(max = 560.dp),
            )
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PaneHeader("设置")

        // ---------------------------------------------------------- 外观
        Section("外观") {
            DropdownRow(
                label = "主题",
                value = themeId.label,
                options = CcThemeId.entries.map { it.label },
                onSelect = { label ->
                    CcThemeId.entries.firstOrNull { it.label == label }?.let(onThemeChange)
                },
            )
            Text(
                "配色沿用 Handy Agent 的「纸·墨·印」体系；六套主题对应上游同一份设计令牌。",
                style = MaterialTheme.typography.labelSmall,
                color = PocketColors.Muted,
            )
        }

        // ---------------------------------------------------------- 模型服务
        Section("模型服务") {
            ProviderPicker(
                providers = providers,
                selected = selected,
                configured = installedKeys,
                onSelect = {
                    pickedId = it
                    controller.setProvider(it)
                },
            )

            if (selected != null) {
                val hasKey = selected.id in installedKeys
                Text(
                    if (hasKey) "API Key：已配置（不会回显）" else "API Key：未配置",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hasKey) PocketColors.ToolOk else PocketColors.ToolWarn,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        placeholder = { Text(if (hasKey) "输入新密钥以覆盖" else "粘贴 API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = CodeTextStyle,
                        shape = MaterialTheme.shapes.medium,
                    )
                    Button(
                        onClick = {
                            runtime.credentials.put(selected.id, keyDraft.trim())
                            keyDraft = ""
                            keySavedTick++
                            // 新密钥可能让「当前 provider」变可用，立刻刷新顶栏与可发送状态。
                            controller.reloadProviders()
                            // 有密钥才能列模型 —— 顺手拉一次，省得用户再手动点刷新
                            controller.refreshModels()
                        },
                        enabled = keyDraft.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("保存") }
                    if (hasKey) {
                        OutlinedButton(
                            onClick = {
                                runtime.credentials.remove(selected.id)
                                keySavedTick++
                                controller.reloadProviders()
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("清除") }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    DropdownRow(
                        label = "模型",
                        value = when {
                            state.modelsLoading -> "获取中…"
                            state.model.isBlank() -> "（默认）"
                            else -> state.model
                        },
                        options = state.models,
                        enabled = state.models.isNotEmpty(),
                        onSelect = { controller.setModel(it) },
                    )
                }
                TextButton(
                    onClick = { controller.refreshModels() },
                    enabled = !state.modelsLoading && selected?.id in installedKeys,
                ) { Text(if (state.modelsLoading) "…" else "刷新") }
            }

            DropdownRow(
                label = "思考强度",
                value = state.effort.label,
                options = ReasoningEffort.entries.map { it.label },
                onSelect = { label ->
                    ReasoningEffort.entries.firstOrNull { it.label == label }?.let { controller.setEffort(it) }
                },
            )

            StepperRow(
                label = "最大迭代",
                value = state.maxIterations,
                hint = "一轮对话里模型最多能调用多少次工具。",
                onChange = { controller.setMaxIterations(it) },
            )
        }

        // -------------------------------------------------------------- 权限
        Section("工具权限") {
            Text(
                "只读工具默认放行；写盘与执行命令默认要你批准。改动对下一轮会话生效。",
                style = MaterialTheme.typography.labelSmall,
                color = PocketColors.Muted,
            )
            val specs = remember(state.runtime, toolsTick) { runtime.toolSpecs() }
            specs.forEach { spec ->
                val mode = state.permissionModes[spec.name]
                ToolModeRow(
                    name = spec.name,
                    readOnly = spec.readOnly,
                    mode = mode,
                    onPick = { controller.setPermissionMode(spec.name, it) },
                )
            }
        }

        // ---------------------------------------------------------------- MCP
        // ---------------------------------------------------------- 设备能力
        Section("设备能力") {
            val bridge = runtime.toolContext.mobile
            val statuses = remember(doctorTick, bridge) { bridge?.capability?.list().orEmpty() }
            val ok = statuses.count { it.available }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth().clickable { doctorOpen = true },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("能力体检", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (statuses.isEmpty()) "当前版本不支持设备操作"
                            else "$ok / ${statuses.size} 项可用 · 点此查看如何开启",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ok == statuses.size) PocketColors.ToolOk else PocketColors.ToolWarn,
                        )
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = PocketColors.Muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        Section("MCP 服务端") {
            val parsed = remember(mcpText) { McpConfigParser.parse(mcpText) }
            Text(
                when {
                    parsed.isEmpty -> "尚未配置。支持 {\"mcpServers\": {...}} 或裸映射两种写法。"
                    parsed.errors.isEmpty() -> "解析到 ${parsed.servers.size} 个服务端，保存后会立即重连。"
                    else -> "解析到 ${parsed.servers.size} 个服务端，${parsed.errors.size} 处问题。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (parsed.errors.isEmpty()) PocketColors.Muted else PocketColors.ToolWarn,
            )
            parsed.errors.forEach { err ->
                Text("· $err", style = MaterialTheme.typography.labelSmall, color = PocketColors.ToolWarn)
            }

            OutlinedTextField(
                value = mcpText,
                onValueChange = { mcpText = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                placeholder = {
                    Text(
                        "{\n  \"mcpServers\": {\n    \"fs\": {\"command\": \"npx\", \"args\": [\"-y\", \"@modelcontextprotocol/server-filesystem\", \"/path\"]}\n  }\n}",
                        style = CodeTextStyle,
                        color = PocketColors.Muted,
                    )
                },
                textStyle = CodeTextStyle,
                shape = MaterialTheme.shapes.medium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            runtime.saveMcpConfig(mcpText)
                            // 重连是阻塞式子进程操作，放到 IO；失败也只是状态里多一条错误。
                            mcpStatuses = withContext(Dispatchers.IO) { runtime.startMcp() }
                            toolsTick++
                            controller.reloadProviders()
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("保存并重连") }
                OutlinedButton(
                    onClick = {
                        mcpText = runtime.mcpConfigText()
                        mcpStatuses = runtime.mcpStatus()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("还原") }
            }

            mcpStatuses.forEach { status ->
                Text(
                    buildString {
                        append(if (status.connected) "✓ " else "✗ ")
                        append(status.id)
                        if (status.connected) {
                            append(" · ${status.toolCount} 个工具")
                        } else if (status.error != null) {
                            append(" · ${status.error}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.connected) PocketColors.ToolOk else PocketColors.ToolError,
                )
            }
        }

        // ------------------------------------------------------------- 运行状态
        Section("运行状态") {
            val rt = state.runtime
            if (rt == null) {
                Text("启动中…", style = MaterialTheme.typography.labelSmall, color = PocketColors.Muted)
            } else {
                KeyValue("工作目录", rt.workspace)
                KeyValue("可用 provider", rt.providers.toString())
                KeyValue("技能", if (rt.skills.isEmpty()) "（无）" else rt.skills.joinToString(", "))
                KeyValue("插件", if (rt.plugins.isEmpty()) "（无）" else rt.plugins.joinToString(", "))
                if (rt.loadErrors.isNotEmpty()) {
                    Text("加载问题", style = MaterialTheme.typography.labelMedium, color = PocketColors.ToolWarn)
                    rt.loadErrors.forEach { err ->
                        Text(
                            "· $err",
                            style = MaterialTheme.typography.labelSmall,
                            color = PocketColors.ToolWarn,
                        )
                    }
                }
                Text(
                    "技能与插件放在 workspace 之外（app 私有目录），Agent 无法自行写入 —— 这是刻意的提权防线。",
                    style = MaterialTheme.typography.labelSmall,
                    color = PocketColors.Muted,
                )
            }
            Spacer(Modifier.width(0.dp))
        }

        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun PaneHeader(title: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(0.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            content()
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ProviderPicker(
    providers: List<ProviderConfig>,
    selected: ProviderConfig?,
    configured: Set<String>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text("服务商", style = MaterialTheme.typography.labelMedium, color = PocketColors.Muted)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable(enabled = providers.isNotEmpty()) { open = true },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selected?.name ?: "（无可用服务商）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (selected != null && selected.id in configured) {
                    Text("已配置", style = MaterialTheme.typography.labelSmall, color = PocketColors.ToolOk)
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = PocketColors.Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            providers.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Text(
                            p.name + if (p.id in configured) "  · 已配置" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingIcon = {
                        if (p.id == selected?.id) Text("✓", color = PocketColors.ToolOk)
                    },
                    onClick = {
                        open = false
                        onSelect(p.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun DropdownRow(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = PocketColors.Muted)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled && options.isNotEmpty()) { open = true },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = PocketColors.Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = MaterialTheme.typography.bodySmall) },
                    trailingIcon = { if (option == value) Text("✓", color = PocketColors.ToolOk) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    hint: String,
    onChange: (Int) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = PocketColors.Muted)
                Text(hint, style = MaterialTheme.typography.labelSmall, color = PocketColors.Muted)
            }
            OutlinedButton(
                onClick = { onChange(value - 1) },
                enabled = value > MIN_ITERATIONS,
                modifier = Modifier.size(44.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("−") }
            Text(
                value.toString(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            OutlinedButton(
                onClick = { onChange(value + 1) },
                enabled = value < MAX_ITERATIONS,
                modifier = Modifier.size(44.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("＋") }
        }
    }
}

@Composable
private fun ToolModeRow(
    name: String,
    readOnly: Boolean,
    mode: PermissionMode?,
    onPick: (PermissionMode) -> Unit,
) {
    val effective = mode ?: if (readOnly) PermissionMode.ALWAYS else PermissionMode.ASK
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = CodeTextStyle,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (mode == null) {
                Text(
                    if (readOnly) "默认放行" else "默认询问",
                    style = MaterialTheme.typography.labelSmall,
                    color = PocketColors.Muted,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            PermissionMode.entries.forEach { candidate ->
                ModeChip(
                    text = modeLabel(candidate),
                    selected = candidate == effective,
                    isDefault = mode == null && candidate == effective,
                    onClick = { onPick(candidate) },
                )
            }
        }
    }
}

@Composable
private fun ModeChip(
    text: String,
    selected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
) {
    val color = when {
        selected && isDefault -> PocketColors.Muted
        selected -> PocketColors.ToolAccent
        else -> PocketColors.Muted
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Text("● ", style = MaterialTheme.typography.labelSmall, color = color)
            }
            Text(text, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Column {
        Text(key, style = MaterialTheme.typography.labelSmall, color = PocketColors.Muted)
        Text(value, style = CodeTextStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun modeLabel(mode: PermissionMode): String = when (mode) {
    PermissionMode.ALWAYS -> "放行"
    PermissionMode.ASK -> "询问"
    PermissionMode.NEVER -> "禁止"
}

private const val MIN_ITERATIONS = 1
private const val MAX_ITERATIONS = 200
