package dev.mikhailtail.handyagent.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 一条归一化后的消息条目 —— 对应 cc-haha API 的 `MessageEntry`。
 *
 * **归一化规则是从活的 cc-haha 服务实测出来的**（`http://127.0.0.1:58104`），
 * 不是猜的。对照同一会话的 JSONL 与 API 输出，结论是 **1 行 JSONL = 1 条条目**，
 * 只做两件事：
 *
 * 1. `id` 取该行的 `uuid`；
 * 2. `type` 按 content 里的 block 类型重新归类：
 *
 * | JSONL 行的 type | content 里的 block | 归一化后的 type |
 * |---|---|---|
 * | `user` | `text` / `image` | `user` |
 * | `user` | `tool_result` | **`tool_result`** |
 * | `assistant` | `text` / `thinking` | `assistant` |
 * | `assistant` | `tool_use` | **`tool_use`** |
 *
 * 注意 cc-haha **存盘时就把每类 block 单独写成一行**（一条含 thinking + text + tool_use
 * 的回复会落成 3 行），所以这里不需要拆 —— 早期我以为要拆，对照后才发现只是改名。
 *
 * 非消息行（`session-meta` / `ai-title` / `queue-operation` / `file-history-snapshot`）
 * 不进这里，它们供列表与元数据使用。
 */
data class MessageEntry(
    val id: String,
    val type: String,
    val content: JsonElement?,
    val timestamp: String?,
    val parentUuid: String?,
    val isSidechain: Boolean?,
    val cwd: String?,
    val model: String?,
    val usage: JsonElement?,
    val usageKey: String?,
)

/**
 * JSONL 转录读取。
 *
 * 解析策略是**宽松**的：字段会随 cc-haha 版本增减，遇到不认识的键忽略而不是抛错。
 * 转录是用户自己的历史数据，读不动等于丢数据 —— 宁可少认几个字段，也不能整份读不出来。
 */
class TranscriptReader(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    },
) {

    /**
     * 逐行解析一个转录文件。坏行**跳过而不是中断** —— 半行写入（进程被杀）在真实数据里
     * 很常见，不能让其中一行废掉整个会话。
     */
    fun readLines(file: File): List<TranscriptLine> {
        if (!file.isFile) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching { json.decodeFromString<TranscriptLine>(line) }.getOrNull()
            }.toList()
        }
    }

    /** 只取对话消息，并按 [normalizeType] 归类。顺序即文件顺序（已按时间写入）。 */
    fun readMessages(file: File): List<MessageEntry> =
        readLines(file).mapNotNull { it.toMessageEntry() }

    private fun TranscriptLine.toMessageEntry(): MessageEntry? {
        if (type != "user" && type != "assistant") return null
        val uuid = uuid ?: return null

        // model / usage 是 content 的**兄弟字段**，同在 message 对象里。
        // 早先只取了 content，导致 assistant 条目的模型与用量整块丢失 —— 与 cc-haha 对照才发现。
        val messageObj = message?.jsonObject
        val blocks = messageObj?.get("content")?.asArrayOrNull()

        return MessageEntry(
            id = uuid,
            type = normalizeType(originalType = type, blocks = blocks ?: emptyList()),
            content = messageObj?.get("content"),
            timestamp = timestamp,
            parentUuid = parentUuid,
            isSidechain = isSidechain,
            cwd = cwd,
            model = messageObj?.get("model")?.asStringOrNull(),
            usage = messageObj?.get("usage")?.let(::normalizeUsage),
            // cc-haha 用它给同一份 API 响应去重：一次回复会落成多行（thinking / text /
            // tool_use 各一行），每行都重复整个 usage，统计用量时必须按 key 去重。
            // 形态是 `<message.id>\u0000` —— 那个 NUL 是它自己的分隔约定，照搬。
            usageKey = messageObj?.get("id")?.asStringOrNull()?.let { "$it\u0000" },
        )
    }

    /**
     * 依 content 里的 block 类型归类，落到原始 type 上。
     *
     * 实测结论：一条 JSONL 行的 content 只会是单一类别（cc-haha 存盘时已按 block 分行），
     * 但这里仍按"只要出现就归类"来判，对混合内容的容错更好。
     */
    private fun normalizeType(originalType: String, blocks: List<JsonElement>): String {
        val kinds = blocks.mapNotNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull() }
        return when {
            kinds.contains("tool_result") -> "tool_result"
            kinds.contains("tool_use") -> "tool_use"
            else -> originalType
        }
    }
}

/**
 * 只保留 cc-haha 认的四个用量字段。
 *
 * 原始 usage 里还有 `service_tier` / `cache_creation` / `inference_geo` 等一堆字段，
 * 但 cc-haha 的 `normalizeMessageUsage` 做了白名单裁剪后才外发 —— 这是对照 API 响应看出来的
 * （原版的 usage 恰好只有这四个键）。多带字段不会让前端崩，但会让"逐字段一致"永远差一截，
 * 而一致性正是这一层的验收标准。
 */
private val USAGE_KEYS = listOf(
    "input_tokens",
    "output_tokens",
    "cache_read_input_tokens",
    "cache_creation_input_tokens",
)

private fun normalizeUsage(raw: JsonElement): JsonElement? {
    val obj = raw as? JsonObject ?: return null
    val kept = USAGE_KEYS.mapNotNull { key -> obj[key]?.let { key to it } }
    return if (kept.isEmpty()) null else JsonObject(kept.toMap())
}

private fun JsonElement.asArrayOrNull(): List<JsonElement>? =
    (this as? JsonArray)?.toList()

private fun JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonPrimitive.contentOrNull(): String? =
    runCatching { content }.getOrNull()
