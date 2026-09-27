package dev.mikhailtail.handyagent.ui.timeline

import dev.mikhailtail.handyagent.core.context.COMPACTION_MARKER
import dev.mikhailtail.handyagent.core.loop.AgentEvent
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.Role
import dev.mikhailtail.handyagent.core.model.StopReason
import dev.mikhailtail.handyagent.core.provider.TokenUsage

/**
 * [AgentEvent] → [TimelineItem] 的纯函数式归约。
 *
 * 不变量：
 * 1. 一轮里发生的每个事件都只影响**已存在**的条目或追加一条新条目，从不因为流式增量
 *    反复重建整条时间轴（`id` 稳定，LazyColumn 才能保持滚动位置）。
 * 2. 流式文本先落在一个 `streaming = true` 的草稿条目上；收到 [AgentEvent.AssistantMessage]
 *    后用**权威文本**就地覆盖并收尾 —— 因此 delta 与最终消息不会重复显示。
 * 3. 工具卡片以 `toolUseId` 匹配，[AgentEvent.ToolStarted] 建卡、[AgentEvent.ToolFinished] 就地更新。
 *
 * 全部方法在单线程（ViewModel 的 IO 协程）中调用；加锁只是防御「停止/新建」时的竞态。
 */
class TimelineReducer {

    private val items = ArrayList<TimelineItem>()
    private var nextId = 1L
    private var liveAssistantId: Long? = null

    @Synchronized
    fun snapshot(): List<TimelineItem> = items.toList()

    /** 清空并**重置 id 计数**：clear 之后是新会话，时间轴从头开始，键不会与旧条目冲突。 */
    @Synchronized
    fun clear() {
        items.clear()
        liveAssistantId = null
        nextId = 1L
    }

    /**
     * 用一段已落盘的历史整体替换时间轴（切换会话时使用）。
     *
     * id 计数从重建结果的最大值接续，避免与既有条目的 key 冲突导致 LazyColumn 错位。
     */
    @Synchronized
    fun replaceAll(loaded: List<TimelineItem>) {
        items.clear()
        items.addAll(loaded)
        liveAssistantId = null
        nextId = (loaded.maxOfOrNull { it.id } ?: 0L) + 1L
    }

    /** 用户消息不经事件流，由 UI 直接落一条。 */
    @Synchronized
    fun user(text: String, images: List<ImageRef> = emptyList()): List<TimelineItem> {
        sealLive()
        items.add(TimelineItem.UserText(newId(), text, images))
        return snapshot()
    }

    @Synchronized
    fun note(kind: TimelineItem.Note.Kind, text: String): List<TimelineItem> {
        sealLive()
        items.add(TimelineItem.Note(newId(), kind, text))
        return snapshot()
    }

    @Synchronized
    fun apply(event: AgentEvent): List<TimelineItem> {
        when (event) {
            is AgentEvent.IterationStarted -> Unit // UI 不关心轮次计数，落款里已有
            is AgentEvent.TextDelta -> appendLive(text = event.text)
            is AgentEvent.ReasoningDelta -> appendLive(reasoning = event.text)
            is AgentEvent.AssistantMessage -> finalizeAssistant(event)
            is AgentEvent.ToolStarted -> items.add(
                TimelineItem.ToolCall(
                    id = newId(),
                    toolUseId = event.toolUseId,
                    name = event.name,
                    input = event.input,
                    status = TimelineItem.ToolCall.Status.RUNNING,
                )
            )
            is AgentEvent.ToolFinished -> updateTool(event.toolUseId) { card ->
                card.copy(
                    status = if (event.outcome.isError) {
                        TimelineItem.ToolCall.Status.ERROR
                    } else {
                        TimelineItem.ToolCall.Status.OK
                    },
                    output = event.outcome.content,
                    durationMs = event.durationMs,
                    images = event.outcome.images,
                )
            }
            is AgentEvent.ToolDenied -> {
                val existing = items.indexOfFirst {
                    it is TimelineItem.ToolCall && it.toolUseId == event.toolUseId
                }
                if (existing >= 0) {
                    updateTool(event.toolUseId) {
                        it.copy(status = TimelineItem.ToolCall.Status.DENIED, output = event.reason)
                    }
                } else {
                    items.add(
                        TimelineItem.ToolCall(
                            id = newId(),
                            toolUseId = event.toolUseId,
                            name = event.name,
                            status = TimelineItem.ToolCall.Status.DENIED,
                            output = event.reason,
                        )
                    )
                }
            }
            is AgentEvent.Compacted -> {
                sealLive()
                items.add(
                    TimelineItem.Note(
                        newId(),
                        TimelineItem.Note.Kind.COMPACTION,
                        compactionText(event),
                    )
                )
            }
            is AgentEvent.Completed -> {
                sealLive()
                items.add(
                    TimelineItem.RunSummary(newId(), event.stopReason, event.usage, event.iterations)
                )
            }
            is AgentEvent.Aborted -> {
                sealLive()
                items.add(
                    TimelineItem.Note(
                        newId(),
                        TimelineItem.Note.Kind.INFO,
                        "已中止（第 ${event.iterations} 轮）。历史已修复为可继续的状态。",
                    )
                )
            }
            is AgentEvent.Failed -> {
                sealLive()
                items.add(TimelineItem.Note(newId(), TimelineItem.Note.Kind.ERROR, event.message))
            }
        }
        return snapshot()
    }

    // ---------------------------------------------------------------- 内部

    private fun newId(): Long = nextId++

