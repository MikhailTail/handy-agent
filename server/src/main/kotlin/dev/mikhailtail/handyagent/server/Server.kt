package dev.mikhailtail.handyagent.server

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.io.File

/**
 * 本地 HTTP + WebSocket 服务 —— 复刻 cc-haha 的 `src/server`。
 *
 * 存在的理由：cc-haha 的前端不通过宿主 IPC 取数据，而是直接 `fetch(baseUrl + path)`
 * 访问一个 sidecar 服务（见 `desktop/src/api/client.ts`）。Android 上跑不了那个
 * Windows exe，于是由本模块扮演它。
 *
 * **必须同时提供静态文件与 API**：前端的同源判定依赖页面 origin 与 API 同源 ——
 * `desktopRuntime.ts` 的 `getSameOriginServerUrl()` 在 origin 为 http/https 时直接返回
 * `window.location.origin`；`h5AccessPolicy.ts` 对 loopback 请求判 `local-trusted`，免 token。
 * 一旦两者不同源，就会掉进"需要显式配置 baseUrl"的分支，那时才不得不注入。
 *
 * 只监听 `127.0.0.1`：设备上其他程序访问不到，同时让 loopback 判定成立。
 */
class HandyServer(
    private val staticRoot: File,
    private val projectsDir: File,
    /** cc-haha 的配置目录（含 providers.json）。 */
    private val configDir: File,
    /** 模型上下文窗口，决定压缩阈值。 */
    private val contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    /** 手机操作能力提供者；桌面开发机上恒为 null。 */
    private val mobileProvider: () -> dev.mikhailtail.handyagent.kernel.api.MobileCapability? = { null },
    private val port: Int,
    private val host: String = "127.0.0.1",
) {
    /** 权限模式的单一真源：设置接口写、权限管线读，切换立即生效。 */
    private val permissionMode = PermissionModeHolder()

    private var engine: ApplicationEngine? = null

    fun start() {
        check(engine == null) { "HandyServer already started" }
        engine = embeddedServer(CIO, port = port, host = host) {
            handyModule(staticRoot, projectsDir, configDir, contextWindow, mobileProvider, permissionMode)
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
    }
}

/**
 * 路由装配。做成 `Application` 的扩展而不是塞进 [HandyServer]，是为了让测试能
 * `testApplication { application { handyModule(dir) } }` 直接挂载，不必真的占端口。
 */
fun Application.handyModule(
    staticRoot: File,
    projectsDir: File,
    configDir: File,
    contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
    mobileProvider: () -> dev.mikhailtail.handyagent.kernel.api.MobileCapability? = { null },
    permissionMode: PermissionModeHolder = PermissionModeHolder(),
) {
    install(WebSockets)
    install(requestLogPlugin)
    routing {
        healthRoutes()
        statusRoutes()
        // 本平台做不到的接口要明确报错，不能让兜底的空对象骗过前端。
        unsupportedRoutes()
        sessionsApi(projectsDir)
        settingsRoutes(configDir, permissionMode)
        modelsApi(configDir)
        providerRoutes(configDir)
        // workDir 作为工具解析相对路径的基准（不是沙箱，只是 cwd 语义）。
        frontendChannel(
            projectsDir, configDir, projectsDir.parentFile?.absolutePath.orEmpty(), contextWindow, mobileProvider, permissionMode,
        )
        // 兜底必须排在静态之前：否则未实现的 /api 路径会掉进 SPA 兜底拿到一页 HTML，
        // 前端把它当 JSON 解析，报 "could not be parsed as JSON"，整屏进错误页。
        apiFallback()
        staticRoutes(staticRoot)
    }
}

/**
 * 把每个进来的请求打出来。
 *
 * 这不只是调试便利，而是**穷举 API 清单的手段之一**：前端是必须被满足的消费者，
 * 但它的请求路径散落在几十个 api 客户端文件里，靠读代码容易漏（尤其是模板字面量
 * 拼出来的路径）。让真实前端跑一遍、把请求记下来，比人肉 grep 可靠。
 */
private val requestLogPlugin = createApplicationPlugin("RequestLog") {
    onCall { call ->
        val line = "${call.request.httpMethod.value} ${call.request.uri}"
        println("[req] $line")
        // 同时落盘：Gradle 的 JavaExec 会缓冲子进程 stdout，从管道里常常读不到。
        // 路径走系统属性显式指定 —— 相对路径不可靠，Gradle 的工作目录未必是模块目录。
        val logPath = System.getProperty("handy.reqlog")
        if (logPath != null) {
            runCatching { java.io.File(logPath).appendText(line + "\n") }
        }
    }
}
