package dev.mikhailtail.handyagent.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.util.pipeline.PipelineContext
// 无参数的 `get { }` 会匹配到旧的 PipelineContext 重载，那里的 call 是扩展属性，
// 必须显式导入才能解析（带路径的 `get("/x") { }` 用的是 RoutingContext，call 是成员）。
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** 与 cc-haha `src/server/api/status.ts` 的 `getVersion()` 回退值保持一致。 */
private const val SERVER_VERSION = "999.0.0-local"

private val startedAt = System.currentTimeMillis()

private suspend fun ApplicationCall.respondJson(body: String) =
    respondText(body, ContentType.Application.Json)

/**
 * `GET /health` —— 存活探针。前端与宿主都用它判断服务是否起来了。
 */
internal fun Route.healthRoutes() {
    get("/health") {
        call.respondJson("""{"status":"ok"}""")
    }
}

/**
 * `/api/status` 系列。
 *
 * 字段名逐个对齐 cc-haha 的 `src/server/api/status.ts`：
 * ```
 * GET /api/status        → { status, version, uptime }
 * GET /api/status/user   → { configDir, projects: string[] }
 * GET /api/status/usage  → { totalInputTokens, totalOutputTokens, totalCost }
 * ```
 * 阶段 0 只做只读且恒为空的版本；接入 :persistence 后 `projects` 会是真的目录列表。
 */
internal fun Route.statusRoutes() {
    route("/api/status") {
        get {
            val uptime = System.currentTimeMillis() - startedAt
            call.respondJson(
                """{"status":"ok","version":"$SERVER_VERSION","uptime":$uptime}""",
            )
        }
        get("/user") {
            call.respondJson("""{"configDir":"","projects":[]}""")
        }
        get("/usage") {
            call.respondJson(
                """{"totalInputTokens":0,"totalOutputTokens":0,"totalCost":0}""",
            )
        }
    }
}

// `/api/sessions` 及其子路由见 SessionRoutes.kt（阶段 1 起接真实转录）。

/**
 * `/ws/{sessionId}` —— 前端的事件通道。
 *
 * cc-haha 用的是自定义 JSON 事件（不是 JSON-RPC），见 `desktop/src/api/websocket.ts`
 * 与 `desktop/src/types/chat.ts`。
 *
 * **对话是从这里发起的**（`user_message`），不是 POST 到 `/chat` —— 那个接口只用来查状态。
 * 这一点看协议定义才确认，凭直觉很容易做错方向。
 */
internal fun Route.frontendChannel(
    projectsDir: File,
    configDir: File,
    workDir: String,
    contextWindow: Int,
    mobileProvider: () -> dev.mikhailtail.handyagent.kernel.api.MobileCapability?,
    permissionMode: PermissionModeHolder,
) {
    // 每轮对话是长任务（流式输出可能持续几十秒），必须与"收消息"的循环并发，
    // 否则一轮跑着的时候收到的 ping 都处理不了，前端会以为断线。
    val chatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    webSocket("/ws/{sessionId}") {
        val sessionId = call.parameters["sessionId"].orEmpty()
        val handler = ChatHandler(projectsDir, configDir, chatScope, workDir, contextWindow, mobileProvider, permissionMode)

        send(Frame.Text("""{"type":"connected","sessionId":"$sessionId"}"""))
        send(Frame.Text("""{"type":"session_state","turnState":"idle"}"""))

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val payload = runCatching { Json.parseToJsonElement(frame.readText()).jsonObject }
                    .getOrNull() ?: continue
                when (payload["type"]?.jsonPrimitive?.contentOrNull()) {
                    // 前端每 30s 发一次 ping 保活；不回 pong 会被判为断线并触发重连。
                    "ping" -> send(Frame.Text("""{"type":"pong"}"""))
                    "user_message" -> handler.onUserMessage(sessionId, this, payload)
                    "stop_generation" -> handler.onStop(sessionId)
                    "sync_state" -> send(Frame.Text("""{"type":"session_state","turnState":"idle"}"""))
                    "permission_response" -> handler.onPermissionResponse(
                        sessionId = sessionId,
                        requestId = payload["requestId"]?.jsonPrimitive?.contentOrNull().orEmpty(),
                        allowed = payload["allowed"]?.jsonPrimitive?.contentOrNull() == "true",
                    )
                }
            }
        } finally {
            // 连接断了要把等待审批的协程唤醒，否则它们会一直挂在超时上。
            handler.onDisconnect(sessionId)
        }
    }
}

