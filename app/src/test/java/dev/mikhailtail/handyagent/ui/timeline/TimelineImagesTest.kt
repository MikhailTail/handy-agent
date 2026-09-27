package dev.mikhailtail.handyagent.ui.timeline

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.loop.AgentEvent
import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.Role
import dev.mikhailtail.handyagent.core.tool.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片进入时间轴。这条链路（工具产出图 → 事件 → 卡片；历史落盘 → 重建 → 卡片）
 * 是 mobile use 的可见性来源：模型截了图，用户必须在界面上看得见。
 */
class TimelineImagesTest {

    private val ref = ImageRef(
        id = "img_1.jpg",
        mediaType = "image/jpeg",
        width = 800,
        height = 600,
        byteSize = 1_234,
    )

    private fun cards(reducer: TimelineReducer) = reducer.snapshot().filterIsInstance<TimelineItem.ToolCall>()

    @Test
    fun `a tool that returns an image shows it on its card`() {
        val reducer = TimelineReducer()
        reducer.apply(AgentEvent.ToolStarted("t1", "mobile_screenshot", Json.obj()))
        reducer.apply(
            AgentEvent.ToolFinished(
                toolUseId = "t1",
                name = "mobile_screenshot",
                outcome = ToolOutcome.withImages("screenshot 800x600", listOf(ref)),
                durationMs = 12,
            )
        )

        assertEquals(listOf(ref), cards(reducer).single().images)
    }

    @Test
    fun `a tool without images leaves the card image-free`() {
        val reducer = TimelineReducer()
        reducer.apply(AgentEvent.ToolStarted("t1", "read", Json.obj()))
        reducer.apply(AgentEvent.ToolFinished("t1", "read", ToolOutcome.ok("text"), 1))

        assertTrue(cards(reducer).single().images.isEmpty())
    }

    @Test
    fun `user attachments land on the user bubble`() {
        val reducer = TimelineReducer()

        val items = reducer.user("看看这个", listOf(ref))

        assertEquals(listOf(ref), items.filterIsInstance<TimelineItem.UserText>().single().images)
    }

    @Test
    fun `fromHistory restores images on both user bubbles and tool cards`() {
        val items = TimelineReducer.fromHistory(
            listOf(
                Message.userWithImages("看看这个", listOf(ref)),
                Message(
                    Role.ASSISTANT,
                    listOf(Block.ToolUse("t1", "mobile_screenshot", Json.obj())),
                ),
                Message.toolResults(listOf(Block.ToolResult("t1", "shot", images = listOf(ref)))),
            )
        )

        assertEquals(listOf(ref), items.filterIsInstance<TimelineItem.UserText>().single().images)
        assertEquals(listOf(ref), items.filterIsInstance<TimelineItem.ToolCall>().single().images)
    }

    @Test
    fun `a message carrying only an image still produces a bubble`() {
        val items = TimelineReducer.fromHistory(listOf(Message.userWithImages("", listOf(ref))))

        assertEquals(listOf(ref), items.filterIsInstance<TimelineItem.UserText>().single().images)
    }

    @Test
    fun `an errored tool result still keeps its image`() {
        val items = TimelineReducer.fromHistory(
            listOf(
                Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "mobile_screenshot", Json.obj()))),
                Message.toolResults(
                    listOf(Block.ToolResult("t1", "部分失败", isError = true, images = listOf(ref)))
                ),
            )
        )

        val card = items.filterIsInstance<TimelineItem.ToolCall>().single()
        assertEquals(TimelineItem.ToolCall.Status.ERROR, card.status)
        assertEquals(listOf(ref), card.images)
    }
}
