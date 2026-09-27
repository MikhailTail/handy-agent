package dev.mikhailtail.handyagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.mikhailtail.handyagent.core.platform.AgentRuntime
import dev.mikhailtail.handyagent.platform.AppContainer
import dev.mikhailtail.handyagent.ui.chat.ApprovalDialog
import dev.mikhailtail.handyagent.ui.chat.ChatController
import dev.mikhailtail.handyagent.ui.chat.ChatScreen
import dev.mikhailtail.handyagent.ui.export.rememberExportHost
import dev.mikhailtail.handyagent.ui.files.FilesPane
import dev.mikhailtail.handyagent.ui.settings.SettingsPane
import dev.mikhailtail.handyagent.ui.theme.CcThemeId
import dev.mikhailtail.handyagent.ui.theme.PocketColors
import dev.mikhailtail.handyagent.ui.terminal.TerminalPane
import dev.mikhailtail.handyagent.ui.terminal.TerminalSession

/** 底部导航的四个页面。顺序即「从最常用到最少用」。 */
enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("对话", Icons.Filled.Send),
    FILES("文件", Icons.Filled.List),
    TERMINAL("终端", Icons.Filled.Build),
    SETTINGS("设置", Icons.Filled.Settings),
}

/**
 * 应用外框：底部导航 + 页面切换 + 全局审批卡。
 *
 * 审批卡放在**外框**而不是对话页里的理由：审批属于整个会话（工具可能在用户已经切到
 * 文件页时才被调用），挂在导航之上才能保证「任何页面都能批」，也不会因为在页面切换时
 * 重组而丢掉挂起的那次调用。
 */
@Composable
fun RootScaffold(
    container: AppContainer,
    controller: ChatController,
    modifier: Modifier = Modifier,
    onStart: () -> Unit = {},
    themeId: CcThemeId = CcThemeId.DEFAULT,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    val state by controller.state.collectAsState()

    // 冷启动的重活（建目录、扫技能、连 MCP）只跑一次。
    LaunchedEffect(Unit) { onStart() }

    // 回到前台时重读设置：用户可能刚在别处改过配置，或从后台被杀后重建了 provider。
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // 终端会话跟随 AppContainer：shell 是长生命周期的，会话只是它的记账外壳。
    val terminal = remember(container) {
        TerminalSession(exec = { cmd -> container.runtime.shell.run(cmd) })
    }
    val runtime: AgentRuntime = container.runtime

    // 导出入口挂在根页面：ActivityResult 的注册是全局按 key 的，若由各页面自己 remember，
    // 切页会注销掉正在等用户挑保存位置的那次导出 —— 与审批卡同一套理由。
    val exportHost = rememberExportHost()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(containerColor = PocketColors.SurfaceSidebar) {
                Tab.entries.forEach { candidate ->
                    NavigationBarItem(
                        selected = candidate == tab,
                        onClick = { tab = candidate },
                        icon = { Icon(candidate.icon, contentDescription = candidate.label) },
                        label = { Text(candidate.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PocketColors.OnAccentSoft,
                            selectedTextColor = PocketColors.TextPrimary,
                            indicatorColor = PocketColors.AccentSoft,
                            unselectedIconColor = PocketColors.Muted,
                            unselectedTextColor = PocketColors.Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.CHAT -> ChatScreen(
                    state = state,
                    controller = controller,
                    onOpenSettings = { tab = Tab.SETTINGS },
                )

                Tab.FILES -> FilesPane(
                    workspace = container.layout.workspace,
                    host = exportHost,
                )

                Tab.TERMINAL -> TerminalPane(
                    session = terminal,
                    workspaceLabel = container.layout.workspace.absolutePath,
                )

                Tab.SETTINGS -> SettingsPane(
                    state = state,
                    runtime = runtime,
                    controller = controller,
                    themeId = themeId,
                    onThemeChange = container::setTheme,
                )
            }
        }
    }

    // 审批卡：有挂起的请求就必然可见，且返回键等于「拒绝这一次」。
    state.approval?.let { pending ->
        BackHandler(enabled = true) { controller.dismissApproval() }
        ApprovalDialog(
            pending = pending,
            onResolve = controller::resolveApproval,
            onDismiss = controller::dismissApproval,
        )
    }
}
