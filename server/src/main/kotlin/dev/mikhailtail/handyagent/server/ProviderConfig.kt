package dev.mikhailtail.handyagent.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 当前生效的模型供应商配置。
 *
 * 结构对齐 cc-haha 的 `providers.json`（本机实测 schemaVersion 5）：
 * ```json
 * {
 *   "activeId": "606f3086-...",
 *   "providers": [{
 *     "id": "...", "name": "DeepSeek",
 *     "baseUrl": "https://api.deepseek.com/anthropic",
 *     "apiFormat": "anthropic",
 *     "authStrategy": "auth_token",
 *     "apiKey": "sk-...",
 *     "models": { "main": "deepseek-flash", "haiku": "...", "sonnet": "...", "opus": "..." }
 *   }]
 * }
 * ```
 *
 * `authStrategy` 有讲究：实测有的 provider 是 `auth_token`（发 `Authorization: Bearer`），
 * 有的是 `api_key`（发 `x-api-key`）—— 认错头就是 401，且报错信息通常不会指出这一点。
 */
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val useBearerAuth: Boolean,
    val model: String,
) {
    val usable: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** 从 `providers.json` 读当前生效的供应商。文件缺失或损坏时返回 null（而不是抛错）。 */
fun loadActiveProvider(configDir: File): ProviderConfig? {
    val file = File(configDir, "providers.json")
    if (!file.isFile) return null

    return runCatching {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val providers = root["providers"]?.let { it as? kotlinx.serialization.json.JsonArray }
            ?: return@runCatching null
        val activeId = root["activeId"]?.jsonPrimitive?.contentOrNull()

        // 按 activeId 找；找不到就退回第一个（用户可能删过 provider 而 activeId 没更新）。
        val active = providers
            .map { it.jsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull() == activeId }
            ?: providers.firstOrNull()?.jsonObject
            ?: return@runCatching null

        val models = active["models"]?.jsonObject
        ProviderConfig(
            id = active["id"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            name = active["name"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            baseUrl = active["baseUrl"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            apiKey = active["apiKey"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            useBearerAuth = active["authStrategy"]?.jsonPrimitive?.contentOrNull() == "auth_token",
            // 用 main 那个槽位：cc-haha 的三个槽位（haiku/sonnet/opus）是给分级路由用的，
            // 手机端阶段 2 只跑单模型。
            model = models?.get("main")?.jsonPrimitive?.contentOrNull().orEmpty(),
        )
    }.getOrNull()
}

/** 供 `/api/models` 等只读接口用：供应商列表（不含 key）。 */
fun listProviders(configDir: File): List<JsonObject> {
    val file = File(configDir, "providers.json")
    if (!file.isFile) return emptyList()
    return runCatching {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val providers = root["providers"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val activeId = root["activeId"]?.jsonPrimitive?.contentOrNull()
        providers.map { element ->
            val p = element.jsonObject
            kotlinx.serialization.json.buildJsonObject {
                p.forEach { (k, v) ->
                    // apiKey 绝不外发：这是前端展示用的列表，没有理由带上密钥。
                    if (k != "apiKey") put(k, v)
                }
                put("active", kotlinx.serialization.json.JsonPrimitive(
                    p["id"]?.jsonPrimitive?.contentOrNull() == activeId,
                ))
            }
        }
    }.getOrElse { emptyList() }
}

// JSON 取值助手见 JsonExt.kt（同包内重复定义同名 private 扩展会导致解析歧义）。
