package dev.pocket.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import dev.pocket.agent.PocketAgentApp
import dev.pocket.agent.platform.AppContainer
import dev.pocket.agent.ui.chat.ChatController
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 承载 [ChatController] 的 ViewModel。
 *
 * 为什么必须是 ViewModel 而不是 `remember`：旋转屏幕 / 主题切换会重建 Activity，
 * 但**一轮对话不能因此断掉**。ViewModel 活过配置变更，[ChatController] 的
 * `viewModelScope` 也就活过配置变更 —— 正在流式的回复会接着往下走。
 *
 * scope 用 `viewModelScope`（Dispatchers.Main.immediate）：[ChatController] 约定所有
 * 状态写入都在主线程，UI 才能直接 diff 而不需要额外同步。
 */
class AgentViewModel(app: Application) : AndroidViewModel(app) {

    val container: AppContainer = (app as PocketAgentApp).container

    val controller: ChatController = ChatController(
        runtimeProvider = { container.runtime },
        approvals = container.approvals,
        scope = viewModelScope,
    )

    private val started = AtomicBoolean(false)

    /** 幂等：配置变更后重组会再次调用，但启动逻辑只应执行一次。 */
    fun startOnce() {
        if (started.compareAndSet(false, true)) {
            viewModelScope.launch { controller.start() }
        }
    }

    override fun onCleared() {
        // 界面真正销毁：解绑审批中继，此后任何审批都按拒绝处理（fail-closed）。
        controller.dispose()
    }
}
