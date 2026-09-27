package dev.mikhailtail.handyagent.core.tool

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.sandbox.ProcessShell

/**
 * 沙箱 shell。命令在 workspace 目录下、以白名单环境变量、带超时地执行。
 * 每次调用都是一次全新的 `sh -c`，因此 `cd` 不会污染后续调用。
 */
object ShellTool : Tool {
    override val name = "bash"
    override val description =
        "Run a shell command in the workspace sandbox. Returns exit code, stdout and stderr. " +
            "The working directory persists at the workspace root; use absolute-ish relative paths."

    override val inputSchema = toolSchema(
        required = listOf("command"),
        properties = linkedMapOf(
            "command" to stringProp("Shell command executed via 'sh -c'."),
            "timeout_ms" to intProp("Optional timeout in milliseconds (max ${ProcessShell.MAX_TIMEOUT_MS})."),
        ),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val command = input.str("command") ?: return ToolOutcome.error("missing 'command'")
        if (command.isBlank()) return ToolOutcome.error("'command' must not be blank")

        val requested = input.int("timeout_ms")?.toLong()
        val timeout = requested?.coerceIn(1L, ProcessShell.MAX_TIMEOUT_MS)

        val result = ctx.shell.run(command, timeout)
        val rendered = result.render()
        // 非零退出码不算「工具错误」：模型需要看到 stderr 自我纠正，而不是被当作调用失败。
        return ToolOutcome(rendered, isError = result.timedOut)
    }
}
