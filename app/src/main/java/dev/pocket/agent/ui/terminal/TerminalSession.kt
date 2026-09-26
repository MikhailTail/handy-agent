package dev.pocket.agent.ui.terminal

import dev.pocket.agent.core.sandbox.ShellResult

/** 终端里的一条记录：命令 + 合并后的输出。 */
data class TerminalEntry(
    val command: String,
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    /** 提示符行 + 输出，终端样式。 */
    fun render(): String {
        val header = "$ ${command}"
        val body = output.trimEnd()
        val trailer = if (ok) "" else if (timedOut) "\n[超时被强杀]" else "\n[exit=$exitCode]"
        return if (body.isEmpty()) header + trailer else "$header\n$body$trailer"
    }
}

/**
 * 终端页的纯逻辑：维护命令历史，并把「跑一条命令」抽象成注入的 [exec]。
 *
 * 注入 [exec] 而不是直接持有 `ProcessShell` 的理由：命令行（空命令、历史上限）这些
 * 交互逻辑因此可以在宿主 JVM 上单测，不需要真的起进程。
 *
 * 执行是**阻塞**的（`ProcessShell.run` 内部 `waitFor`），调用方负责放到 IO 线程。
 */
class TerminalSession(
    private val exec: (String) -> ShellResult,
    /** 历史上限：终端是调试面板，不需要无限保留输出占用内存。 */
    private val maxEntries: Int = MAX_ENTRIES,
) {

    private val entries = ArrayList<TerminalEntry>()

    fun snapshot(): List<TerminalEntry> = entries.toList()

    fun clear() {
        entries.clear()
    }

    /** 跑一条命令并记账。空命令/纯空白直接忽略，返回 null。 */
    fun run(command: String): TerminalEntry? {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return null
        val result = exec(trimmed)
        val entry = TerminalEntry(
            command = trimmed,
            output = result.render(),
            exitCode = result.exitCode,
            timedOut = result.timedOut,
        )
        entries.add(entry)
        if (entries.size > maxEntries) {
            // 只丢最旧的整条记录，绝不截断正在看的输出。
            entries.subList(0, entries.size - maxEntries).clear()
        }
        return entry
    }

    companion object {
        const val MAX_ENTRIES = 200
    }
}
