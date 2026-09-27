package dev.mikhailtail.handyagent.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * 往转录里追加条目。
 *
 * **格式必须与 cc-haha 落盘的一致** —— 转录是双方共用的：它在桌面端也要能读回来。
 * 少了 `uuid` / `parentUuid` 链，它那边就会把会话看成断裂的；字段名写错则直接读不出来。
 * 所以这里的字段名都是照着真实转录抄的（见 `TranscriptLine` 的注释）。
 *
 * 追加用 `appendText` 而不是"读进来改再写回去"：转录可以到几十 MB，
 * 整份重写既慢又有在半途失败时损坏文件的风险。JSONL 天生就是追加友好的格式。
 */
class TranscriptWriter(
    private val json: Json = Json { encodeDefaults = false },
) {

    /**
     * 追加一条消息。
     *
     * @param parentUuid 上一条消息的 uuid；用 [lastUuidOf] 取。链断了下游会拼不出会话。
     * @return 本条消息的 uuid
     */
    fun appendMessage(
        file: File,
        sessionId: String,
        cwd: String,
        role: String,
        content: JsonElement,
        parentUuid: String?,
        model: String? = null,
        usage: JsonElement? = null,
        extraMessageFields: Map<String, JsonElement> = emptyMap(),
    ): String {
        val uuid = UUID.randomUUID().toString()
        val line = buildJsonObject {
            put("parentUuid", parentUuid?.let { JsonPrimitive(it) } ?: JsonNull)
            put("isSidechain", JsonPrimitive(false))
            put("type", JsonPrimitive(role))
            put(
                "message",
                buildJsonObject {
                    put("role", JsonPrimitive(role))
                    put("content", content)
                    model?.let { put("model", JsonPrimitive(it)) }
                    usage?.let { put("usage", it) }
                    // message.id 是 usage 去重的依据（见 MessageEntry.usageKey）。
                    put("id", JsonPrimitive(UUID.randomUUID().toString()))
                    extraMessageFields.forEach { (k, v) -> put(k, v) }
                },
            )
            put("uuid", JsonPrimitive(uuid))
            put("timestamp", JsonPrimitive(Instant.now().toString()))
            put("userType", JsonPrimitive("external"))
            put("cwd", JsonPrimitive(cwd))
            put("sessionId", JsonPrimitive(sessionId))
            put("version", JsonPrimitive("999.0.0-local"))
        }

        file.parentFile?.mkdirs()
        file.appendText(line.toString() + "\n")
        return uuid
    }

    /** 追加一条 `session-meta`（新会话首次写入时要有，否则列表里没有 workDir / 权限模式）。 */
    fun appendSessionMeta(
        file: File,
        sessionId: String,
        workDir: String,
        permissionMode: String,
        providerId: String?,
        modelId: String?,
    ) {
        val line = buildJsonObject {
            put("type", JsonPrimitive("session-meta"))
            put("isMeta", JsonPrimitive(true))
            put("workDir", JsonPrimitive(workDir))
            put("permissionMode", JsonPrimitive(permissionMode))
            providerId?.let { put("runtimeProviderId", JsonPrimitive(it)) }
            modelId?.let { put("runtimeModelId", JsonPrimitive(it)) }
            put("timestamp", JsonPrimitive(Instant.now().toString()))
            put("sessionId", JsonPrimitive(sessionId))
        }
        file.parentFile?.mkdirs()
        file.appendText(line.toString() + "\n")
    }

    /**
     * 追加一条系统备注（目前用于压缩边界）。
     *
     * 不参与 `parentUuid` 链 —— 它不是对话消息，只是给用户看的标记。
     * 重开 App 后读转录时能重新看到"这里压缩过"。
     */
    fun appendSystemNote(
        file: File,
        sessionId: String,
        subtype: String,
        text: String,
        tokensBefore: Int,
        tokensAfter: Int,
    ) {
        val line = buildJsonObject {
            put("type", JsonPrimitive("system"))
            put("subtype", JsonPrimitive(subtype))
            put("isMeta", JsonPrimitive(true))
            put("text", JsonPrimitive(text))
            put("tokensBefore", JsonPrimitive(tokensBefore))
            put("tokensAfter", JsonPrimitive(tokensAfter))
            put("timestamp", JsonPrimitive(Instant.now().toString()))
            put("sessionId", JsonPrimitive(sessionId))
        }
        file.parentFile?.mkdirs()
        file.appendText(line.toString() + "\n")
    }

    /** 追加一条 `ai-title`（列表里显示的会话名）。 */
    fun appendAiTitle(file: File, title: String) {
        val line = buildJsonObject {
            put("type", JsonPrimitive("ai-title"))
            put("aiTitle", JsonPrimitive(title))
            put("timestamp", JsonPrimitive(Instant.now().toString()))
        }
        file.appendText(line.toString() + "\n")
    }

    /**
     * 转录里最后一条消息的 uuid —— 作为下一条的 `parentUuid`。
     *
     * 只扫消息行，`session-meta` 之类的没有 uuid 参与链。
     * 逆序读全文件在这里不可取（可能有几十 MB），所以从头扫但只留最后一个。
     */
    fun lastUuidOf(file: File, reader: TranscriptReader = TranscriptReader()): String? =
        reader.readLines(file)
            .filter { it.type == "user" || it.type == "assistant" }
            .mapNotNull { it.uuid }
            .lastOrNull()
}

/** 便利：把 `{role, content}` 拼成转录里的 message 对象形态。 */
fun messageOf(role: String, content: JsonElement): JsonObject = buildJsonObject {
    put("role", JsonPrimitive(role))
    put("content", content)
}
