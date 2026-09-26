package dev.pocket.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pocket.agent.ui.AgentViewModel
import dev.pocket.agent.ui.RootScaffold
import dev.pocket.agent.ui.theme.PocketAgentTheme

/**
 * 唯一 Activity：整个界面在 Compose 里。
 *
 * 只做三件事：取到进程级 [dev.pocket.agent.platform.AppContainer]、把 [AgentViewModel]
 * 作为状态宿主、套上主题后交给 [RootScaffold]。任何会话/审批/存储逻辑都不在这里。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AgentViewModel = viewModel()
            PocketAgentTheme {
                RootScaffold(
                    container = vm.container,
                    controller = vm.controller,
                    onStart = vm::startOnce,
                )
            }
        }
    }
}
