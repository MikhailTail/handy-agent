package dev.mikhailtail.handyagent.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 模型与推理强度接口。
 *
 * 响应结构拿活的 cc-haha 实测得到（`/api/models` 与 `/api/models/current`）：
 * ```json
 * GET /api/models         → { models: [{id,name,description,context,supportedReasoningEfforts}], provider: {id,name}|null }
 * GET /api/models/current → { model: {同上单个} }
 * GET /api/effort         → { level, available: [...] }
 * ```
 *
 * 注意 `models` 是**对象数组**不是字符串数组 —— 凭直觉很容易写错，前端拿到的会是
 * `undefined` 而不报错，只是模型选择器空着。
 */
internal fun Route.modelsApi(configDir: File) {

    route("/api/models") {
        get {
            val provider = loadActiveProvider(configDir)
            call.respondRawJson(
                buildJsonObject {
                    put(
                        "models",
                        if (provider == null || !provider.usable) JsonArray(emptyList())
                        else buildJsonArray { add(modelEntry(provider.model)) },
                    )
                    put(
                        "provider",
                        if (provider == null) JsonNull
                        else buildJsonObject {
                            put("id", JsonPrimitive(provider.id))
                            put("name", JsonPrimitive(provider.name))
                        },
                    )
                }.toString(),
            )
        }

        get("/current") {
            val provider = loadActiveProvider(configDir)
            call.respondRawJson(
                buildJsonObject {
                    if (provider == null || !provider.usable) {
                        put("model", JsonNull)
                    } else {
                        put("model", modelEntry(provider.model))
                    }
                }.toString(),
            )
        }
    }

    route("/api/effort") {
        get {
            // 阶段 2 不做推理强度选择，如实报一个固定档位。
            call.respondRawJson(
                buildJsonObject {
                    put("level", JsonPrimitive(DEFAULT_EFFORT))
                    put("available", buildJsonArray { EFFORT_LEVELS.forEach { add(JsonPrimitive(it)) } })
                }.toString(),
            )
        }
        put {
            // 接受但暂不生效：前端切档位时不至于报错，行为与 `level` 恒为默认值一致。
            call.respondRawJson("""{"ok":true}""")
        }
    }
}

/**
 * 单个模型条目的形态。
 *
 * `supportedReasoningEfforts` 的取值来自实测的 DeepSeek 条目；两个 provider
 * （DeepSeek / Kimi）走的是 Anthropic 兼容端点，这套档位是通用的。
 */
private fun modelEntry(modelId: String): JsonElement = buildJsonObject {
    put("id", JsonPrimitive(modelId))
    put("name", JsonPrimitive(modelId))
    put("description", JsonPrimitive("Main model"))
    put("context", JsonPrimitive(""))
    put(
        "supportedReasoningEfforts",
        buildJsonArray { EFFORT_LEVELS.forEach { add(JsonPrimitive(it)) } },
    )
}

private val EFFORT_LEVELS = listOf("low", "medium", "high", "xhigh", "max")
private const val DEFAULT_EFFORT = "max"

private suspend fun io.ktor.server.application.ApplicationCall.respondRawJson(body: String) =
    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
