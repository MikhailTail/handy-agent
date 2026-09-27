package dev.mikhailtail.handyagent.core.sandbox

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    /** 供工具回填给模型的紧凑表示。 */
    fun render(): String {
        val sb = StringBuilder()
        if (timedOut) sb.append("[timed out]\n")
        sb.append("exit=").append(exitCode).append('\n')
        if (stdout.isNotEmpty()) sb.append(stdout)
        if (stdout.isNotEmpty() && !stdout.endsWith("\n")) sb.append('\n')
        if (stderr.isNotEmpty()) {
            sb.append("--- stderr ---\n").append(stderr)
            if (!stderr.endsWith("\n")) sb.append('\n')
        }
        if (truncated) sb.append("[output truncated]\n")
        return sb.toString()
    }
}

/**
 * 沙箱命令执行器。
 *
 * 安全约束：
 * 1. 工作目录固定为 workspace，子进程继承（无法用 cd 逃出，因为 shell 每次重启）。
 * 2. 环境变量白名单化，避免泄漏宿主密钥（如 API key）。
 * 3. 超时强杀；双流并发排空避免管道缓冲死锁；输出截断防止 OOM。
 */
class ProcessShell(
    private val workingDir: File,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT,
    private val shellPath: String = defaultShell(),
) {
    init {
        require(timeoutMs > 0) { "timeoutMs must be > 0" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be > 0" }
        workingDir.mkdirs()
    }

    fun run(command: String, timeoutOverrideMs: Long? = null): ShellResult {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return ShellResult(command, -1, "", "empty command", truncated = false)

        val timeout = (timeoutOverrideMs ?: timeoutMs).coerceAtLeast(1L)
        val proc = ProcessBuilder(shellPath, "-c", trimmed)
            .directory(workingDir)
            .apply { scrubEnvironment(environment()) }
            .start()

        proc.outputStream.close()

        val out = Drain(proc.inputStream, maxOutputBytes)
        val err = Drain(proc.errorStream, maxOutputBytes)
        val tOut = Thread(out, "shell-stdout").apply { isDaemon = true }
        val tErr = Thread(err, "shell-stderr").apply { isDaemon = true }
        tOut.start()
        tErr.start()

        val finished = proc.waitFor(timeout, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
        }

        tOut.join(3_000)
        tErr.join(3_000)

        val exit = if (finished) runCatching { proc.exitValue() }.getOrDefault(-1) else -1
        return ShellResult(
            command = trimmed,
            exitCode = exit,
            stdout = out.text(),
            stderr = err.text(),
            timedOut = !finished,
            truncated = out.truncated || err.truncated,
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 60_000
        const val DEFAULT_MAX_OUTPUT: Int = 8 * 1024
        const val MAX_TIMEOUT_MS: Long = 600_000

        /** 只保留这些环境变量，其余（含各类 *_KEY / TOKEN）一律剥离。 */
        private val ALLOWED_ENV = setOf(
            "PATH", "HOME", "TMPDIR", "TEMP", "TMP",
            "LANG", "LC_ALL", "LC_CTYPE", "TERM", "TZ", "USER", "PWD",
        )

        private var cachedShell: String? = null

        fun defaultShell(): String {
            cachedShell?.let { return it }
            val candidates = listOf("/system/bin/sh", "/bin/sh", "/usr/bin/sh")
            val found = candidates.firstOrNull { File(it).canExecute() } ?: "sh"
            cachedShell = found
            return found
        }

        private fun scrubEnvironment(env: MutableMap<String, String>) {
            val kept = HashMap<String, String>()
            for (name in ALLOWED_ENV) {
                val v = env[name] ?: continue
                kept[name] = v
            }
            env.clear()
            env.putAll(kept)
            env["PATH"] = env["PATH"] ?: "/system/bin:/system/xbin:/usr/bin:/bin"
        }
    }

    /** 单独线程排空一个流；超出上限后继续读但丢弃，保证子进程不会阻塞在写。 */
    private class Drain(private val input: InputStream, private val limit: Int) : Runnable {
        private val buf = ByteArray(8 * 1024)
        private val sink = ByteArrayOutputStream()

        @Volatile
        var truncated: Boolean = false
            private set

        override fun run() {
            try {
                input.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        val remaining = limit - sink.size()
                        if (remaining > 0) {
                            sink.write(buf, 0, minOf(n, remaining))
                            if (n > remaining) truncated = true
                        } else {
                            truncated = true
                        }
                    }
                }
            } catch (_: Exception) {
                // 进程被强杀时流会异常关闭，属预期路径。
            }
        }

        fun text(): String = sink.toString(Charsets.UTF_8.name())
    }
}
