package dev.mikhailtail.handyagent.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用给定的 baseUrl + apiKey 探测可用的模型列表 —— `POST /api/providers/models`。
 *
 * 这是设置页里"填完 key 点一下拉取模型"那一步。**没有它，添加供应商的流程走不完**。
 *
 * 响应契约（照抄 cc-haha `desktop/src/types/provider.ts`）：
 * ```
 * 成功 → { ok: true,  models: [{id, ownedBy?}], endpoint }
 * 失败 → { ok: false, errorCode, message, httpStatus?, endpointsTried }
 * ```
 *
 * **失败也返回 HTTP 200** —— 前端明确依赖这一点：它用 `errorCode` 分支，
 * 而 `catch` 只留给"我们自己的服务不可达"。若把上游失败做成 4xx/5xx，
 * 前端会把它误判成后端崩了。
 */
object ProviderModelsProbe {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 候选端点。
     *
     * 各家路径不统一：OpenAI 系是 `/v1/models`，而 Anthropic 兼容端点常把
     * `/anthropic` 当基址（DeepSeek 就是 `https://api.deepseek.com/anthropic`），
     * 真正的模型列表却在 `https://api.deepseek.com/v1/models`。
     * 所以既试原样拼接，也试去掉兼容后缀的那一层。
     */
    private fun candidatesFor(baseUrl: String): List<String> {
        val base = baseUrl.trim().trimEnd('/')
        return buildList {
            add("$base/v1/models")
            add("$base/models")
            // 去掉 /anthropic、/openai 这类兼容后缀再试一次。
            for (suffix in listOf("/anthropic", "/openai", "/v1")) {
                if (base.endsWith(suffix)) {
                    val trimmed = base.removeSuffix(suffix)
                    add("$trimmed/v1/models")
                    add("$trimmed/models")
                }
            }
        }.distinct()
    }

    fun probe(baseUrl: String, apiKey: String, useBearerAuth: Boolean): JsonObject {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return failure("missing-config", "请先填写 Base URL 与 API Key", tried = emptyList())
        }

        val tried = mutableListOf<String>()
        var lastStatus: Int? = null
        var lastMessage: String? = null

        for (endpoint in candidatesFor(baseUrl)) {
            tried += endpoint
            val outcome = request(endpoint, apiKey, useBearerAuth)

            when (outcome) {
                is Outcome.Models ->
                    return buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("models", buildJsonArray {
                            outcome.ids.forEach { id ->
                                add(buildJsonObject {
                                    put("id", JsonPrimitive(id))
                                    put("ownedBy", JsonPrimitive(endpoint.substringBefore("/v1")))
                                })
                            }
                        })
                        put("endpoint", JsonPrimitive(endpoint))
                    }

                is Outcome.HttpError -> {
                    lastStatus = outcome.status
                    lastMessage = outcome.body
                    // 401/403 是明确的鉴权失败，换个端点也一样 —— 没必要再试。
                    if (outcome.status == 401 || outcome.status == 403) {
                        return failure("auth-failed", outcome.body.ifBlank { "鉴权失败（HTTP ${outcome.status}）" }, tried, outcome.status)
                    }
                }

                is Outcome.Failed -> lastMessage = outcome.message
            }
        }

        val code = when (lastStatus) {
            404 -> "endpoint-not-found"
            null -> "network"
            else -> "unknown"
        }
        return failure(code, lastMessage ?: "所有候选端点都没能返回模型列表", tried, lastStatus)
    }

    private sealed interface Outcome {
        data class Models(val ids: List<String>) : Outcome
        data class HttpError(val status: Int, val body: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private fun request(endpoint: String, apiKey: String, useBearerAuth: Boolean): Outcome =
        runCatching {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                if (useBearerAuth) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                } else {
                    setRequestProperty("x-api-key", apiKey)
                }
                setRequestProperty("anthropic-version", "2023-06-01")
            }

            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            if (status !in 200..299) {
                Outcome.HttpError(status, body.take(300))
            } else {
                val ids = parseModelIds(body)
                if (ids.isEmpty()) Outcome.Failed("端点返回了 200 但没有可识别的模型列表")
                else Outcome.Models(ids)
            }
        }.getOrElse { Outcome.Failed(it.message ?: "网络请求失败") }

    /**
     * 解析模型列表。
     *
     * 兼容三种常见形态：
     * - OpenAI 系：`{ "data": [{ "id": "gpt-4" }] }`
     * - Anthropic 系：`{ "data": [{ "id": "claude-..." }] }`
     * - 简易形态：`{ "models": ["a", "b"] }` 或 `{ "models": [{ "id": "a" }] }`
     */
    private fun parseModelIds(body: String): List<String> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val array = (root["data"] ?: root["models"])?.let { it as? JsonArray } ?: return emptyList()

        array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull()
                is JsonObject -> element["id"]?.jsonPrimitive?.contentOrNull()
                    ?: element["name"]?.jsonPrimitive?.contentOrNull()
                else -> null
            }
        }.distinct()
    }.getOrElse { emptyList() }

    private fun failure(
        code: String,
        message: String,
        tried: List<String>,
        httpStatus: Int? = null,
    ): JsonObject = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("errorCode", JsonPrimitive(code))
        put("message", JsonPrimitive(message))
        httpStatus?.let { put("httpStatus", JsonPrimitive(it)) }
        put("endpointsTried", buildJsonArray { tried.forEach { add(JsonPrimitive(it)) } })
    }
}

// contentOrNull 见 JsonExt.kt —— 同包内重复定义同名 private 扩展会让调用点解析歧义。
