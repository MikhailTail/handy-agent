package dev.pocket.agent.core.mcp

import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * MCP 传输层：按「一行一条 JSON 消息」交换（stdio 与 streamable-HTTP 都是这个形态）。
 *
 * 生命周期：`start()` → 若干 `send()` ↔ `incoming` → `close()`。
 * [incoming] 是热流（内部 Channel 缓冲），因此 `start()` 之后、开始收集之前到达的消息不会丢。
 */
interface McpTransport {

    /** 人类可读的传输标识（通常是 server id），用于错误信息。 */
    val name: String

    /** 启动传输（拉子进程 / 建连接）。可重复调用，第二次应为空操作。 */
    suspend fun start()

    /** 发送一条消息（换行由实现补）。未 [start] 时抛异常。 */
    suspend fun send(message: String)

    /** 入站消息行流；流结束表示对端关闭。 */
    val incoming: Flow<String>

    /** 关闭并释放资源。必须幂等。 */
    suspend fun close()

    /** 传输侧诊断信息（如子进程 stderr 末尾），用于把「握手失败」变成可读错误。 */
    fun diagnostics(): String = ""
}

/**
 * 子进程抽象。存在的唯一理由是**可测**：单测注入内存假进程，
 * 就能覆盖行框定、半包、进程早退等分支，而不必真的 fork。
 */
interface McpProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    fun isAlive(): Boolean
    fun destroy()
}

fun interface McpProcessFactory {
    fun start(command: List<String>, workingDir: File?, env: Map<String, String>): McpProcess
}

/** 生产实现：`ProcessBuilder` 拉真子进程，环境变量白名单化 + 显式注入。 */
object SystemMcpProcessFactory : McpProcessFactory {

    /** 只透传这些宿主变量；其余一律剥离，避免把 Agent 的密钥泄漏给第三方 MCP 服务。 */
    private val BASE_ENV = listOf(
        "PATH", "HOME", "TMPDIR", "TEMP", "TMP",
        "LANG", "LC_ALL", "LC_CTYPE", "TERM", "TZ", "USER", "PWD",
    )

    override fun start(command: List<String>, workingDir: File?, env: Map<String, String>): McpProcess {
        require(command.isNotEmpty()) { "empty MCP command" }
        val pb = ProcessBuilder(command)
        if (workingDir != null) pb.directory(workingDir)
        val target = pb.environment()
        val kept = LinkedHashMap<String, String>()
        for (k in BASE_ENV) target[k]?.let { kept[k] = it }
        kept.putAll(env)
        if ("PATH" !in kept) kept["PATH"] = "/system/bin:/system/xbin:/usr/bin:/bin"
        target.clear()
        target.putAll(kept)
        val proc = pb.start()
        return ProcessAdapter(proc)
    }

    private class ProcessAdapter(private val proc: Process) : McpProcess {
        override val stdin: OutputStream get() = proc.outputStream
        override val stdout: InputStream get() = proc.inputStream
        override val stderr: InputStream get() = proc.errorStream
        override fun isAlive(): Boolean = proc.isAlive
        override fun destroy() {
            proc.destroy()
            if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }
}
