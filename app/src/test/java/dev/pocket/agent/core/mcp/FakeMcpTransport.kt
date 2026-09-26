package dev.pocket.agent.core.mcp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout

/**
 * 内存假传输：把「客户端发出」与「服务端送入」都做成可 await 的队列，
 * 于是测试可以严格按协议时序断言，而不必 sleep 或轮询。
 */
class FakeMcpTransport(
    override val name: String = "fake",
    private val stderrText: String = "fake stderr",
) : McpTransport {

    private val outbox = Channel<String>(Channel.UNLIMITED)
    private val inbox = Channel<String>(Channel.UNLIMITED)

    var startCount = 0
        private set
    var closed = false
        private set

    override suspend fun start() {
        startCount++
    }

    override suspend fun send(message: String) {
        check(!closed) { "send after close" }
        outbox.send(message)
    }

    override val incoming: Flow<String> get() = inbox.receiveAsFlow()

    override suspend fun close() {
        closed = true
        inbox.close()
    }

    override fun diagnostics(): String = stderrText

    /** 等下一条客户端发出的消息（带超时，避免测试挂死）。 */
    suspend fun awaitSent(): String = withTimeout(2_000) { outbox.receive() }

    /** 服务端送入一条消息。 */
    fun feed(line: String) {
        inbox.trySend(line)
    }

    /** 模拟对端关闭 stdout。 */
    fun endIncoming() {
        inbox.close()
    }
}
