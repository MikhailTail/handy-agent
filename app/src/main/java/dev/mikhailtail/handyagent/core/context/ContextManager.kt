package dev.mikhailtail.handyagent.core.context

import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.Role
import dev.mikhailtail.handyagent.core.model.ToolSpec
import kotlin.math.ceil

/**
 * 粗略 token 估算。真实 tokenizer 在设备上不可得（也没有必要）：
 * 目标是「量级正确」以便决定何时压缩，而不是精确计费。
 *
 * 经验系数：英文 ≈ 4 字符/token，中文 ≈ 1.5 字符/token，取保守值 3 字符/token，
 * 并对结构化块（tool_use JSON）额外计费，避免低估。
 */
object TokenEstimator {

    private const val CHARS_PER_TOKEN = 3.0
    private const val PER_MESSAGE_OVERHEAD = 4
    private const val PER_BLOCK_OVERHEAD = 3

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        return ceil(text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    fun estimate(message: Message): Int {
        var total = PER_MESSAGE_OVERHEAD
        for (block in message.blocks) {
            total += PER_BLOCK_OVERHEAD
            total += when (block) {
                is Block.Text -> estimate(block.text)
                is Block.Reasoning -> estimate(block.text)
                is Block.ToolUse -> estimate(block.name) + estimate(block.input.encode())
                is Block.Image -> estimateImage(block.ref)
                is Block.ToolResult ->
                    estimate(block.content) + block.images.sumOf { estimateImage(it) }
            }
        }
        return total
    }

    /**
     * 图片 token 估算，取两家的保守最大值，避免低估导致压缩触发太晚：
     * Anthropic 约 像素/750；OpenAI high-detail 按 512×512 分块（基础 85 + 每块 170）。
     * 块自身的开销由调用处的 PER_BLOCK_OVERHEAD 计，这里只算图片内容。
     */
    fun estimateImage(ref: ImageRef): Int {
        if (!ref.valid) return 0
        val w = ref.width.coerceAtLeast(1)
        val h = ref.height.coerceAtLeast(1)
        val anthropic = ceil(w.toDouble() * h / 750.0).toInt()
        val tiles = ceil(w / 512.0).toInt() * ceil(h / 512.0).toInt()
        val openai = 85 + 170 * tiles
        return maxOf(anthropic, openai, 85)
    }

    fun estimate(messages: List<Message>): Int = messages.sumOf { estimate(it) }

    /** 工具 schema 每轮都要随请求发送，必须计入预算。 */
    fun estimateTools(tools: List<ToolSpec>): Int =
        tools.sumOf { estimate(it.name) + estimate(it.description) + estimate(it.inputSchema.encode()) }

    fun estimateTotal(system: String, messages: List<Message>, tools: List<ToolSpec>): Int =
        estimate(system) + estimate(messages) + estimateTools(tools)
}

/** 把一段历史折叠成摘要的实现；Loop 5 会接真实 LLM，这里由测试注入假实现。 */
fun interface Summarizer {
    suspend fun summarize(messages: List<Message>): String
}

data class ContextBudget(
    val maxTokens: Int,
    /** 超过 maxTokens * threshold 就触发压缩。 */
    val threshold: Double = 0.85,
    /** 至少保留最近多少「轮」不压缩。 */
    val keepTurns: Int = 3,
    /** 预留给模型输出的额度，从可用输入里扣除。 */
    val reserveOutputTokens: Int = 4_096,
) {
    init {
        require(maxTokens > 0) { "maxTokens must be > 0" }
        require(threshold in 0.1..1.0) { "threshold must be in (0.1, 1.0]" }
        require(keepTurns >= 1) { "keepTurns must be >= 1" }
        require(reserveOutputTokens >= 0) { "reserveOutputTokens must be >= 0" }
    }

    val inputLimit: Int get() = (maxTokens - reserveOutputTokens).coerceAtLeast(1)
    val triggerTokens: Int get() = (inputLimit * threshold).toInt().coerceAtLeast(1)
}

data class CompactionResult(
    val messages: List<Message>,
    val summary: String = "",
    /** 被折叠进摘要的消息条数；0 表示未压缩。 */
    val compactedMessages: Int = 0,
    val tokensBefore: Int = 0,
    val tokensAfter: Int = 0,
) {
    val didCompact: Boolean get() = compactedMessages > 0
    val savedTokens: Int get() = (tokensBefore - tokensAfter).coerceAtLeast(0)
}

/** 摘要注入历史时使用的前缀；UI 可据此渲染一条「上下文已压缩」分隔线。 */
const val COMPACTION_MARKER: String = "[conversation summary]"

/**
 * 上下文管理器：估算用量、在超阈值时把「旧轮次」折叠为一条摘要。
 *
 * 关键不变量（违反会导致 Provider 400）：
 * 1. 绝不拆散 assistant.tool_use 与其后的 tool_result —— 只在「用户文本轮」边界切分。
 * 2. 压缩后，被保留的第一条消息绝不是一个仅含 tool_result 的 USER 消息。
 * 3. 摘要作为一条 USER 文本消息前置，其后紧跟被保留的完整轮次。
 */
class ContextManager(val budget: ContextBudget) {

