package dev.mikhailtail.handyagent.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 用户设置与权限模式。
 *
 * 这两个是**实现级**的接口，不能靠兜底糊弄：
 * - `/api/settings/user` 是设置页「保存」的落点，糊弄的话用户改了什么都不会生效；
 * - `/api/permissions/mode` 决定审批强度，糊弄的话切了模式行为不变。
 *
 * 设置直接存成 `settings.json`（与 cc-haha 同构），读回时原样返回 ——
 * 前端会往里放各种字段，我们不该假装知道全部，只保证**存取不丢**。
 */
internal fun Route.settingsRoutes(configDir: File, permissionModeHolder: PermissionModeHolder) {

    route("/api/settings/user") {
        get {
            call.respondJsonRaw(readSettings(configDir).toString())
        }
        put {
            val input = call.receiveJsonObject()
                ?: return@put call.respondJsonRaw("""{"ok":false,"message":"请求体不是合法 JSON"}""", 400)
            // 与已有设置**合并**而不是覆盖：前端可能只发改动过的字段，
            // 覆盖会让其他设置凭空消失。
            val merged = readSettings(configDir).toMutableMap()
            input.forEach { (k, v) -> merged[k] = v }
            writeSettings(configDir, merged)
            call.respondJsonRaw("""{"ok":true}""")
        }
    }

    route("/api/permissions/mode") {
        get {
            call.respondJsonRaw("""{"mode":"${permissionModeHolder.mode}"}""")
        }
        put {
            val input = call.receiveJsonObject()
                ?: return@put call.respondJsonRaw("""{"ok":false,"message":"请求体不是合法 JSON"}""", 400)
            val mode = (input["mode"] as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }
                ?: return@put call.respondJsonRaw("""{"ok":false,"message":"缺少 mode 字段"}""", 400)

            permissionModeHolder.mode = dev.mikhailtail.handyagent.kernel.PermissionMode.fromWire(mode).toWire()
            call.respondJsonRaw("""{"ok":true,"mode":"${permissionModeHolder.mode}"}""")
        }
    }

    // 输出风格：手机端不做多风格，如实返回默认值而不是 404。
    route("/api/settings/output-styles") {
        get {
            call.respondJsonRaw(
                """{"outputStyle":"default","styles":[{"value":"default","label":"Default","description":"默认风格","source":"built-in"}],"scope":"userSettings","workDir":null}""",
            )
        }
    }
}

/**
 * 当前权限模式。
 *
 * 做成可变的持有者而不是直接读配置文件：它会被 WebSocket 那边的权限管线实时读取，
 * 用户在界面上一切换就要立刻生效，不该走一次磁盘往返。
 */
class PermissionModeHolder(initial: String = "default") {
    @Volatile
    var mode: String = initial
}

private val settingsJson = Json { prettyPrint = true; ignoreUnknownKeys = true; isLenient = true }

private fun settingsFile(configDir: File) = File(configDir, "settings.json")

private fun readSettings(configDir: File): Map<String, kotlinx.serialization.json.JsonElement> {
    val file = settingsFile(configDir)
    if (!file.isFile) return emptyMap()
    return runCatching {
        Json.parseToJsonElement(file.readText()).jsonObject.toMap()
    }.getOrElse { emptyMap() }
}

/** 原子写 —— 设置里可能含 API key，写坏一次用户就得重填。 */
private fun writeSettings(configDir: File, settings: Map<String, kotlinx.serialization.json.JsonElement>) {
    configDir.mkdirs()
    val target = settingsFile(configDir)
    val tmp = File(configDir, "settings.json.tmp")
    tmp.writeText(settingsJson.encodeToString(JsonObject.serializer(), JsonObject(settings)))
    val moved = runCatching {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }.isSuccess
    if (!moved) Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

private suspend fun ApplicationCall.receiveJsonObject(): JsonObject? =
    runCatching { Json.parseToJsonElement(receiveText()).jsonObject }.getOrNull()

private suspend fun ApplicationCall.respondJsonRaw(body: String, status: Int = 200) =
    respondText(body, ContentType.Application.Json, HttpStatusCode.fromValue(status))
