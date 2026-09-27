package dev.mikhailtail.handyagent.tools

import dev.mikhailtail.handyagent.kernel.api.FileHost
import dev.mikhailtail.handyagent.kernel.api.ShellHost
import dev.mikhailtail.handyagent.kernel.api.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 真实文件系统 —— 无路径限制。
 *
 * Android 上能否碰到某个路径由**系统权限**决定（`MANAGE_EXTERNAL_STORAGE` 或运行时授权），
 * 不由这里决定。这里的职责只是把路径解析成绝对路径并做 I/O。
 */
class RealFileHost : FileHost {

    override fun readText(path: String): String = File(path).readText()

    override fun writeText(path: String, text: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun list(path: String): List<String> =
        File(path).listFiles()?.map { it.absolutePath }?.sorted() ?: emptyList()

    override fun delete(path: String) {
        File(path).delete()
    }

    /**
     * 在 `root` 下递归匹配。
     *
     * 限制返回条数：命中上千个文件时全部塞进模型上下文既没用又烧 token，
     * 不如给它一个明确的截断信号（见 [MAX_GLOB_RESULTS] 的提示语）。
     */
    override fun glob(pattern: String, root: String): List<String> {
        val regex = globToRegex(pattern)
        val rel = pattern.startsWith("/")
        val base = File(root)
        return base.walkTopDown()
            .filter { it.isFile }
            .map { if (rel) it.absolutePath else it.relativeTo(base).path.replace(File.separatorChar, '/') }
            .filter { regex.matches(if (rel) it.removePrefix(base.absolutePath).trimStart('/') else it) }
            .take(MAX_GLOB_RESULTS)
            .toList()
    }

    companion object {
        const val MAX_GLOB_RESULTS = 300

        /** 只支持 `*` / `**` / `?` —— 覆盖模型的真实用法，不引入完整 glob 语义的复杂度。 */
        fun globToRegex(pattern: String): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < pattern.length) {
                when (val c = pattern[i]) {
                    '*' ->
                        if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                            sb.append(".*"); i++
                        } else {
                            sb.append("[^/]*")
                        }
                    '?' -> sb.append("[^/]")
                    '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}' ->
                        sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i++
            }
            return Regex(sb.append('$').toString())
        }
    }
}

/**
 * 用系统 shell 执行命令。
 *
 * Android 上没有 bash，`/system/bin/sh` 是 mksh —— 语法与 POSIX sh 基本相容，
 * 但**没有 bash 的那些扩展**（数组、`[[ ]]`、进程替换）。模型若写出 bash 专有语法
 * 会失败，所以工具描述里要讲清这一点，别让它反复试错。
 */
class ProcessShell(
    private val shellPath: String = detectShell(),
    private val defaultTimeoutMs: Long = 120_000,
) : ShellHost {

    override suspend fun run(command: String, workDir: String, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            val dir = File(workDir).takeIf { it.isDirectory } ?: File(".")
            val process = ProcessBuilder(shellPath, "-c", command)
                .directory(dir)
                .redirectErrorStream(false)
                .start()

            // 并发读 stdout/stderr：只读一个流会在另一个写满管道缓冲时死锁，
            // 而 `yes | head` 这类命令轻易就能写满。
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread { process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
            val errThread = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
            outThread.start(); errThread.start()

            val finished = process.waitFor(
                timeoutMs.takeIf { it > 0 } ?: defaultTimeoutMs,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            if (!finished) {
                process.destroyForcibly()
                outThread.join(1_000); errThread.join(1_000)
                return@withContext ShellResult(
                    stdout = stdout.toString().trimEnd(),
                    stderr = "命令超时（${timeoutMs}ms）后被终止",
                    exitCode = -1,
                )
            }
            outThread.join(2_000); errThread.join(2_000)
            ShellResult(
                stdout = stdout.toString().trimEnd(),
                stderr = stderr.toString().trimEnd(),
                exitCode = process.exitValue(),
            )
        }

    companion object {
        /**
         * 候选 shell，按优先级探测。
         *
         * **`/bin/sh` 在 Android 上不存在** —— 系统把它放在 `/system/bin/sh`
         * （且是 mksh，没有 bash 扩展）。写死 `/bin/sh` 会让手机上的 Bash 工具
         * 直接抛 `Cannot run program`，而模型收到的只是"工具执行异常"，
         * 它会以为是权限问题并反复重试。
         *
         * 桌面开发机同理：Windows 上只有 Git Bash 的 sh。探测一遍最省事。
         */
        private val CANDIDATES = listOf(
            "/system/bin/sh",                       // Android
            "/bin/sh",                              // Linux / macOS
            "C:/Program Files/Git/bin/sh.exe",      // Windows 上的 Git Bash
            "C:/Program Files (x86)/Git/bin/sh.exe",
        )

        fun detectShell(): String =
            CANDIDATES.firstOrNull { File(it).canExecute() || File(it).exists() }
                ?: CANDIDATES.first()               // 都不在时保留 Android 的默认值
    }
}