    fun estimate(system: String, messages: List<Message>, tools: List<ToolSpec>): Int =
        TokenEstimator.estimateTotal(system, messages, tools)

    fun shouldCompact(system: String, messages: List<Message>, tools: List<ToolSpec>): Boolean {
        if (messages.isEmpty()) return false
        if (splitPoint(messages) == 0) return false
        return estimate(system, messages, tools) >= budget.triggerTokens
    }

    /**
     * 需要压缩时执行压缩（可 force 强制），否则原样返回。
     * [summarizer] 失败不应中断对话：异常会被吞掉并退化为「裁剪旧轮次」。
     */
    suspend fun compact(
        messages: List<Message>,
        summarizer: Summarizer,
        system: String = "",
        tools: List<ToolSpec> = emptyList(),
        force: Boolean = false,
    ): CompactionResult {
        val before = TokenEstimator.estimate(messages)
        val cut = splitPoint(messages)
        if (cut == 0) {
            return CompactionResult(messages, tokensBefore = before, tokensAfter = before)
        }
        // 判定口径必须与 shouldCompact 完全一致（system + messages + tools）：
        // system/tools 每轮都要随请求发送，只拿 messages 去比会把它们漏掉，
        // 结果就是「shouldCompact 说要压缩，compact 却拒绝」，上下文无限增长。
        // tokensBefore/After 仍然只统计历史本身，用于展示压缩收益。
        if (!force && estimate(system, messages, tools) < budget.triggerTokens) {
            return CompactionResult(messages, tokensBefore = before, tokensAfter = before)
        }

        val older = messages.subList(0, cut).toList()
        val recent = messages.subList(cut, messages.size).toList()

        val summary = try {
            summarizer.summarize(older)
        } catch (e: Exception) {
            ""
        }

        val replacement = if (summary.isBlank()) {
            // 摘要不可用时退化为「只保留最近轮次」，并留下显式标记，避免静默丢上下文。
            listOf(Message.user("$COMPACTION_MARKER\n(earlier ${older.size} messages were dropped)"))
        } else {
            listOf(Message.user("$COMPACTION_MARKER\n$summary"))
        }

        val result = replacement + recent
        val after = TokenEstimator.estimate(result)
        return CompactionResult(
            messages = result,
            summary = summary,
            compactedMessages = older.size,
            tokensBefore = before,
            tokensAfter = after,
        )
    }

    /**
     * 找到「最近 keepTurns 轮」的起点；返回 0 表示无可压缩空间。
     * 轮次边界 = 含文本块的 USER 消息（纯 tool_result 的 USER 消息不构成新轮）。
     */
    fun splitPoint(messages: List<Message>): Int {
        if (messages.size <= 1) return 0
        val starts = ArrayList<Int>()
        for (i in messages.indices) {
            if (isTurnStart(messages[i])) starts.add(i)
        }
        if (starts.isEmpty() || starts.first() != 0) starts.add(0, 0)
        if (starts.size <= budget.keepTurns) return 0
        return starts[starts.size - budget.keepTurns]
    }

    private fun isTurnStart(m: Message): Boolean =
        m.role == Role.USER && m.blocks.any { it is Block.Text }
}