/**
 * 静态前端 —— 托管 cc-haha 的 `dist/` 产物。
 *
 * 这是"前端零改动"方案的另一半：页面与 API 同源，`window.location.origin` 就是
 * baseUrl，前端自己去 `desktopRuntime.ts` 的同源分支，不需要任何注入。
 *
 * `default("index.html")` 提供 SPA 兜底：前端用的是 history 路由，深链接
 * （如 `/settings`）在服务端没有对应文件，必须回落到 index.html 由前端自己解析。
 */
/**
 * `/api` 下尚未实现路径的兜底。
 *
 * 两条铁律，都是踩出来的：
 *
 * **1. 绝不返回 HTML。** 前端把所有 `/api` 响应一律按 JSON 解析，拿到 HTML 就报
 * `The server response could not be parsed as JSON` 并整屏进错误页 —— 而那个提示
 * 指不出是哪个端点。真机上这个表现跟"服务没起来"几乎一样，定位成本极高。
 * （我们就是因为缺兜底，前端启动时请求的 9 个端点全落到 SPA 兜底拿到 index.html。）
 *
 * **2. 返回 200 + `{}`，而不是 404。** 这对应协议分级里的 `degraded` ——
 * 前端拿到空对象会用各自的默认值继续渲染，拿到 404 则可能直接进错误分支。
 * 代价是掩盖了"这个端点还没做"，所以下面打一条日志留痕。
 *
 * 已实现的具体路由不受影响：Ktor 按 specificity 选路由，具体路径优先于这个通配。
 * 将来按 `protocol/api-routes.json` 的分级逐个把它们替换成 real 实现。
 */
internal fun Route.apiFallback() {
    route("/api/{...}") {
        // Ktor 2.3 的 handler receiver 是 PipelineContext（RoutingContext 是 3.x 的 API）。
        val degraded: suspend PipelineContext<Unit, ApplicationCall>.(Unit) -> Unit = {
            val path = call.request.path()
            val body = emptyShapeFor(path)
            println("[degraded] ${call.request.httpMethod.value} $path -> $body")
            call.respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
        }
        get(degraded)
        post(degraded)
        put(degraded)
        patch(degraded)
        delete(degraded)
    }
}

/**
 * 未实现路径的**空形态**。
 *
 * 一律返回 `{}` 是不够的 —— 前端有一大批接口按数组消费（`/api/teams`、`/api/adapters`、
 * `/api/skills`…），拿到对象会在 `.map` 上直接抛错，整个页面白屏。
 * 这类"看起来有界面、一点就崩"的现象，根因往往就在这里。
 *
 * 名单按前端 api 目录下各文件的实际用法整理。**判断依据是"前端把它当数组还是对象"**，
 * 不是接口的语义 —— 语义上像列表但前端当对象用的，要跟着前端走。
 */
private fun emptyShapeFor(path: String): String {
    val p = path.trimEnd('/')
    val listLike = listOf(
        "/api/adapters", "/api/agents", "/api/connectors", "/api/mcp", "/api/plugins",
        "/api/skills", "/api/teams", "/api/workflows", "/api/tasks", "/api/tasks/lists",
        "/api/scheduled-tasks", "/api/traces", "/api/public-access", "/api/market/skills",
        "/api/session-collaboration", "/api/computer-use/apps",
        "/api/computer-use/authorized-apps", "/api/open-targets",
        "/api/sessions/recent-projects", "/api/sessions/project-history",
    )
    if (listLike.any { p == it || p.startsWith("$it/") }) return "[]"

    // 少数接口前端直接读顶层字段，给个带键的空对象比裸 `{}` 更安全。
    return when {
        p.startsWith("/api/diagnostics") -> """{"events":[],"status":"ok"}"""
        p == "/api/models" -> """{"models":[],"provider":null}"""
        p == "/api/models/current" -> """{"model":null}"""
        p == "/api/permissions/mode" -> """{"mode":"default"}"""
        p == "/api/filesystem/browse" -> """{"entries":[],"path":""}"""
        p == "/api/search/sessions" -> """{"results":[],"total":0}"""
        else -> "{}"
    }
}

internal fun Route.staticRoutes(root: File) {
    staticFiles("/", root) {
        default("index.html")
    }
}
