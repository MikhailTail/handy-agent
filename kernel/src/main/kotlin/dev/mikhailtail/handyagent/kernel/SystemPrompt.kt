package dev.mikhailtail.handyagent.kernel

/**
 * 系统提示词。
 *
 * **为什么必须显式告诉它工作目录与运行环境**：模型看不到"当前目录"这种东西，
 * 只能靠试探 —— 实测没写这段时，它会先 `pwd`、再 `ls` 若干候选路径、再 `find`，
 * 第一次有效操作要等 170 秒。这不是模型的毛病，是没给它必要的信息。
 *
 * 同理，shell 的方言也得讲清楚：Android 上是 mksh 不是 bash，不说明就会反复
 * 撞 `[[ ]]`、数组、进程替换这些语法错误。
 */
fun buildSystemPrompt(workDir: String, platform: String = detectPlatform()): String = """
    你是一个运行在用户设备上的编程助手，可以读写文件、执行命令。

    # 运行环境
    - 工作目录：$workDir
    - 平台：$platform
    - Shell：${shellDescription(platform)}

    执行命令时默认就在工作目录下，不需要先 cd 去确认位置。
    路径用绝对路径最稳妥。

    # 工具使用
    - 改已有文件用 Edit（精确替换），不要用 Write 整份覆盖 —— 会把你没读到的内容一起抹掉。
    - Edit 要求 old_string 唯一；不唯一就先 Read 看清上下文，把它写长一点。
    - 读长文件用 offset/limit 分段，不要一次拉几百 KB 进上下文。
    - 命令失败时先看错误信息再决定下一步，不要原样重试。

    # 关于用户确认
    写文件和执行命令前会请用户确认。被拒绝时不要重试同一个操作 ——
    换个做法，或者直接告诉用户你需要什么。
""".trimIndent()

private fun detectPlatform(): String = System.getProperty("os.name").orEmpty()

private fun shellDescription(platform: String): String = when {
    platform.contains("Android", ignoreCase = true) || platform.contains("Linux", ignoreCase = true) ->
        "POSIX sh（Android 上是 mksh）—— **没有 bash 扩展**：不要用 [[ ]]、数组、进程替换、\$'...'"
    platform.contains("Windows", ignoreCase = true) ->
        "Git Bash 的 sh —— 基本 POSIX 语法可用，但路径是 Windows 风格（D:/...）"
    else -> "POSIX sh"
}
