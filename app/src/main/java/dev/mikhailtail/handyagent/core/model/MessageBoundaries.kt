package dev.mikhailtail.handyagent.core.model

/**
 * 消息序列的合法切割点计算。
 *
 * Provider 对历史结构有硬性要求：assistant 的 `tool_use` 必须紧跟着对应的
 * `tool_result`。一旦在两者之间切开（Fork、压缩、裁剪都会遇到），下一次请求必定 400。
 */
object MessageBoundaries {

    /**
     * 把「想在第 [requested] 条处切开」吸附到最近的合法位置。
     *
     * 合法定义为：切口之后的第一条消息不是一个「纯 tool_result」的 USER 消息。
     * 若是，说明它的 tool_use 留在了切口左侧，必须往左退，直到配对完整。
     */
    fun snapToValidCut(messages: List<Message>, requested: Int): Int {
        var k = requested.coerceIn(0, messages.size)
        while (k > 0 && k < messages.size && isToolResultOnly(messages[k])) {
            k--
        }
        return k
    }

    /** 纯工具结果消息（协议层属于 USER 轮）。 */
    fun isToolResultOnly(m: Message): Boolean =
        m.role == Role.USER && m.blocks.isNotEmpty() && m.blocks.all { it is Block.ToolResult }
}
