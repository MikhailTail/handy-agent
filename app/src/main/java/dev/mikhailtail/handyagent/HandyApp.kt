package dev.mikhailtail.handyagent

import android.app.Application
import android.util.Log
import dev.mikhailtail.handyagent.server.HandyServer
import java.io.File
import java.net.ServerSocket

/**
 * 进程级宿主：把前端产物铺到磁盘，然后拉起本地服务。
 *
 * 这里对应 cc-haha 里 Electron 主进程做的那件事 —— 选端口、起 sidecar、把地址交给
 * 渲染进程（`desktop/electron/services/serverRuntime.ts`）。差别是 Android 上不需要
 * 跨进程，服务与界面同进程，但**协议边界照留**，不因为同进程就退化成直接函数调用。
 */
class HandyApp : Application() {

    /** 本地服务监听的端口；MainActivity 用它拼 WebView 的地址。 */
    var serverPort: Int = -1
        private set

    override fun onCreate() {
        super.onCreate()

        val staticRoot = File(filesDir, "h5")
        H5Assets.ensureExtracted(
            context = this,
            assetDir = H5_ASSET_DIR,
            target = staticRoot,
            version = BuildConfig.VERSION_NAME,
        )

        // 手机上有自己的数据目录，与桌面 cc-haha 的互不相干：
        // 转录和 provider 配置都落在应用私有空间里，卸载即清除，不会被其他 App 读到。
        val projectsDir = File(filesDir, "projects")
        val configDir = File(filesDir, "config")
        configDir.mkdirs()

        val port = pickLoopbackPort()
        HandyServer(
            staticRoot = staticRoot,
            projectsDir = projectsDir,
            configDir = configDir,
            port = port,
        ).start()
        serverPort = port

        Log.i(TAG, "local server listening on http://127.0.0.1:$port/")
        Log.i(TAG, "  static=$staticRoot projects=$projectsDir config=$configDir")
    }

    /**
     * 由系统分配一个空闲端口。
     *
     * 不写死 3456（cc-haha 的默认值）：loopback 端口是全设备共享的，任何 App 都可能
     * 占用，写死会在那些设备上直接起不来。系统分配后再交给 Ktor 使用，中间存在极小的
     * 竞态窗口，但实践中可忽略；真撞上了 Ktor 会抛错，比静默失败好定位。
     */
    private fun pickLoopbackPort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val TAG = "HandyApp"
        const val H5_ASSET_DIR = "h5"
    }
}
