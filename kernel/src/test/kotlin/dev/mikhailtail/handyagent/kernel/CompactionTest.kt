package dev.mikhailtail.handyagent.kernel

import dev.mikhailtail.handyagent.kernel.api.LlmClient
import dev.mikhailtail.handyagent.kernel.api.LlmEvent
import dev.mikhailtail.handyagent.kernel.api.LlmRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactionTest {

    private fun msg(role: String, text: String) = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("content", buildJsonArray { add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(text)) }) })
    }

    private fun summaryLlm(summary: String = "<analysis>草稿</analysis><summary>要点如下</summary>") =
        object : LlmClient {
            val requests = mutableListOf<LlmRequest>()
            override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
                requests += request
                emit(LlmEvent.TextDelta(summary))
                emit(LlmEvent.MessageStop("end_turn", null))
            }
        }

    // ─── token 估算 ──────────────────────────────────────────────────────────

    /**
     * **中文不能被按 4 字符/token 估**。
     *
     * 这个项目里中文是主要输入。若照搬英文经验值，2000 字中文会被估成 500 token，
     * 实际可能接近 2000 —— 结果是该压缩时没压缩，然后在 API 那边撞 400。
     */
    @Test
    fun `cjk text is not underestimated`() {
        val chinese = "这是一段中文文本".repeat(100)   // 800 字
        val estimate = TokenEstimator.estimate(chinese)

        assertTrue(estimate >= 800, "中文应按接近 1 字符/token 估，实际 $estimate")
    }

    @Test
    fun `english text uses the four character rule`() {
        val english = "a".repeat(400)

        assertEquals(100, TokenEstimator.estimate(english))
    }

    @Test
    fun `mixed text counts both`() {
        val estimate = TokenEstimator.estimate("abc" + "中".repeat(10))

        assertTrue(estimate >= 10, "至少要算上中文字符，实际 $estimate")
    }

    // ─── 阈值公式（对齐 cc-haha）─────────────────────────────────────────────

    @Test
    fun `thresholds follow the cc-haha formula`() {
        // 1M 窗口：reserved = min(20000, 250000) = 20000
        //           effective = 980000；buffer = min(13000, 326666) = 13000
        //           autoCompact = 967000
        val t = CompactThresholds.forWindow(1_000_000)

        assertEquals(20_000, t.reservedForSummary)
        assertEquals(980_000, t.effectiveContextWindow)
        assertEquals(13_000, t.autoCompactBuffer)
        assertEquals(967_000, t.autoCompact)
        assertEquals(947_000, t.warning)     // autoCompact − 20000
        assertEquals(947_000, t.error)
    }

    /**
     * 小窗口下缓冲区要收敛。
     *
     * 固定 13000 的缓冲在 32k 窗口上会把阈值压到 11000，几乎一开口就要压缩 ——
     * 所以 cc-haha 取 `min(13000, effective/3)`，我们照搬。
     */
    @Test
    fun `small windows shrink the buffer instead of eating the whole budget`() {
        val t = CompactThresholds.forWindow(32_000)

        assertEquals(8_000, t.reservedForSummary)          // min(20000, 8000)
        assertEquals(24_000, t.effectiveContextWindow)
        assertEquals(8_000, t.autoCompactBuffer)           // min(13000, 8000)
        assertEquals(16_000, t.autoCompact)
        assertTrue(t.autoCompact > 0, "阈值必须为正，否则小窗口模型一开口就要压缩")
    }

    @Test
    fun `percent left is clamped`() {
        val t = CompactThresholds.forWindow(1_000_000)

        assertEquals(100, t.percentLeft(0))
        assertEquals(0, t.percentLeft(t.autoCompact * 2))   // 超出也不报负数
    }

    // ─── 压缩本身 ────────────────────────────────────────────────────────────

    @Test
    fun `compaction replaces the head with a summary and keeps the tail`() = runTest {
        val compactor = ContextCompactor(summaryLlm(), CompactThresholds.forWindow(200_000), keepRecent = 2)
        val messages = (1..10).map { msg(if (it % 2 == 0) "assistant" else "user", "第 $it 条消息内容") }

        val result = compactor.compact("m", messages)

        assertNotNull(result)
        assertEquals(8, result.replacedCount, "10 条里保留最近 2 条，其余 8 条被摘要替换")
        // 结果 = 一条摘要 + 保留的 2 条
        assertEquals(3, result.messages.size)
        assertTrue(result.tokensAfter < result.tokensBefore, "压缩后必须更小")
    }

    /** `<analysis>` 是草稿，不能进上下文 —— 既占 token 又可能被后续轮次误读成结论。 */
    @Test
    fun `analysis block is stripped from the summary`() = runTest {
        val compactor = ContextCompactor(summaryLlm(), CompactThresholds.forWindow(200_000), keepRecent = 2)
        val messages = (1..10).map { msg("user", "内容 $it") }

        val result = compactor.compact("m", messages)!!

        assertTrue(!result.summary.contains("<analysis>"), result.summary)
        assertTrue(result.summary.contains("要点如下"), result.summary)
    }

    /** 消息太少时不压 —— 压完反而更长，而且会把仅有的上下文也弄丢。 */
    @Test
    fun `compaction is skipped when there is little to compact`() = runTest {
        val compactor = ContextCompactor(summaryLlm(), CompactThresholds.forWindow(200_000), keepRecent = 6)

        assertNull(compactor.compact("m", (1..4).map { msg("user", "短") }))
    }

    /** 摘要请求必须禁止调工具 —— 被拿去调工具这一轮就白费了。 */
    @Test
    fun `summary request forbids tools`() = runTest {
        val llm = summaryLlm()
        val compactor = ContextCompactor(llm, CompactThresholds.forWindow(200_000), keepRecent = 2)

        compactor.compact("m", (1..10).map { msg("user", "内容 $it") })

        assertNull(llm.requests.single().tools, "摘要轮不该带工具定义")
    }

    /** 压缩失败时返回 null，让主循环继续 —— 不能因为压不了就整个对话卡死。 */
    @Test
    fun `failed summary yields null instead of throwing`() = runTest {
        val failing = object : LlmClient {
            override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
                throw dev.mikhailtail.handyagent.kernel.api.LlmException("boom", status = 500)
            }
        }
        val compactor = ContextCompactor(failing, CompactThresholds.forWindow(200_000), keepRecent = 2)

        assertNull(compactor.compact("m", (1..10).map { msg("user", "内容 $it") }))
    }

    // ─── 与主循环的集成 ──────────────────────────────────────────────────────

    /** 超阈值时主循环应发出 Compacted 事件，让界面显示"对话已压缩"的边界提示。 */
    @Test
    fun `engine emits a compacted event when over threshold`() = runTest {
        val llm = summaryLlm()
        val longText = "这是一段很长的中文内容".repeat(20_000)   // 约 2 万 token
        val engine = QueryEngine(
            llm = llm,
            compactor = ContextCompactor(llm, CompactThresholds.forWindow(30_000), keepRecent = 2),
        )
        val messages = (1..12).map { msg(if (it % 2 == 0) "assistant" else "user", longText) }

        val events = engine.run(
            LlmRequest(model = "m", system = null, messages = messages),
        ).toList()

        val compacted = events.filterIsInstance<EngineEvent.Compacted>()
        assertTrue(compacted.isNotEmpty(), "超过阈值时应触发压缩")
        assertTrue(compacted.first().tokensAfter < compacted.first().tokensBefore)
    }

    /** 没超阈值时不该平白多一次模型调用（那是真金白银）。 */
    @Test
    fun `engine does not compact when under threshold`() = runTest {
        val llm = summaryLlm()
        var calls = 0
        val counting = object : LlmClient {
            override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
                calls++
                emit(LlmEvent.TextDelta("好的"))
                emit(LlmEvent.MessageStop("end_turn", null))
            }
        }
        val engine = QueryEngine(
            llm = counting,
            compactor = ContextCompactor(llm, CompactThresholds.forWindow(1_000_000)),
        )

        val events = engine.run(
            LlmRequest(model = "m", system = null, messages = listOf(msg("user", "你好"))),
        ).toList()

        assertTrue(events.none { it is EngineEvent.Compacted })
        assertEquals(1, calls, "不该有额外的摘要调用")
    }
}
