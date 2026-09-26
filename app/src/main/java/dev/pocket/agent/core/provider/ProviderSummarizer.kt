package dev.pocket.agent.core.provider

import dev.pocket.agent.core.context.Summarizer
import dev.pocket.agent.core.model.Block
import dev.pocket.agent.core.model.ChatRequest
import dev.pocket.agent.core.model.Message
import dev.pocket.agent.core.model.ReasoningEffort

/**
 * 用真实模型做上下文压缩摘要（Loop 9 接线）。
 *
 * 只在历史超阈值时被调用，因此这里以「非流式收集」的方式取完整文本：
 * 逐段拼接 [ProviderEvent.TextDelta]；若后端没吐 delta（少数兼容实现），
 * 回退到 [ProviderEvent.Completed] 里的文本块。
 *
 * 为什么把被压缩的历史渲染成**一条** user 消息，而不是原样拼上一条指令：
 * 历史可能以 assistant 轮或 tool_result 轮结尾，直接追加会造成同角色连续两条消息，
 * 部分后端（Anthropic）会因此 400。渲染成单条消息既规避了协议细节，也顺带裁剪了体积。
 *
 * 失败一律抛异常，由 [dev.pocket.agent.core.context.ContextManager] 捕获并退化为
 * 「丢弃旧轮次」——压缩失败绝不能让对话中断。
 */
class ProviderSummarizer(
    /** 惰性解析：摘要时才有 provider，装配期不必先连上模型。 */
    private val provider: () -> LlmProvider?,
    private val maxTokens: Int = 2_048,
    /** 送进模型的转录上限；超出即截断，避免「压缩」本身把上下文撑爆。 */
    private val maxTranscriptChars: Int = 48_000,
    /** 摘要结果上限，防止模型超长输出把新上下文顶满。 */
    private val maxSummaryChars: Int = 8_000,
) : Summarizer {

    override suspend fun summarize(messages: List<Message>): String {
        if (messages.isEmpty()) return ""
        val llm = provider()
            ?: throw IllegalStateException("no provider available for summarization")

        val request = ChatRequest(
            model = llm.config.defaultModel,
            system = SYSTEM_PROMPT,
            // 单条 user 消息：协议最稳，也不会把旧轮的 tool_use/tool_result 结构带进摘要请求。
            messages = listOf(Message.user(renderTranscript(messages) + "\n\n" + INSTRUCTION)),
            tools = emptyList(),
            effort = ReasoningEffort.OFF,
            maxTokens = maxTokens,
        )

        val streamed = StringBuilder()
        var completedText: String? = null
        llm.stream(request).collect { event ->
            when (event) {
                is ProviderEvent.TextDelta -> streamed.append(event.text)
                is ProviderEvent.Completed -> {
                    completedText = event.blocks
                        .filterIsInstance<Block.Text>()
                        .joinToString("") { it.text }
                }
                is ProviderEvent.Failure ->
                    throw IllegalStateException("summarization failed: ${event.message}", event.cause)
                else -> Unit
            }
        }

        val text = if (streamed.isNotBlank()) streamed.toString() else completedText.orEmpty()
        return text.trim().take(maxSummaryChars)
    }

    /** 把历史渲染成带角色标签的纯文本；单条过长时截断，保留开头（信息密度更高）。 */
    private fun renderTranscript(messages: List<Message>): String {
        val sb = StringBuilder()
        for (m in messages) {
            val body = renderMessage(m)
            if (body.isEmpty()) continue
            sb.append('[').append(m.role.name.lowercase()).append("] ").append(body).append('\n')
            if (sb.length >= maxTranscriptChars) {
                sb.append("...(transcript truncated)\n")
                break
            }
        }
        return sb.toString().trim()
    }

    private fun renderMessage(m: Message): String {
        val parts = ArrayList<String>(4)
        m.blocks.forEach { block ->
            when (block) {
                is Block.Text -> parts.add(block.text)
                is Block.Reasoning -> Unit // 推理内容对摘要无价值，且体积大
                is Block.ToolUse ->
                    parts.add("called tool ${block.name}(${block.input.encode().take(400)})")
                is Block.ToolResult ->
                    parts.add("tool result: ${block.content.take(1_500)}")
            }
        }
        return parts.joinToString("\n").trim()
    }

    companion object {
        const val SYSTEM_PROMPT: String =
            "You compress conversation history for a coding agent. " +
                "Preserve facts, decisions, file paths, code identifiers and unfinished work. " +
                "Drop pleasantries and redundant tool output. Output only the summary."

        const val INSTRUCTION: String =
            "Summarize the transcript above as terse notes for continuing this task. " +
                "Use short bullet points. Do not add commentary about the summary itself."
    }
}
