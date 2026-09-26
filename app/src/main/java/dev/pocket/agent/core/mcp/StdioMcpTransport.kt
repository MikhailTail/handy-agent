package dev.pocket.agent.core.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * stdio 传输：拉一个子进程，用它的 stdin/stdout 交换换行分隔的 JSON。
 *
 * 设计要点：
 * 1. **stdout 是协议通道**，绝不能被日志污染 —— 服务端日志只能走 stderr。
 *    因此我们把 stderr 单独排空并留作诊断，而不是并进 stdout。
 * 2. 读线程是独立 daemon 线程 + 无界 Channel：读阻塞不能占住协程调度器，
 *    且 `start()` 与开始收集之间的消息不会丢。
 * 3. 读线程必须持续排空 stdout，否则子进程写满管道缓冲就会僵死。
 */
class StdioMcpTransport(
    override val name: String,
    private val command: List<String>,
    private val workingDir: File? = null,
    private val env: Map<String, String> = emptyMap(),
    private val factory: McpProcessFactory = SystemMcpProcessFactory,
    private val maxStderrBytes: Int = 4 * 1024,
) : McpTransport {

    private val inbox = Channel<String>(Channel.UNLIMITED)
    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)
    private val stderrTail = StringBuilder()

    @Volatile
    private var process: McpProcess? = null

    @Volatile
    private var reader: Thread? = null

    override suspend fun start() {
        if (process != null) return
        check(!closed.get()) { "MCP transport '$name' is already closed" }
        require(command.isNotEmpty()) { "MCP server '$name' has an empty command" }

        val proc = try {
            factory.start(command, workingDir, env)
        } catch (e: Exception) {
            throw McpProtocolException("failed to start MCP server '$name': ${e.message}")
        }
        process = proc
        reader = Thread({ pumpStdout(proc) }, "mcp-$name-reader").apply {
            isDaemon = true
            start()
        }
        Thread({ pumpStderr(proc) }, "mcp-$name-stderr").apply {
            isDaemon = true
            start()
        }
    }

    override suspend fun send(message: String) {
        val proc = process ?: throw McpProtocolException("MCP transport '$name' is not started")
        val bytes = (message + "\n").toByteArray(Charsets.UTF_8)
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    proc.stdin.write(bytes)
                    proc.stdin.flush()
                } catch (e: Exception) {
                    throw McpProtocolException(
                        "failed writing to MCP server '$name': ${e.message}${stderrSuffix()}"
                    )
                }
            }
        }
    }

    override val incoming: Flow<String> get() = inbox.receiveAsFlow()

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        val proc = process
        runCatching { proc?.stdin?.close() }
        runCatching { proc?.destroy() }
        inbox.close()
        reader?.join(2_000)
    }

    override fun diagnostics(): String = synchronized(stderrTail) { stderrTail.toString() }

    private fun pumpStdout(proc: McpProcess) {
        try {
            proc.stdout.bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    if (!inbox.trySend(line).isSuccess) break
                }
            }
        } catch (_: Exception) {
            // 进程被强杀时流会异常关闭，属预期路径。
        } finally {
            inbox.close()
        }
    }

    private fun pumpStderr(proc: McpProcess) {
        try {
            proc.stderr.bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    synchronized(stderrTail) {
                        if (stderrTail.length < maxStderrBytes) stderrTail.append(line).append('\n')
                    }
                }
            }
        } catch (_: Exception) {
            // 同上。
        }
    }

    private fun stderrSuffix(): String {
        val text = diagnostics().trim()
        return if (text.isEmpty()) "" else "\n--- server stderr ---\n$text"
    }
}
