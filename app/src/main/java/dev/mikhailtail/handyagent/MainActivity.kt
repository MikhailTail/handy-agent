package dev.mikhailtail.handyagent

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose 壳 + WebView 聊天区。
 *
 * 分工按既定方案：**聊天/会话/设置这些由 cc-haha 自己的 React 界面承担**（原版产物，
 * 零改动），而我们自己的原生界面（终端、预览、worktree）以后挂在 WebView 之外。
 * 所以这里先只有一层全屏 WebView，将来的导航骨架会套在它外面。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = (application as HandyApp).serverPort

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (port > 0) {
                        HandyWebView(port = port)
                    } else {
                        // 服务没起来就别给个白屏，直接把原因摆出来。
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("本地服务未能启动（端口 $port）")
                        }
                    }
                }
            }
        }
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
