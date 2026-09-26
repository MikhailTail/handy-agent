package dev.pocket.agent.ui.timeline

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.model.StopReason
import dev.pocket.agent.core.provider.TokenUsage

/**
 * 时间轴的一条渲染单元。
 *
 * 刻意是**纯 Kotlin**（零 compose / zero android 依赖）：时间轴的归约逻辑因此可以在
 * 宿主 JVM 上单测，Compose 只负责把这些不可变快照画出来。
 */
sealed interface TimelineItem {

    /** LazyColumn 的稳定 key；同一条目内容变化时 id 不变（流式追加不会重建行）。 */
    val id: Long

    /** 用户输入。 */
    data class UserText(override val id: Long, val text: String) : TimelineItem

    /** 助手回复。[streaming] 为 true 时是逐字追加中的草稿。 */
    data class AssistantText(
        override val id: Long,
        val text: String,
        val reasoning: String = "",
        val streaming: Boolean = false,
    ) : TimelineItem

    /** 一次工具调用。同一张卡片从 RUNNING 就地更新到终态，不新增行。 */
    data class ToolCall(
        override val id: Long,
        val toolUseId: String,
        val name: String,
        val input: Json = Json.Null,
        val status: Status = Status.RUNNING,
        val output: String = "",
        val durationMs: Long = 0L,
    ) : TimelineItem {
        enum class Status { RUNNING, OK, ERROR, DENIED }
    }

    /** 分隔线 / 提示类条目。 */
    data class Note(
        override val id: Long,
        val kind: Kind,
        val text: String,
    ) : TimelineItem {
        enum class Kind { INFO, COMPACTION, ERROR }
    }

    /** 一轮对话结束的落款（stop reason + 累计用量）。 */
    data class RunSummary(
        override val id: Long,
        val stopReason: StopReason,
        val usage: TokenUsage,
        val iterations: Int,
    ) : TimelineItem
}
