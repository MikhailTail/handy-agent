package dev.mikhailtail.handyagent.server

import dev.mikhailtail.handyagent.persistence.MessageEntry
import dev.mikhailtail.handyagent.persistence.SessionListItem
import dev.mikhailtail.handyagent.persistence.SessionScanner
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 会话读接口。
 *
 * 响应结构是**拿活的 cc-haha 服务实测出来的**（`http://127.0.0.1:58104`），不是照类型定义猜的：
 *
 * ```
 * GET /api/sessions                 → { sessions: [...], total, index }
 * GET /api/sessions/<id>/messages   → { messages: [...], taskNotifications, page }
 * ```
 *
 * 注意两个容易踩的点：
 * - `/api/sessions` 顶层是**对象**不是数组，会话在 `sessions` 键下；
 * - `/api/sessions`（列表）与 `/api/sessions/<id>/messages`（详情）必须是**两个不同的路由**，
 *   前者匹配 `route("/api/sessions") { get { } }`，后者匹配 `get("/{id}/messages")`。
 */
internal fun Route.sessionsApi(projectsDir: File) {
    val scanner = SessionScanner(projectsDir)

    route("/api/sessions") {

        get {
            val sessions = scanner.listSessions()
            call.respondJsonRaw(
                buildJsonObject {
                    put("sessions", buildJsonArray { sessions.forEach { add(it.toJson()) } })
                    put("total", sessions.size)
                    put("index", currentIndexStatus(sessions.size))
                }.toString(),
            )
        }

        get("/{id}/messages") {
            val id = call.parameters["id"].orEmpty()
            val session = scanner.findSession(id)
            if (session == null) {
                call.respondText(
                    """{"error":"NOT_FOUND","message":"session $id not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
                return@get
            }
            val messages = scanner.messagesOf(session.projectPath, id)
            val transcript = File(File(projectsDir, session.projectPath), "$id.jsonl")
            call.respondJsonRaw(
                buildJsonObject {
                    put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
                    // 任务通知来自 Task 工具的后台任务；阶段 1 还没有 Task 工具，恒为空数组
                    // （不是 null —— 前端会对它做 .map）。
                    put("taskNotifications", JsonArray(emptyList()))
                    put("page", pageInfo(complete = true, transcript = transcript))
                }.toString(),
            )
        }

        get("/{id}") {
            val id = call.parameters["id"].orEmpty()
            val session = scanner.findSession(id)
            if (session == null) {
                call.respondText(
                    """{"error":"NOT_FOUND","message":"session $id not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
                return@get
            }
            // SessionDetail = SessionListItem + messages
            val messages = scanner.messagesOf(session.projectPath, id)
            call.respondJsonRaw(
                (session.toJson() as JsonObject).let { base ->
                    buildJsonObject {
                        base.forEach { (k, v) -> put(k, v) }
                        put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
                    }
                }.toString(),
            )
        }
    }
}

/**
 * 索引状态。
 *
 * 阶段 1 还没有 SQLite 索引（那要等 :persistence 的下一步），所以**如实报 `off`**，
 * 而不是伪造一个 `ready` 骗前端。前端看到 `off` 会走"直接读文件"的路径，功能不受影响。
 */
private fun currentIndexStatus(discovered: Int): JsonElement = buildJsonObject {
    put("mode", "off")
    put("state", "off")
    put("discovered", discovered)
    put("indexed", 0)
    put("degradedSources", 0)
    put("databaseBytes", 0)
    put("walBytes", 0)
    put("lastUpdatedAt", JsonNull)
    put("lastErrorCode", JsonNull)
}

/**
 * 分页信息。
 *
 * 阶段 1 一次返回整个转录（`complete = true`、`hasMore = false`），不做游标分页 ——
 * cc-haha 的游标是不透明 base64 串（内含文件指纹与字节偏移），复刻它没有意义，
 * 等真的遇到大转录的性能问题再设计自己的。**这里如实声明"已完整返回"**，
 * 前端就不会去请求下一页。
 *
 * `sourceVersion` 与 `scannedBytes` 照 cc-haha 的口径填真实值：前者是文件指纹
 * （`dev:ino:size:mtime`，用于失效判断），后者是文件字节数。拿不到 Unix 属性时
 * （Windows 开发机）退化成 `size:mtime` —— 语义等价，只是短一截。
 */
private fun pageInfo(complete: Boolean, transcript: File?): JsonElement = buildJsonObject {
    put("nextCursor", JsonNull)
    put("previousCursor", JsonNull)
    put("hasMore", !complete)
    put("historyComplete", complete)
    put("sourceVersion", transcript?.sourceVersion().orNull())
    put("scannedBytes", transcript?.length() ?: 0)
    put("omittedOversizedEntries", 0)
    put("contextScanBytes", 0)
}

private fun File.sourceVersion(): String? = runCatching {
    val attrs = java.nio.file.Files.readAttributes(
        toPath(),
        "unix:dev,ino,size,lastModifiedTime",
    )
    val dev = attrs["dev"]
    val ino = attrs["ino"]
    val size = attrs["size"]
    val mtime = (attrs["lastModifiedTime"] as? java.nio.file.attribute.FileTime)
        ?.to(java.util.concurrent.TimeUnit.NANOSECONDS)
    "$dev:$ino:$size:$mtime"
}.getOrElse {
    runCatching { "${length()}:${lastModified()}" }.getOrNull()
}

private fun SessionListItem.toJson(): JsonElement = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("title", JsonPrimitive(title))
    put("createdAt", JsonPrimitive(createdAt))
    put("modifiedAt", JsonPrimitive(modifiedAt))
    put("messageCount", JsonPrimitive(messageCount))
    put("projectPath", JsonPrimitive(projectPath))
    put("projectRoot", projectRoot.orNull())
    put("workDir", workDir.orNull())
    put("workDirExists", JsonPrimitive(workDirExists))
    put("workspaceState", workspaceState.orNull())
    put("permissionMode", permissionMode.orNull())
    put("runtimeProviderId", runtimeProviderId.orNull())
    put("runtimeModelId", runtimeModelId.orNull())
    // effortLevel 没值时**整个字段不出现**（对照 cc-haha 是 undefined 而非 null）。
    // 这类"缺字段 vs 显式 null"的差别前端会区分对待，不能想当然。
    effortLevel?.let { put("effortLevel", JsonPrimitive(it)) }
}

/**
 * 消息条目。
 *
 * **可空字段一律"无值就不输出"，而不是输出 null** —— 这是与 cc-haha 逐字段对照出来的：
 * 它的 `parentUuid` / `model` / `usage` 在缺失时是**字段不存在**（JSON 里根本没有这个键），
 * 不是显式 null。前端会区分这两者（`'k' in obj` vs `obj.k === null`），不能想当然。
 */
private fun MessageEntry.toJson(): JsonElement = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("type", JsonPrimitive(type))
    // content 原样透传：它是 Anthropic 的 block 数组，自己重新拼装只会引入失真。
    put("content", content ?: JsonNull)
    timestamp?.let { put("timestamp", JsonPrimitive(it)) }
    parentUuid?.let { put("parentUuid", JsonPrimitive(it)) }
    isSidechain?.let { put("isSidechain", JsonPrimitive(it)) }
    cwd?.let { put("cwd", JsonPrimitive(it)) }
    model?.let { put("model", JsonPrimitive(it)) }
    usage?.let { put("usage", it) }
    usageKey?.let { put("usageKey", JsonPrimitive(it)) }
}

/** `x ?: JsonNull` 的公共父类型是 `Any`，会让 `put` 的重载解析失败 —— 显式转一下。 */
private fun String?.orNull(): JsonElement = this?.let { JsonPrimitive(it) } ?: JsonNull

private suspend fun ApplicationCall.respondJsonRaw(body: String) =
    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
