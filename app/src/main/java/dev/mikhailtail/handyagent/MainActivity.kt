package dev.mikhailtail.handyagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mikhailtail.handyagent.core.platform.FileImageBytesStore
import dev.mikhailtail.handyagent.ui.AgentViewModel
import dev.mikhailtail.handyagent.ui.RootScaffold
import dev.mikhailtail.handyagent.ui.image.LocalImageStore
import dev.mikhailtail.handyagent.ui.theme.HandyTheme

/**
 * 唯一 Activity：整个界面在 Compose 里。
 *
 * 只做三件事：取到进程级 [dev.mikhailtail.handyagent.platform.AppContainer]、把 [AgentViewModel]
 * 作为状态宿主、套上主题后交给 [RootScaffold]。任何会话/审批/存储逻辑都不在这里。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AgentViewModel = viewModel()
            // 图片字节存储：时间轴与审批卡都要显示截图。用 CompositionLocal 提供，
            // 免得把 store 一路透传到每个渲染函数。
            val imageStore = remember(vm.container) {
                FileImageBytesStore(vm.container.layout.images)
            }
            // 主题跟着 AppContainer 里的 Flow 走，设置页一改整个界面立刻重组
            val themeId by vm.container.theme.collectAsState()
            HandyTheme(themeId = themeId) {
                CompositionLocalProvider(LocalImageStore provides imageStore) {
                    RootScaffold(
                        container = vm.container,
                        controller = vm.controller,
                        onStart = vm::startOnce,
                        themeId = themeId,
                    )
                }
            }
        }
    }
}
