package dev.mikhailtail.handyagent.persistence

import java.io.File

/**
 * 会话列表项 —— 字段逐个对齐 cc-haha 的 `SessionListItem`
 * （`src/server/services/sessionService.ts`，前端副本在 `desktop/src/types/session.ts`）。
 */
data class SessionListItem(
    val id: String,
    val title: String,
    val createdAt: String,
    val modifiedAt: String,
    val messageCount: Int,
    val projectPath: String,
    val projectRoot: String?,
    val workDir: String?,
    val workDirExists: Boolean,
    val workspaceState: String?,
    val permissionMode: String?,
    val runtimeProviderId: String?,
    val runtimeModelId: String?,
    val effortLevel: String?,
)

/**
 * 扫描 `projects/<projectPath>/<sessionId>.jsonl` 产出会话列表。
 *
 * **目录布局**（cc-haha 的 `<configDir>/projects/`）：
 * ```
 * projects/
 *   D--Users-29159-AppData-.../          ← projectPath：把真实路径里的非字母数字换成 -
 *     4daa5acb-....jsonl                 ← 一个会话一份转录
 *     4daa5acb-..../                     ← 同名目录，放子代理转录等
 *       subagents/agent-*.jsonl
 * ```
 *
 * **`projectRoot` 不去反解目录名**。`A-B` 形式是有歧义的（原本的 `-` 和路径分隔符都变成了
 * `-`），cc-haha 是靠 SQLite 索引记着的。我们改从消息行自带的 `cwd` 取 —— 它记的就是
 * 会话当时的工作目录，比反解可靠。
 */
class SessionScanner(
    private val projectsDir: File,
    private val reader: TranscriptReader = TranscriptReader(),
) {

    /** 列出全部会话，按 `modifiedAt` 倒序（最近的在前），与 cc-haha 的列表顺序一致。 */
    fun listSessions(): List<SessionListItem> =
        projectDirs()
            .flatMap { projectDir -> sessionFiles(projectDir).mapNotNull { toListItem(projectDir, it) } }
            .sortedByDescending { it.modifiedAt }

    private fun projectDirs(): List<File> =
        projectsDir.listFiles { f: File -> f.isDirectory && !f.name.startsWith(".") }?.toList()
            ?: emptyList()

    private fun sessionFiles(projectDir: File): List<File> =
        projectDir.listFiles { f: File -> f.isFile && f.name.endsWith(".jsonl") }?.toList()
            ?: emptyList()

    fun messagesOf(projectDirName: String, sessionId: String): List<MessageEntry> =
        reader.readMessages(File(File(projectsDir, projectDirName), "$sessionId.jsonl"))

    /**
     * 按 sessionId 找会话。
     *
     * API 路径里只有 sessionId（`/api/sessions/<id>/messages`），没有 projectPath ——
     * cc-haha 靠 SQLite 索引直接查，我们阶段 1 还没有索引，就遍历一遍目录。
     * 会话以千计时这个做法仍然很快；等真的慢了再上索引（那是 :persistence 的下一步）。
     */
    fun findSession(sessionId: String): SessionListItem? {
        for (projectDir in projectDirs()) {
            val file = File(projectDir, "$sessionId.jsonl")
            if (file.isFile) return toListItem(projectDir, file)
        }
        return null
    }

    private fun toListItem(projectDir: File, file: File): SessionListItem? {
        val sessionId = file.nameWithoutExtension
        val lines = reader.readLines(file)
        if (lines.isEmpty()) return null

        val messages = lines.mapNotNull { line ->
            if (line.type == "user" || line.type == "assistant") line else null
        }

        // session-meta 是**逐步补全**的：实测一个会话里可以有 3 条，字段各写各的
        // （先只有 workDir，再加 permissionMode，最后换模型时只写 runtime*）。
        // 所以必须**逐字段累积**（后出现的非空值覆盖），取"最后一条整行"会丢字段 ——
        // 这是与 cc-haha 逐字段对照才发现的。
        val meta = SessionMeta(
            workDir = lines.lastMetaValue { it.workDir },
            permissionMode = lines.lastMetaValue { it.permissionMode },
            runtimeProviderId = lines.lastMetaValue { it.runtimeProviderId },
            runtimeModelId = lines.lastMetaValue { it.runtimeModelId },
            effortLevel = lines.lastMetaValue { it.effortLevel },
        )
        // 标题同理：ai-title 会随对话演进被重写，取最后一条。
        val title = lines.lastOrNull { it.type == "ai-title" }?.aiTitle

        // projectRoot / cwd 都取自消息行自带的字段（见类注释）。
        val cwd = messages.firstNotNullOfOrNull { it.cwd } ?: meta?.workDir

        return SessionListItem(
            id = sessionId,
            title = title?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE,
            // 两个时间戳口径**不同**，都是与 cc-haha 逐字段对照出来的：
            // - createdAt  取第一条**任意 entry**（含 session-meta），比首条消息早几秒；
            // - modifiedAt 取最后一条**消息**，不是最后一条 entry ——
            //   会话末尾常跟着一条 session-meta（换模型/改权限时写入），若按 entry 取，
            //   时间会凭空往后跳（实测有的会话跳了 1 小时）。
            createdAt = lines.mapNotNull { it.timestamp }.firstOrNull() ?: file.lastModifiedStamp(),
            modifiedAt = messages.mapNotNull { it.timestamp }.lastOrNull()
                ?: file.lastModifiedStamp(),
            // 只数对话消息：session-meta / ai-title / queue-operation / file-history-snapshot
            // 都不是消息，算进去会让条数明显偏大。
            messageCount = messages.size,
            projectPath = projectDir.name,
            projectRoot = cwd,
            workDir = meta?.workDir,
            workDirExists = meta?.workDir?.let { File(it).isDirectory } ?: false,
            workspaceState = if (meta?.workDir?.let { File(it).isDirectory } == true) "available" else "missing",
            permissionMode = meta?.permissionMode,
            runtimeProviderId = meta?.runtimeProviderId,
            runtimeModelId = meta?.runtimeModelId,
            effortLevel = meta?.effortLevel,
        )
    }

    companion object {
        /** cc-haha 对无标题会话显示 "Untitled"；保持一致的措辞。 */
        const val DEFAULT_TITLE = "Untitled"
    }
}

/** 某个 meta 字段最后一次出现的非空值（见 toListItem 里关于"逐步补全"的说明）。 */
private fun List<TranscriptLine>.lastMetaValue(pick: (TranscriptLine) -> String?): String? =
    asReversed().firstNotNullOfOrNull { line ->
        if (line.type == "session-meta") pick(line) else null
    }

private fun File.lastModifiedStamp(): String =
    java.time.Instant.ofEpochMilli(lastModified()).toString()
