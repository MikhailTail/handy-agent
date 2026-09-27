package dev.mikhailtail.handyagent.kernel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Token 估算。
 *
 * **刻意是估算而不是精确计数**：精确计数要按各家 tokenizer 实现，而 provider 是
 * OpenAI/Anthropic 兼容端点、模型还会换。用字符数近似够触发压缩决策 ——
 * 我们只关心"快到窗口了没有"，不需要知道确切是 148237 还是 148921。
 *
 * 系数取 4 字符/token 是英文的常见经验值；**中日韩字符单独按 1 字符≈1 token 算** ——
 * 中文一个字符往往就是一个 token 甚至更多，用 4 字/token 会**严重低估**，
 * 导致该压缩时没压缩，然后在 API 那边撞 400。这个项目里中文是主要输入，
 * 所以这一条不能照抄英文经验值。
 */
object TokenEstimator {

    fun estimate(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    /** 估算一条消息（含结构开销）。 */
    fun estimate(element: JsonElement): Int = when (element) {
        is JsonPrimitive -> estimate(element.content) + OVERHEAD_PER_FIELD
        is JsonArray -> element.sumOf { estimate(it) } + OVERHEAD_PER_FIELD
        is JsonObject -> element.entries.sumOf { (k, v) -> estimate(k) + estimate(v) }
        else -> OVERHEAD_PER_FIELD
    }

    fun estimateAll(messages: List<JsonElement>): Int = messages.sumOf { estimate(it) }

    /** 每条消息的结构开销（role/content 之类的包装）。 */
    private const val OVERHEAD_PER_FIELD = 4

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||      // 中日韩统一表意
            code in 0x3040..0x30FF ||          // 日文假名
            code in 0xAC00..0xD7AF ||          // 韩文
            code in 0x3000..0x303F ||          // 中日韩标点
            code in 0xFF00..0xFFEF             // 全角字符
    }
}

/**
 * 自动压缩的阈值。
 *
 * **公式逐字对齐 cc-haha 的 `src/services/compact/autoCompact.ts`**：
 * ```
 * reservedTokensForSummary = min(20000, contextWindow × 0.25)
 * effectiveContextWindow    = contextWindow − reservedTokensForSummary
 * autoCompactBuffer         = min(13000, effectiveContextWindow / 3)
 * autoCompactThreshold      = effectiveContextWindow − autoCompactBuffer
 * warningThreshold          = autoCompactThreshold − 20000
 * ```
 *
 * 两层"预留"容易看糊涂，含义是：
 * - `reservedTokensForSummary` 是留给**摘要请求本身**的空间 —— 压缩要靠一次模型调用完成，
 *   如果上下文已经撑满，那次调用自己就发不出去，等于没有出路。
 * - `autoCompactBuffer` 是提前量 —— 不能等真的到顶才压，因为一次回复还会产出几千 token。
 */
data class CompactThresholds(
    val contextWindow: Int,
    val reservedForSummary: Int,
    val effectiveContextWindow: Int,
    val autoCompactBuffer: Int,
) {
    val autoCompact: Int = effectiveContextWindow - autoCompactBuffer
    val warning: Int = autoCompact - WARNING_BUFFER
    val error: Int = autoCompact - ERROR_BUFFER

    val isAboveWarning: (Int) -> Boolean = { it >= warning }
    val isAboveError: (Int) -> Boolean = { it >= error }
    val shouldCompact: (Int) -> Boolean = { it >= autoCompact }

    /** 剩余百分比，供界面显示。 */
    fun percentLeft(used: Int): Int =
        (((autoCompact - used).toDouble() / autoCompact) * 100).coerceIn(0.0, 100.0).toInt()

    companion object {
        const val MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20_000
        const val MAX_SUMMARY_RESERVE_FRACTION = 0.25
        const val AUTOCOMPACT_BUFFER_TOKENS = 13_000
        const val WARNING_BUFFER = 20_000
        const val ERROR_BUFFER = 20_000
        const val MANUAL_COMPACT_BUFFER = 3_000

        /** 连续失败这么多次就停止重试 —— 免得在一个必然失败的压缩上反复烧钱。 */
        const val MAX_CONSECUTIVE_FAILURES = 3

        fun forWindow(contextWindow: Int): CompactThresholds {
            val reserved = minOf(
                MAX_OUTPUT_TOKENS_FOR_SUMMARY,
                (contextWindow * MAX_SUMMARY_RESERVE_FRACTION).toInt(),
            )
            val effective = contextWindow - reserved
            // 缓冲区不超过有效窗口的三分之一：窗口很小的模型上，
            // 一个 13000 的固定缓冲区会直接把阈值压到负数，等于永不压缩也永不可用。
            val buffer = minOf(
                AUTOCOMPACT_BUFFER_TOKENS,
                effective / 3,
            )
            return CompactThresholds(
                contextWindow = contextWindow,
                reservedForSummary = reserved,
                effectiveContextWindow = effective,
                autoCompactBuffer = buffer,
            )
        }
    }
}
