package dev.mikhailtail.handyagent

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose 壳 + WebView 聊天区。
 *
 * 分工：**聊天/会话/设置由 cc-haha 自己的 React 界面承担**（原版产物，零改动），
 * 我们自己的原生界面（权限引导、以后的终端/预览/worktree）挂在 WebView 之外。
 *
 * 权限引导必须在这里，不能在 WebView 里 —— 前端是原版产物，我们不改它；
 * 而"跳去系统设置"本来也只有原生侧能做。
 */
class MainActivity : ComponentActivity() {

    /** 全盘文件访问是否已授权。用 state 是为了从设置返回时能即时刷新界面。 */
    private var storageGranted by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storageGranted = StorageAccess.granted(this)
        val port = (application as HandyApp).serverPort

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        !storageGranted -> StorageGateScreen(
                            onOpenSettings = { StorageAccess.openSettings(this) },
                        )

                        port > 0 -> HandyWebView(port = port)

                        else -> CenterMessage("本地服务未能启动（端口 $port）")
                    }
                }
            }
        }
    }

    /**
     * 从系统设置返回时重新检测。
     *
     * 靠这个而不是靠 `startActivityForResult` —— 设置页不保证以 result 形式返回，
     * 用户也可能中途切走再切回来，`onResume` 是唯一可靠的时机。
     */
    override fun onResume() {
        super.onResume()
        storageGranted = StorageAccess.granted(this)
    }
}

/**
 * 权限引导页。
 *
 * 文案上刻意说清"为什么需要"而不是只报一个权限名 —— 用户面对一个陌生的系统开关时，
 * 需要知道不开会怎样、开了能做什么，否则会倾向于拒绝。
 */
@Composable
private fun StorageGateScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(modifier = Modifier.widthIn(max = 520.dp)) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("还需要一项权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Text(
                    "Handy Agent 要帮你读写手机上的文件，需要「所有文件访问权限」。",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    "不开的话，读取、修改、运行命令这些工具都会失败——" +
                        "Agent 只能看见它自己那个小目录，看不到你的下载、文档和照片。",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Card {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("怎么开", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "点下面的按钮会跳到本应用的权限页，" +
                                "把「允许访问所有文件」打开，然后返回即可。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Android 不允许应用自己申请这项权限，必须你手动开一次。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("去开启")
                }

                Text(
                    "提示：这项授权只影响文件工具。每一步写操作仍会单独弹审批卡，" +
                        "点同意才会执行。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}

@Composable
private fun HandyWebView(port: Int) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    // cc-haha 前端的 Zustand store 会往 localStorage 持久化，
                    // 关掉它会让设置项在重启后丢失。
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mediaPlaybackRequiresUserGesture = false
                }
                // 必须装 WebViewClient，否则页面里的链接会被丢给系统浏览器打开。
                webViewClient = WebViewClient()
                // 用 127.0.0.1 而不是 localhost：前端的同源判定拿的是
                // window.location.origin，混用这两个字面量会被当成跨 origin。
                loadUrl("http://127.0.0.1:$port/")
            }
        },
    )
}
