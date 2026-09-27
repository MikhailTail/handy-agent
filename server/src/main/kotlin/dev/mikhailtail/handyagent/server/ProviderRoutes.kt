package dev.mikhailtail.handyagent.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 模型供应商的读**写**接口。
 *
 * 这是手机上唯一能配置 API key 的途径 —— 缺了它整个 App 无法对话。
 * 结构对齐 cc-haha 的 `providers.json`（schemaVersion 5）与它的 `/api/providers`：
 *
 * ```
 * GET    /api/providers               → { providers: [...], activeId }
 * POST   /api/providers               → 新建，返回创建后的 provider
 * PUT    /api/providers/:id           → 更新
 * DELETE /api/providers/:id           → 删除
 * POST   /api/providers/:id/activate  → 设为当前
 * GET    /api/providers/auth-status   → { hasAuth, source, activeProvider }
 * ```
 *
 * **关于 apiKey 明文返回**：cc-haha 就是这么做的（它的类型注释写着 "masked"，
 * 但实测返回明文），因为前端编辑时要回填。这里保持一致 —— 服务只监听 loopback，
 * 能读到它的代码本来就能直接读 `providers.json`，脱敏带不来实际收益。
 */
internal fun Route.providerRoutes(configDir: File) {

    route("/api/providers") {

        get {
            val doc = readProvidersDoc(configDir)
            call.respondJsonRaw(
                buildJsonObject {
                    put("providers", doc["providers"] ?: JsonArray(emptyList()))
                    put("activeId", doc["activeId"] ?: JsonNull)
                }.toString(),
            )
        }

        get("/auth-status") {
            val doc = readProvidersDoc(configDir)
            val active = activeProviderOf(doc)
            call.respondJsonRaw(
                buildJsonObject {
                    put("hasAuth", JsonPrimitive(active != null))
                    put("source", JsonPrimitive("handy-agent-provider"))
                    put("activeProvider", JsonPrimitive(active?.get("name")?.asString() ?: ""))
                }.toString(),
            )
        }

        post {
            val input = call.receiveJson() ?: return@post call.respondJsonRaw("""{"error":"INVALID_JSON"}""", 400)
            val doc = readProvidersDoc(configDir).toMutableMap()
            val list = (doc["providers"] as? JsonArray)?.toMutableList() ?: mutableListOf()

            val created = normalizeProvider(input, existing = null)
            list += created
            doc["providers"] = JsonArray(list)
            // 第一个 provider 自动设为当前，否则用户建完还得再点一次"启用"。
            if (doc["activeId"] == null || doc["activeId"] is JsonNull) {
                doc["activeId"] = created["id"]!!
            }
            writeProvidersDoc(configDir, doc)
            call.respondJsonRaw(created.toString())
        }

        put("/{id}") {
            val id = call.parameters["id"].orEmpty()
            val input = call.receiveJson() ?: return@put call.respondJsonRaw("""{"error":"INVALID_JSON"}""", 400)
            val doc = readProvidersDoc(configDir).toMutableMap()
            val list = (doc["providers"] as? JsonArray)?.toMutableList() ?: mutableListOf()

            val index = list.indexOfFirst { it.asObject()?.get("id")?.asString() == id }
            if (index < 0) return@put call.respondJsonRaw("""{"error":"NOT_FOUND"}""", 404)

            // 与已有条目合并：前端只发改动过的字段，没发的要保持原值。
            val merged = mergeProvider(list[index].jsonObject, input, id)
            list[index] = merged
            doc["providers"] = JsonArray(list)
            writeProvidersDoc(configDir, doc)
            call.respondJsonRaw(merged.toString())
        }

        delete("/{id}") {
            val id = call.parameters["id"].orEmpty()
            val doc = readProvidersDoc(configDir).toMutableMap()
            val list = (doc["providers"] as? JsonArray)?.toMutableList() ?: mutableListOf()
            val removed = list.removeAll { it.asObject()?.get("id")?.asString() == id }
            if (!removed) return@delete call.respondJsonRaw("""{"error":"NOT_FOUND"}""", 404)

            doc["providers"] = JsonArray(list)
            // 删掉的正是当前项时，把 activeId 挪到剩下的第一个（或置空）。
            if (doc["activeId"]?.asString() == id) {
                doc["activeId"] = list.firstOrNull()?.asObject()?.get("id") ?: JsonNull
            }
            writeProvidersDoc(configDir, doc)
            call.respondJsonRaw("""{"ok":true}""")
        }

        post("/{id}/activate") {
            val id = call.parameters["id"].orEmpty()
            val doc = readProvidersDoc(configDir).toMutableMap()
            val list = (doc["providers"] as? JsonArray)?.toList() ?: emptyList()
            if (list.none { it.asObject()?.get("id")?.asString() == id }) {
                return@post call.respondJsonRaw("""{"error":"NOT_FOUND"}""", 404)
            }
            doc["activeId"] = JsonPrimitive(id)
            writeProvidersDoc(configDir, doc)
            call.respondJsonRaw("""{"ok":true}""")
        }

        // 阶段 4 会真正实现（拉取远端模型列表 / 连通性测试）。
        // 现在如实返回一个明确的失败，而不是伪造成功 —— 假的"测试通过"会让人以为配置没问题。
        post("/{id}/test") {
            call.respondJsonRaw(
                """{"ok":false,"message":"连通性测试尚未实现，请先保存后在对话中验证"}""",
            )
        }
    }
}