    /** 把增量追加到当前流式草稿上；没有草稿就新建一条。 */
    private fun appendLive(text: String? = null, reasoning: String? = null) {
        val id = liveAssistantId
        if (id == null) {
            liveAssistantId = newId()
            items.add(
                TimelineItem.AssistantText(
                    id = liveAssistantId!!,
                    text = text.orEmpty(),
                    reasoning = reasoning.orEmpty(),
                    streaming = true,
                )
            )
            return
        }
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        val current = items[idx] as TimelineItem.AssistantText
        items[idx] = current.copy(
            text = current.text + text.orEmpty(),
            reasoning = current.reasoning + reasoning.orEmpty(),
        )
    }

    /**
     * 用权威消息收尾草稿：text/reasoning 以 [AgentEvent.AssistantMessage] 为准，
     * 从而覆盖掉流式拼出来的等价内容（不重复展示）。
     */
    private fun finalizeAssistant(event: AgentEvent.AssistantMessage) {
        val message = event.message
        val id = liveAssistantId
        liveAssistantId = null
        val live = id?.let { lid -> items.firstOrNull { it.id == lid } as? TimelineItem.AssistantText }
        val finalText = message.text.ifEmpty { live?.text.orEmpty() }
        val finalReasoning = message.reasoning.ifEmpty { live?.reasoning.orEmpty() }

        if (id != null && live != null) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) {
                items[idx] = TimelineItem.AssistantText(id, finalText, finalReasoning, streaming = false)
                return
            }
        }
        if (finalText.isNotEmpty() || finalReasoning.isNotEmpty()) {
            items.add(TimelineItem.AssistantText(newId(), finalText, finalReasoning, streaming = false))
        }
    }

    /** 结束当前草稿（不丢弃内容，仅停止追加）。 */
    private fun sealLive() {
        val id = liveAssistantId ?: return
        liveAssistantId = null
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        val current = items[idx] as TimelineItem.AssistantText
        items[idx] = current.copy(streaming = false)
    }

    private inline fun updateTool(toolUseId: String, transform: (TimelineItem.ToolCall) -> TimelineItem.ToolCall) {
        val idx = items.indexOfFirst { it is TimelineItem.ToolCall && it.toolUseId == toolUseId }
        if (idx < 0) return
        items[idx] = transform(items[idx] as TimelineItem.ToolCall)
    }

    private fun compactionText(event: AgentEvent.Compacted): String {
        val r = event.result
        return if (r.didCompact) {
            "上下文已压缩：折叠 ${r.compactedMessages} 条消息，约 -${r.savedTokens} tokens" +
                if (r.summary.isBlank()) "（摘要不可用，仅截断）" else ""
        } else {
            "上下文已压缩"
        }
    }

    companion object {
        /**
         * 从落盘的历史重建时间轴（切换会话 / 冷启动恢复）。
         *
         * 纯函数，可在宿主 JVM 上单测。注意 tool_use 与 tool_result 在历史里是**两条**
         * 消息：重建时先建卡片，等遇到 tool_result 再回填状态与输出。
         */
        fun fromHistory(messages: List<Message>): List<TimelineItem> {
            val out = ArrayList<TimelineItem>()
            val cardIndexOf = HashMap<String, Int>()
            var id = 1L

            for (m in messages) {
                when (m.role) {
                    Role.USER -> {
                        val text = m.text
                        when {
                            // 压缩摘要渲染成分隔线，而不是伪装成一条用户发言
                            text.startsWith(COMPACTION_MARKER) -> out.add(
                                TimelineItem.Note(
                                    id++,
                                    TimelineItem.Note.Kind.COMPACTION,
                                    text.removePrefix(COMPACTION_MARKER).trim(),
                                )
                            )

                            text.isNotEmpty() || m.images.isNotEmpty() ->
                                out.add(TimelineItem.UserText(id++, text, m.images))
                        }
                        // 纯工具结果消息：回填既有卡片，不新增行
                        for (r in m.toolResults) {
                            val at = cardIndexOf[r.toolUseId] ?: continue
                            val card = out[at] as? TimelineItem.ToolCall ?: continue
                            out[at] = card.copy(
                                status = if (r.isError) {
                                    TimelineItem.ToolCall.Status.ERROR
                                } else {
                                    TimelineItem.ToolCall.Status.OK
                                },
                                output = r.content,
                                images = r.images,
                            )
                        }
                    }

                    Role.ASSISTANT -> {
                        if (m.text.isNotEmpty() || m.reasoning.isNotEmpty()) {
                            out.add(
                                TimelineItem.AssistantText(
                                    id = id++,
                                    text = m.text,
                                    reasoning = m.reasoning,
                                    streaming = false,
                                )
                            )
                        }
                        for (u in m.toolUses) {
                            cardIndexOf[u.id] = out.size
                            out.add(
                                TimelineItem.ToolCall(
                                    id = id++,
                                    toolUseId = u.id,
                                    name = u.name,
                                    input = u.input,
                                    status = TimelineItem.ToolCall.Status.RUNNING,
                                )
                            )
                        }
                    }

                    Role.SYSTEM -> Unit
                }
            }
            return out
        }
    }
}

/** 供 UI 显示 stop reason 的短标签。 */
fun StopReason.shortLabel(): String = when (this) {
    StopReason.END_TURN -> "完成"
    StopReason.TOOL_USE -> "工具调用"
    StopReason.MAX_TOKENS -> "达到输出上限"
    StopReason.STOP_SEQUENCE -> "停止序列"
    StopReason.REFUSAL -> "被拒绝"
    StopReason.ABORTED -> "已中止"
    StopReason.ERROR -> "出错"
    StopReason.UNKNOWN -> "结束"
}

/** 「1.2k」这类紧凑 token 表示。 */
fun TokenUsage.compactLabel(): String = "↑${compact(inputTokens)} ↓${compact(outputTokens)}"

private fun compact(n: Int): String =
    if (n < 1_000) n.toString() else String.format("%.1fk", n / 1_000.0)
