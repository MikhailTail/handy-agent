package dev.mikhailtail.handyagent.core.mcp

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * 内存假子进程：stdin 被捕获成可断言的文本行，stdout/stderr 由测试主动喂。
 * 无需真的 fork，就能覆盖行框定、stderr 隔离、进程早退等分支。
 */
class FakeMcpProcess : McpProcess {

    private val captured = StringBuilder()

    private val stdoutIn = PipedInputStream(1 shl 16)
    private val stdoutOut = PipedOutputStream(stdoutIn)
    private val stderrIn = PipedInputStream(1 shl 16)
    private val stderrOut = PipedOutputStream(stderrIn)

    @Volatile
    var destroyed = false
        private set

    @Volatile
    private var alive = true

    override val stdin: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            synchronized(captured) { captured.append(b.toChar()) }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(captured) { captured.append(String(b, off, len, Charsets.UTF_8)) }
        }
    }

    override val stdout: InputStream get() = stdoutIn
    override val stderr: InputStream get() = stderrIn

    override fun isAlive(): Boolean = alive

    override fun destroy() {
        alive = false
        destroyed = true
        runCatching { stdoutOut.close() }
        runCatching { stderrOut.close() }
    }

    /** 服务端往协议通道写一行（stdout）。 */
    fun serverSays(line: String) {
        stdoutOut.write((line + "\n").toByteArray(Charsets.UTF_8))
        stdoutOut.flush()
    }

    fun serverSaysRaw(bytes: ByteArray) {
        stdoutOut.write(bytes)
        stdoutOut.flush()
    }

    /** 服务端往日志通道写一行（stderr）。 */
    fun serverLogs(line: String) {
        stderrOut.write((line + "\n").toByteArray(Charsets.UTF_8))
        stderrOut.flush()
    }

    /** 对端关闭 stdout。 */
    fun closeStdout() {
        runCatching { stdoutOut.close() }
    }

    fun sentLines(): List<String> = synchronized(captured) {
        captured.toString().split('\n').filter { it.isNotEmpty() }
    }
}

/** 记录调用参数的假工厂。 */
class FakeProcessFactory(
    private val process: FakeMcpProcess = FakeMcpProcess(),
    private val failWith: Exception? = null,
) : McpProcessFactory {

    var startCount = 0
        private set
    var lastCommand: List<String>? = null
        private set
    var lastWorkingDir: File? = null
        private set
    var lastEnv: Map<String, String> = emptyMap()
        private set

    val proc: FakeMcpProcess get() = process

    override fun start(command: List<String>, workingDir: File?, env: Map<String, String>): McpProcess {
        failWith?.let { throw it }
        startCount++
        lastCommand = command
        lastWorkingDir = workingDir
        lastEnv = env
        return process
    }
}