// ─── 读写 ─────────────────────────────────────────────────────────────────────

private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true; isLenient = true }

private fun providersFile(configDir: File) = File(configDir, "providers.json")

private fun readProvidersDoc(configDir: File): Map<String, JsonElement> {
    val file = providersFile(configDir)
    if (!file.isFile) return mapOf("schemaVersion" to JsonPrimitive(5), "providers" to JsonArray(emptyList()))
    return runCatching {
        Json.parseToJsonElement(file.readText()).jsonObject.toMap()
    }.getOrElse {
        // 文件坏了不能让整个功能不可用 —— 备份后从空开始，用户至少还能重新配。
        runCatching {
            file.renameTo(File(configDir, "providers.json.corrupt-${System.currentTimeMillis()}"))
        }
        mapOf("schemaVersion" to JsonPrimitive(5), "providers" to JsonArray(emptyList()))
    }
}

/**
 * 原子写：先写临时文件再 move。
 *
 * 直接覆写有风险 —— 写到一半断电/被杀，`providers.json` 就废了，而里面有用户的 API key，
 * 那是他手头唯一的一份。
 */
private fun writeProvidersDoc(configDir: File, doc: Map<String, JsonElement>) {
    configDir.mkdirs()
    val target = providersFile(configDir)
    val tmp = File(configDir, "providers.json.tmp")
    tmp.writeText(prettyJson.encodeToString(JsonObject.serializer(), JsonObject(doc)))

    val moved = runCatching {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }.isSuccess
    if (!moved) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/** 新建时补齐默认值；`id` 与 `presetId` 由服务端生成。 */
private fun normalizeProvider(input: JsonObject, existing: JsonObject?): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(existing?.get("id")?.asString() ?: UUID.randomUUID().toString()))
    put("presetId", (input["presetId"] ?: existing?.get("presetId")) ?: JsonPrimitive("custom"))
    put("name", (input["name"] ?: existing?.get("name")) ?: JsonPrimitive("未命名"))
    put("apiKey", (input["apiKey"] ?: existing?.get("apiKey")) ?: JsonPrimitive(""))
    put("authStrategy", (input["authStrategy"] ?: existing?.get("authStrategy")) ?: JsonPrimitive("auth_token"))
    put("baseUrl", (input["baseUrl"] ?: existing?.get("baseUrl")) ?: JsonPrimitive(""))
    put("apiFormat", (input["apiFormat"] ?: existing?.get("apiFormat")) ?: JsonPrimitive("anthropic"))
    put("runtimeKind", (input["runtimeKind"] ?: existing?.get("runtimeKind")) ?: JsonPrimitive("anthropic_compatible"))
    put("models", (input["models"] ?: existing?.get("models")) ?: defaultModels(input))
    put("modelContextWindows", (input["modelContextWindows"] ?: existing?.get("modelContextWindows")) ?: JsonObject(emptyMap()))
}

/** 更新时按字段合并：前端只发改动过的字段，没发的要保持原值。 */
private fun mergeProvider(existing: JsonObject, input: JsonObject, id: String): JsonObject = buildJsonObject {
    val keys = existing.keys + input.keys
    for (key in keys) {
        when (key) {
            "id" -> put("id", JsonPrimitive(id))    // id 不可改
            else -> {
                val value = input[key] ?: existing[key] ?: JsonNull
                put(key, value)
            }
        }
    }
}

/** 四个槽位都指向同一个模型 —— 手机端阶段 4 之前只跑单模型。 */
private fun defaultModels(input: JsonObject): JsonObject {
    val model = input["model"]?.asString()
        ?: input["models"]?.asObject()?.get("main")?.asString()
        ?: "default"
    return buildJsonObject {
        listOf("main", "haiku", "sonnet", "opus").forEach { put(it, JsonPrimitive(model)) }
    }
}

private fun activeProviderOf(doc: Map<String, JsonElement>): JsonObject? {
    val list = doc["providers"] as? JsonArray ?: return null
    val activeId = doc["activeId"]?.asString()
    return list.mapNotNull { it.asObject() }
        .firstOrNull { it["id"]?.asString() == activeId }
        ?: list.firstOrNull()?.asObject()
}

// ─── 小工具 ──────────────────────────────────────────────────────────────────

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

private fun JsonElement.asString(): String? =
    (this as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }

private suspend fun io.ktor.server.application.ApplicationCall.receiveJson(): JsonObject? =
    runCatching { Json.parseToJsonElement(receiveText()).jsonObject }.getOrNull()

private suspend fun io.ktor.server.application.ApplicationCall.respondJsonRaw(
    body: String,
    status: Int = 200,
) = respondText(body, ContentType.Application.Json, HttpStatusCode.fromValue(status))
