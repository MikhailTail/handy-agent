package dev.mikhailtail.handyagent.core.model

import dev.mikhailtail.handyagent.core.json.Json

/** 对话角色。注意：工具结果在协议层属于 USER 轮次（两端 API 皆如此）。 */
enum class Role { SYSTEM, USER, ASSISTANT }

/**
 * 图片的元数据 + 磁盘句柄，**不含字节**。
 *
 * 字节以 [id] 为文件名落在应用私有目录里，只在构造 Provider 请求那一刻才由
 * ImageResolver 读出来编码成 base64。这样做的原因：
 * 1. 截图 base64 有数百 KB，若驻留在会话历史里，内存与落盘体积都会失控；
 * 2. 会话 JSON 只写文件名，可安全地持久化与复制。
 */
data class ImageRef(
    val id: String,
    /** image/jpeg | image/png | image/webp */
    val mediaType: String,
    val width: Int,
    val height: Int,
    val byteSize: Int,
) {
    /** 无效的 ref（空 id / 未知类型 / 非正尺寸）在序列化时降级为文本占位，绝不中断整轮。 */
    val valid: Boolean
        get() = id.isNotBlank() && mediaType.isNotBlank() && width > 0 && height > 0
}

/** 解析后的实际载荷，仅在拼装请求时短暂存在。 */
data class ImageData(val mediaType: String, val base64: String)

/** 一条消息由若干内容块组成，块是 UI 时间轴的渲染单元、也是 Provider 序列化单元。 */
sealed interface Block {
    data class Text(val text: String) : Block

    /**
     * 模型推理内容（Anthropic thinking / OpenAI reasoning_content）。
     * [signature] / [redactedData] 用于把 thinking 块原样回传给 Anthropic，
     * 开启 extended thinking 时缺失会导致后续请求 400。
     */
    data class Reasoning(
        val text: String,
        val signature: String? = null,
        val redactedData: String? = null,
    ) : Block

    data class ToolUse(val id: String, val name: String, val input: Json) : Block

    /** 用户带来的图片：分享入口、相册、手动截图。 */
    data class Image(val ref: ImageRef) : Block

    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
        /**
         * 工具产出的图片（例如 mobile_screenshot）。
         *
         * 必须挂在这里、而不是同一条消息里的兄弟 [Image] 块：
         * AgentLoop.validatePairing 要求 tool_result 消息只含 ToolResult，
         * 混入其它块会让历史配对校验失败。
         */
        val images: List<ImageRef> = emptyList(),
    ) : Block
}

data class Message(
    val role: Role,
    val blocks: List<Block>,
    val id: String = "",
) {
    val text: String get() = blocks.filterIsInstance<Block.Text>().joinToString("") { it.text }
    val reasoning: String get() = blocks.filterIsInstance<Block.Reasoning>().joinToString("") { it.text }
    val toolUses: List<Block.ToolUse> get() = blocks.filterIsInstance<Block.ToolUse>()
    val toolResults: List<Block.ToolResult> get() = blocks.filterIsInstance<Block.ToolResult>()

    /** 用户随消息附带的图片；工具产出的图片见 [Block.ToolResult.images]。 */
    val images: List<ImageRef> get() = blocks.filterIsInstance<Block.Image>().map { it.ref }
    val hasToolUse: Boolean get() = blocks.any { it is Block.ToolUse }

    companion object {
        fun user(text: String) = Message(Role.USER, listOf(Block.Text(text)))
        fun assistant(text: String) = Message(Role.ASSISTANT, listOf(Block.Text(text)))
        fun toolResults(results: List<Block.ToolResult>): Message = Message(Role.USER, results)

        /** 文本 + 若干图片的单条 USER 消息；无图时退化为纯文本。 */
        fun userWithImages(text: String, images: List<ImageRef>): Message {
            if (images.isEmpty()) return user(text)
            val blocks = ArrayList<Block>(images.size + 1)
            if (text.isNotEmpty()) blocks.add(Block.Text(text))
            images.forEach { blocks.add(Block.Image(it)) }
            return Message(Role.USER, blocks)
        }
    }
}

/** 推理强度：UI 分段控件 ↔ 各家 API 参数的映射。 */
enum class ReasoningEffort(
    val label: String,
    val anthropicBudget: Int,
    val openAiEffort: String?,
) {
    OFF("off", 0, null),
    LOW("low", 2_048, "low"),
    MEDIUM("medium", 8_192, "medium"),
    HIGH("high", 24_576, "high");

    val enabled: Boolean get() = this != OFF

    companion object {
        val DEFAULT = MEDIUM
        fun fromLabel(label: String?): ReasoningEffort =
            entries.firstOrNull { it.label == label } ?: DEFAULT
    }
}

/** 暴露给模型的可调用工具描述。 */
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: Json,
    /** 只读工具可自动放行，无需审批。 */
    val readOnly: Boolean = false,
)

data class ChatRequest(
    val model: String,
    val system: String,
    val messages: List<Message>,
    val tools: List<ToolSpec> = emptyList(),
    val effort: ReasoningEffort = ReasoningEffort.DEFAULT,
    val maxTokens: Int = 4_096,
    val temperature: Double? = null,
)

enum class StopReason {
    END_TURN,
    TOOL_USE,
    MAX_TOKENS,
    STOP_SEQUENCE,
    REFUSAL,
    ABORTED,
    ERROR,
    UNKNOWN;

    companion object {
        fun fromAnthropic(raw: String?): StopReason = when (raw) {
            "end_turn" -> END_TURN
            "tool_use" -> TOOL_USE
            "max_tokens" -> MAX_TOKENS
            "stop_sequence" -> STOP_SEQUENCE
            "refusal" -> REFUSAL
            null, "" -> UNKNOWN
            else -> UNKNOWN
        }

        fun fromOpenAi(raw: String?): StopReason = when (raw) {
            "stop" -> END_TURN
            "tool_calls", "function_call" -> TOOL_USE
            "length" -> MAX_TOKENS
            "content_filter" -> REFUSAL
            null, "" -> UNKNOWN
            else -> UNKNOWN
        }
    }
}
