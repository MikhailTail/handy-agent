package dev.mikhailtail.handyagent.server

import java.io.File

/**
 * 本地调试入口，只在开发时使用：
 *
 * ```
 * ./gradlew :server:run --args="<dist目录> <projects目录> <配置目录> 3456"
 * ```
 *
 * 存在的意义：**在电脑上就能验证服务端与真实前端产物、真实转录、真实 provider 配置
 * 合不合得上**，不必先打到手机里。配合无头浏览器
 * （`msedge --headless --dump-dom http://127.0.0.1:3456/`）打开，就能看到 React 是否
 * 真的挂载成功、`/api` 下的接口有没有 401 —— 这些在设备上极难定位。
 *
 * 注意本文件里凡是提到路径的地方都别写出「斜杠紧跟星号」——Kotlin 的块注释支持嵌套，
 * 那两个字符合在一起会开始一段新注释，把整块注释吃掉（编译报 Unclosed comment）。
 */
fun main(args: Array<String>) {
    val staticRoot = File(args.getOrElse(0) { "app/src/main/assets/h5" })
    val projectsDir = File(args.getOrElse(1) { "D:/cc-haha/projects" })
    // cc-haha 的配置目录：providers.json 在这里。
    val configDir = File(args.getOrElse(2) { "D:/cc-haha/cc-haha" })
    val port = args.getOrNull(3)?.toIntOrNull() ?: 3456
    // 第 5 个参数可覆盖上下文窗口，用于验证压缩（真实模型窗口是 100 万，很难自然撑满）。
    val contextWindow = args.getOrNull(4)?.toIntOrNull() ?: DEFAULT_CONTEXT_WINDOW

    require(staticRoot.isDirectory) { "静态目录不存在：${staticRoot.absolutePath}" }

    HandyServer(
        staticRoot = staticRoot,
        projectsDir = projectsDir,
        configDir = configDir,
        contextWindow = contextWindow,
        port = port,
    ).start()

    println("static   ${staticRoot.absolutePath}")
    println("projects ${projectsDir.absolutePath} (exists=${projectsDir.isDirectory})")
    println("config   ${configDir.absolutePath} (exists=${configDir.isDirectory})")
    println("provider ${loadActiveProvider(configDir)?.let { "${it.name} / ${it.model}" } ?: "未配置"}")
    println("window   $contextWindow tokens")
    println("  http://127.0.0.1:$port/")

    Thread.currentThread().join()
}
