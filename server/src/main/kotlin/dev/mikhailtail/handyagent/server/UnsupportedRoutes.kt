package dev.mikhailtail.handyagent.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * **本平台做不到**的接口 —— 如实报错，而不是让兜底返回空对象。
 *
 * 这条界限很重要：兜底返回 `{}` 的意思是"接口存在但暂时没数据"，前端会继续往下走；
 * 而这些能力是**在 Android 上根本不成立**的，必须让前端的错误分支接管。
 *
 * 踩过的实例：`POST /api/providers/official` 返回 `{}` 时，前端把"接口不存在"
 * 误判成"成功但没数据"，于是走进了官方 provider 的 OAuth 流程 —— 用户点
 * 「添加模型」看到的是"Claude 官方 / 检测到本机已保存的出厂凭据"，而他要的
 * 明明是填 baseUrl + API key 的自定义表单。**报错比假装成功有用。**
 */
internal fun Route.unsupportedRoutes() {

    // ── 官方账号接入（需要桌面端的 OAuth 回调与浏览器窗口）──────────────
    route("/api/providers/official") {
        post { call.respondUnsupported("官方账号接入需要在桌面端完成（依赖浏览器 OAuth 回调）") }
        get { call.respondUnsupported("官方账号接入需要在桌面端完成") }
    }

    for (path in listOf(
        "/api/haha-oauth",
        "/api/haha-openai-oauth",
        "/api/haha-grok-oauth",
    )) {
        route(path) {
            get { call.respondUnsupported("官方账号登录需要在桌面端完成") }
            post { call.respondUnsupported("官方账号登录需要在桌面端完成") }
            route("/start") {
                get { call.respondUnsupported("官方账号登录需要在桌面端完成") }
                post { call.respondUnsupported("官方账号登录需要在桌面端完成") }
            }
        }
    }

    // ── cc-switch 导入（读的是桌面端配置目录）────────────────────────────
    route("/api/providers/cc-switch") {
        get("/scan") { call.respondUnsupported("cc-switch 导入需要桌面端环境") }
        post("/import") { call.respondUnsupported("cc-switch 导入需要桌面端环境") }
    }

    // ── 桌面专属能力 ─────────────────────────────────────────────────────
    route("/api/settings/cli-launcher") {
        get { call.respondUnsupported("CLI launcher 是桌面端概念") }
    }

    route("/api/diagnostics/open-log-dir") {
        get { call.respondUnsupported("打开日志目录是桌面端能力") }
        post { call.respondUnsupported("打开日志目录是桌面端能力") }
    }

    // ── 远程访问/公网隧道（手机端不需要，也没有对应实现）────────────────
    route("/api/public-access") {
        get { call.respondUnsupported("远程访问配置需要在桌面端完成") }
        post { call.respondUnsupported("远程访问配置需要在桌面端完成") }
    }
}

/**
 * 统一的"本平台不支持"响应。
 *
 * 保留 `error` 字段而不是只给 `message`：前端多处按 `error` 是否存在来判断失败，
 * 只给 message 会被当成正常响应。
 */
private suspend fun ApplicationCall.respondUnsupported(reason: String) {
    respondText(
        """{"error":"UNSUPPORTED_ON_ANDROID","message":${quote(reason)}}""",
        ContentType.Application.Json,
        // 用 501 而不是 200：这是"能力不存在"，不是"参数不对"。
        HttpStatusCode.NotImplemented,
    )
}

private fun quote(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        else -> append(c)
    }
    append('"')
}
