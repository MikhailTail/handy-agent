package dev.mikhailtail.handyagent.core.model

import dev.mikhailtail.handyagent.core.json.Json

/**
 * Message ↔ JSON 的编解码，供会话落盘使用。
 *
 * 两条硬约定：
 * 1. **绝不写 base64** —— 图片只落 [ImageRef] 的文件名与元数据，字节由 ImageBytesStore
 *    单独管。否则一段 mobile use 会话的 JSON 会被几张截图撑到几十 MB。
 * 2. 未知块类型**跳过而不是抛异常** —— 旧版本写下的会话，新版本仍要能打开。
 */
object MessageCodec {

    private const val TAG = "t"
    private const val T_TEXT = "text"
    private const val T_REASONING = "reasoning"
    private const val T_TOOL_USE = "tool_use"
    private const val T_IMAGE = "image"
    private const val T_TOOL_RESULT = "tool_result"

    // ---------------------------------------------------------------- encode

    fun encode(m: Message): Json = Json.obj(
        "role" to Json.of(m.role.name),
        "blocks" to Json.arr(m.blocks.map { encodeBlock(it) }),
    )

    fun encodeAll(messages: List<Message>): Json = Json.arr(messages.map { encode(it) })

    private fun encodeBlock(b: Block): Json = when (b) {
        is Block.Text -> Json.obj(TAG to Json.of(T_TEXT), "text" to Json.of(b.text))

        is Block.Reasoning -> Json.obj(
            TAG to Json.of(T_REASONING),
            "text" to Json.of(b.text),
            "signature" to Json.of(b.signature),
            "redacted" to Json.of(b.redactedData),
        )

        is Block.ToolUse -> Json.obj(
            TAG to Json.of(T_TOOL_USE),
            "id" to Json.of(b.id),
            "name" to Json.of(b.name),
            "input" to b.input,
        )

        is Block.Image -> Json.obj(TAG to Json.of(T_IMAGE), "ref" to encodeRef(b.ref))

        is Block.ToolResult -> Json.obj(
            TAG to Json.of(T_TOOL_RESULT),
            "id" to Json.of(b.toolUseId),
            "content" to Json.of(b.content),
            "is_error" to Json.of(b.isError),
            "images" to Json.arr(b.images.map { encodeRef(it) }),
        )
    }

    /** 只写文件名与元数据；字节在 ImageBytesStore 里，不进会话文件。 */
    private fun encodeRef(r: ImageRef): Json = Json.obj(
        "id" to Json.of(r.id),
        "mt" to Json.of(r.mediaType),
        "w" to Json.of(r.width),
        "h" to Json.of(r.height),
        "n" to Json.of(r.byteSize),
    )

    // ---------------------------------------------------------------- decode

    fun decodeAll(json: Json): List<Message> =
        (json as? Json.Arr)?.items?.mapNotNull { decodeMessage(it) } ?: emptyList()

    /** 解不出角色或一个块都没有时返回 null，由调用方跳过这条消息。 */
    fun decodeMessage(json: Json): Message? {
        val role = Role.entries.firstOrNull { it.name == json.str("role") } ?: return null
        val blocks = json.array("blocks").mapNotNull { decodeBlock(it) }
        if (blocks.isEmpty()) return null
        return Message(role, blocks)
    }

    private fun decodeBlock(json: Json): Block? = when (json.str(TAG)) {
        T_TEXT -> json.str("text")?.let { Block.Text(it) }

        T_REASONING -> Block.Reasoning(
            text = json.str("text").orEmpty(),
            signature = json.str("signature"),
            redactedData = json.str("redacted"),
        )

        T_TOOL_USE -> {
            val id = json.str("id")
            val name = json.str("name")
            if (id == null || name == null) null
            else Block.ToolUse(id, name, json.obj("input") ?: Json.obj())
        }

        T_IMAGE -> decodeRef(json.obj("ref"))?.let { Block.Image(it) }

        T_TOOL_RESULT -> {
            val id = json.str("id")
            if (id == null) null
            else Block.ToolResult(
                toolUseId = id,
                content = json.str("content").orEmpty(),
                isError = json.bool("is_error") ?: false,
                images = json.array("images").mapNotNull { decodeRef(it) },
            )
        }

        // 未知块类型（旧客户端写的新字段、或未来版本新增的块）直接跳过。
        else -> null
    }

    private fun decodeRef(json: Json?): ImageRef? {
        if (json == null) return null
        val id = json.str("id") ?: return null
        val ref = ImageRef(
            id = id,
            mediaType = json.str("mt").orEmpty(),
            width = json.int("w") ?: 0,
            height = json.int("h") ?: 0,
            byteSize = json.int("n") ?: 0,
        )
        return if (ref.valid) ref else null
    }
}
