package dev.mikhailtail.handyagent.kernel

import dev.mikhailtail.handyagent.kernel.api.LlmClient
import dev.mikhailtail.handyagent.kernel.api.LlmEvent
import dev.mikhailtail.handyagent.kernel.api.LlmRequest
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 压缩结果。 */
data class CompactionResult(
    /** 压缩后的消息列表（摘要 + 保留的尾部）。 */
    val messages: List<JsonElement>,
    /** 压缩前的估算 token 数。 */
    val tokensBefore: Int,
    /** 压缩后的估算 token 数。 */
    val tokensAfter: Int,
    /** 摘要正文，供落盘与界面展示。 */
    val summary: String,
    /** 被摘要替换掉的消息条数。 */
    val replacedCount: Int,
)

/**
 * 上下文压缩 —— 对应 cc-haha 的 `src/services/compact/compact.ts`。
 *
 * 做法是**让模型自己写摘要**，而不是简单地丢掉旧消息：直接截断会丢失
 * "用户说过不要这么做""上次那个方案失败了"这类关键约束，模型随后就会重蹈覆辙。
 *
 * 提示词照搬 cc-haha 的结构，三段各有用途：
 * 1. **禁止调用工具**放在最前面 —— 摘要请求若被模型用来调工具，这一轮就白费了
 *    （cc-haha 的注释里记着实测数据：不加这段，失败率从 0.01% 涨到 2.79%）。
 * 2. `<analysis>` 是草稿，逼模型先逐条过一遍再总结，比直接要摘要质量高。
 * 3. `<summary>` 才是真正进上下文的正文，`<analysis>` 会被剥掉。
 */
class ContextCompactor(
    private val llm: LlmClient,
    private val thresholds: CompactThresholds,
    /** 压缩后保留的最近消息条数 —— 最近的上下文对下一步最关键。 */
    private val keepRecent: Int = DEFAULT_KEEP_RECENT,
) {

    fun needed(usedTokens: Int): Boolean = thresholds.shouldCompact(usedTokens)

    /** 供界面显示的提醒级别。 */
    fun warningLevel(usedTokens: Int): WarningLevel = when {
        thresholds.isAboveError(usedTokens) -> WarningLevel.ERROR
        thresholds.isAboveWarning(usedTokens) -> WarningLevel.WARNING
        else -> WarningLevel.NONE
    }

    /**
     * 执行压缩。消息太少时**不做**（压完反而更长），返回 null 让调用方继续。
     */
    suspend fun compact(
        model: String,
        messages: List<JsonElement>,
        system: String? = null,
    ): CompactionResult? {
        if (messages.size <= keepRecent + 2) return null

        val tokensBefore = TokenEstimator.estimateAll(messages)
        val head = messages.dropLast(keepRecent)
        val tail = messages.takeLast(keepRecent)

        val summary = summarize(model, head, system) ?: return null

        val summaryMessage = buildJsonObject {
            put("role", JsonPrimitive("user"))
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put(
                                "text",
                                JsonPrimitive(
                                    "[会话已压缩] 以下是此前对话的摘要，" +
                                        "原文已从上下文中移除。请把它当作已发生的事实继续。\n\n$summary",
                                ),
                            )
                        },
                    )
                },
            )
        }

        val compacted = listOf(summaryMessage) + tail
        return CompactionResult(
            messages = compacted,
            tokensBefore = tokensBefore,
            tokensAfter = TokenEstimator.estimateAll(compacted),
            summary = summary,
            replacedCount = head.size,
        )
    }

    private suspend fun summarize(
        model: String,
        messages: List<JsonElement>,
        system: String?,
    ): String? {
        val request = LlmRequest(
            model = model,
            system = system,
            messages = messages + buildJsonObject {
                put("role", JsonPrimitive("user"))
                put(
                    "content",
                    buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", COMPACT_PROMPT) })
                    },
                )
            },
            // 摘要要能装进预留的空间里；给太多反而会让摘要请求自己超限。
            maxTokens = thresholds.reservedForSummary.coerceAtMost(8_000),
            tools = null,   // 摘要轮不带工具定义，进一步降低它调工具的可能
        )

        val text = StringBuilder()
        runCatching {
            llm.stream(request).collect { event ->
                if (event is LlmEvent.TextDelta) text.append(event.text)
            }
        }.getOrElse { return null }

        return text.toString()
            .let(::stripAnalysisBlock)
            .takeIf { it.isNotBlank() }
    }

    private companion object {
        const val DEFAULT_KEEP_RECENT = 6
    }
}

enum class WarningLevel { NONE, WARNING, ERROR }

/**
 * 剥掉 `<analysis>` 草稿块。
 *
 * 那个块是给模型自己理思路用的，留在上下文里既占 token 又可能被后续轮次误读成"结论"。
 */
internal fun stripAnalysisBlock(raw: String): String {
    val start = raw.indexOf("<analysis>")
    val end = raw.indexOf("</analysis>")
    if (start >= 0 && end > start) {
        return (raw.substring(0, start) + raw.substring(end + "</analysis>".length)).trim()
    }
    return raw.trim()
}

private val COMPACT_PROMPT = """
    CRITICAL: 只输出文本，不要调用任何工具。
    - 不要使用 Read、Bash、Grep、Glob、Edit、Write 或任何其他工具。
    - 你需要的上下文已经在上面了。
    - 调用工具会被拒绝，并浪费你唯一的一次机会。

    请把上面的对话压缩成一份摘要，供后续继续工作时使用。摘要必须包含：

    1. **用户的原始诉求**与他们明确提出的约束（尤其是"不要做某某"这类）。
    2. **已经做出的关键决定**及其理由。
    3. **改动的文件与具体内容**（文件名、函数名、关键代码片段）。
    4. **遇到的错误以及怎么解决的** —— 这些最容易被重复踩到。
    5. **当前进度**：什么做完了、什么还没做、下一步该做什么。

    先把分析写在 <analysis> 标签里，再给正式的 <summary>。
""".trimIndent()
