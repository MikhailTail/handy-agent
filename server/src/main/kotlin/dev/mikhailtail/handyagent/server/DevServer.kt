package dev.mikhailtail.handyagent.server

import java.io.File

/**
 * 本地调试入口，只在开发时使用：
 *
 * ```
 * ./gradlew :server:run --args="D:/cc-haha-mobile/app/src/main/assets/h5 3456"
 * ```
 *
 * 存在的意义：**在电脑上就能验证服务端与真实前端产物合不合得上**，不必先打到手机里。
 * 配合无头浏览器（`msedge --headless --dump-dom http://127.0.0.1:3456/`）打开，
 * 就能看到 React 是否真的挂载成功、`/api` 下的接口有没有 401 —— 这些在设备上极难定位。
 *
 * 注意本文件里凡是提到路径的地方都别写出「斜杠紧跟星号」——Kotlin 的块注释支持嵌套，
 * 那两个字符合在一起会开始一段新注释，把整块注释吃掉（编译报 Unclosed comment）。
 */
fun main(args: Array<String>) {
    val root = File(args.getOrElse(0) { "app/src/main/assets/h5" })
    val port = args.getOrNull(1)?.toIntOrNull() ?: 3456

    require(root.isDirectory) { "静态目录不存在：${root.absolutePath}" }

    HandyServer(staticRoot = root, port = port).start()
    println("serving ${root.absolutePath}")
    println("  http://127.0.0.1:$port/")

    Thread.currentThread().join()
}
