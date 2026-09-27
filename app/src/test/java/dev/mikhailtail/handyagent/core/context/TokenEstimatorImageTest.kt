package dev.mikhailtail.handyagent.core.context

import dev.mikhailtail.handyagent.core.model.Block
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片必须计入上下文预算，否则「压缩触发得太晚」——
 * 一轮 mobile use 会往历史里塞好几张截图，低估会直接把上下文顶爆。
 */
class TokenEstimatorImageTest {

    private fun ref(w: Int, h: Int) = ImageRef("img_1.jpg", "image/jpeg", w, h, byteSize = 120_000)

    @Test
    fun `an invalid ref costs nothing`() {
        assertEquals(0, TokenEstimator.estimateImage(ImageRef("", "", 0, 0, 0)))
    }

    @Test
    fun `bigger images cost more`() {
        assertTrue(
            TokenEstimator.estimateImage(ref(1280, 2400)) >
                TokenEstimator.estimateImage(ref(320, 240)),
        )
    }

    @Test
    fun `an image block counts toward the message estimate`() {
        val withImage = Message(Role.USER, listOf(Block.Text("hi"), Block.Image(ref(800, 600))))
        val without = Message(Role.USER, listOf(Block.Text("hi")))
        assertTrue(TokenEstimator.estimate(withImage) > TokenEstimator.estimate(without))
    }

    @Test
    fun `images attached to a tool result are counted`() {
        val withImages = Message.toolResults(
            listOf(Block.ToolResult("t1", "ok", images = listOf(ref(800, 600)))),
        )
        val without = Message.toolResults(listOf(Block.ToolResult("t1", "ok")))
        assertTrue(TokenEstimator.estimate(withImages) > TokenEstimator.estimate(without))
    }

    @Test
    fun `two images cost more than one`() {
        val one = Message.toolResults(
            listOf(Block.ToolResult("t1", "ok", images = listOf(ref(800, 600)))),
        )
        val two = Message.toolResults(
            listOf(Block.ToolResult("t1", "ok", images = listOf(ref(800, 600), ref(800, 600)))),
        )
        assertTrue(TokenEstimator.estimate(two) > TokenEstimator.estimate(one))
    }
}
