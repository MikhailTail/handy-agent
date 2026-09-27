package dev.mikhailtail.handyagent.core.tool

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.util.Glob
import java.io.File

internal const val MAX_READ_BYTES = 256 * 1024
internal const val DEFAULT_READ_LINES = 2_000
internal const val MAX_LIST_ENTRIES = 1_000
internal const val MAX_GREP_HITS = 500
internal const val MAX_SCAN_FILES = 20_000
internal const val MAX_SCAN_BYTES = 4 * 1024 * 1024
internal const val MAX_MATCH_LINE_CHARS = 400

private val NUL: Char = 0.toChar()

/** 读取文件字节，最多 [limit] 字节；返回 (文本, 是否被截断)。 */
internal fun readTextCapped(f: File, limit: Int): Pair<String, Boolean> {
    f.inputStream().use { ins ->
        val buf = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val n = ins.read(buf, read, limit - read)
            if (n < 0) break
            read += n
        }
        val truncated = f.length() > read
        return String(buf, 0, read, Charsets.UTF_8) to truncated
    }
}

/** 深度优先收集文件，带数量与总字节上限，防止误扫大仓库卡死。 */
internal fun walkFiles(root: File, onFile: (File) -> Boolean) {
    var scanned = 0
    var bytes = 0L
    val stack = ArrayDeque<File>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val dir = stack.removeLast()
        val children = dir.listFiles() ?: continue
        val sorted = children.sortedBy { it.name }
        for (child in sorted) {
            if (scanned >= MAX_SCAN_FILES || bytes >= MAX_SCAN_BYTES) return
            if (child.isDirectory) {
                if (child.name == ".git" || child.name == "node_modules") continue
                stack.addLast(child)
            } else if (child.isFile) {
                scanned++
                bytes += child.length()
                if (!onFile(child)) return
            }
        }
    }
}

// ---------------------------------------------------------------------------

object ReadFileTool : Tool {
    override val name = "read"
    override val description =
        "Read a UTF-8 text file. Returns numbered lines. Use offset/limit for large files."
    override val readOnly = true
    override val inputSchema = toolSchema(
        required = listOf("path"),
        properties = linkedMapOf(
            "path" to stringProp("Workspace-relative path; a leading '/' means sandbox root."),
            "offset" to intProp("1-based first line to return (default 1)."),
            "limit" to intProp("Maximum lines to return (default $DEFAULT_READ_LINES)."),
        ),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val path = input.str("path") ?: return ToolOutcome.error("missing 'path'")
        val f = ctx.jail.resolve(path)
        if (!f.exists()) return ToolOutcome.error("file not found: ${ctx.jail.relativeOf(f)}")
        if (f.isDirectory) return ToolOutcome.error("is a directory: ${ctx.jail.relativeOf(f)} (use ls)")

        val (text, truncated) = readTextCapped(f, MAX_READ_BYTES)
        if (text.contains(NUL)) return ToolOutcome.error("binary file: ${ctx.jail.relativeOf(f)}")

        val all = if (text.isEmpty()) emptyList() else text.split('\n')
        val offset = (input.int("offset") ?: 1).coerceAtLeast(1)
        val limit = (input.int("limit") ?: DEFAULT_READ_LINES).coerceAtLeast(1)
        val start = offset - 1
        if (all.isEmpty()) return ToolOutcome.ok("(empty file)")
        if (start >= all.size) {
            return ToolOutcome.error("offset $offset is past EOF (${all.size} lines)")
        }
        val end = minOf(all.size, start + limit)

        val sb = StringBuilder()
        for (i in start until end) {
            sb.append((i + 1).toString().padStart(6)).append('\t').append(all[i]).append('\n')
        }
        if (end < all.size || truncated) {
            sb.append("[truncated: lines ${start + 1}-$end of ${all.size}]")
        } else if (sb.isNotEmpty()) {
            sb.setLength(sb.length - 1)
        }
        return ToolOutcome.ok(sb.toString())
    }
}

object WriteFileTool : Tool {
    override val name = "write"
    override val description =
        "Create or overwrite a file with the given content. Parent directories are created."
    override val inputSchema = toolSchema(
        required = listOf("path", "content"),
        properties = linkedMapOf(
            "path" to stringProp("Workspace-relative path."),
            "content" to stringProp("Full file content to write."),
        ),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val path = input.str("path") ?: return ToolOutcome.error("missing 'path'")
        val content = input.str("content") ?: return ToolOutcome.error("missing 'content'")
        val f = ctx.jail.resolve(path)
        if (f.isDirectory) return ToolOutcome.error("is a directory: ${ctx.jail.relativeOf(f)}")

        val existed = f.exists()
        f.parentFile?.mkdirs()
        f.writeText(content)

        val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
        val verb = if (existed) "overwrote" else "created"
        return ToolOutcome.ok("$verb ${ctx.jail.relativeOf(f)} ($lines lines, ${content.length} chars)")
    }
}

object EditFileTool : Tool {
    override val name = "edit"
    override val description =
        "Replace an exact string in a file. old_string must be unique unless replace_all=true."
    override val inputSchema = toolSchema(
        required = listOf("path", "old_string", "new_string"),
        properties = linkedMapOf(
            "path" to stringProp("Workspace-relative path."),
            "old_string" to stringProp("Exact text to replace (must be unique by default)."),
            "new_string" to stringProp("Replacement text. Empty string deletes the match."),
            "replace_all" to boolProp("Replace every occurrence instead of requiring uniqueness."),
        ),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val path = input.str("path") ?: return ToolOutcome.error("missing 'path'")
        val oldString = input.str("old_string") ?: return ToolOutcome.error("missing 'old_string'")
        val newString = input.str("new_string") ?: return ToolOutcome.error("missing 'new_string'")
        val replaceAll = input.bool("replace_all") ?: false

        if (oldString.isEmpty()) return ToolOutcome.error("'old_string' must not be empty")
        if (oldString == newString) return ToolOutcome.error("'old_string' and 'new_string' are identical")

        val f = ctx.jail.resolve(path)
        if (!f.exists()) return ToolOutcome.error("file not found: ${ctx.jail.relativeOf(f)}")
        if (f.isDirectory) return ToolOutcome.error("is a directory: ${ctx.jail.relativeOf(f)}")

        val text = f.readText()
        val count = countOccurrences(text, oldString)
        if (count == 0) {
            return ToolOutcome.error("old_string not found in ${ctx.jail.relativeOf(f)}")
        }
        if (count > 1 && !replaceAll) {
            return ToolOutcome.error(
                "old_string appears $count times in ${ctx.jail.relativeOf(f)}; " +
                    "add surrounding context to make it unique, or set replace_all=true"
            )
        }

        val updated = if (replaceAll) text.replace(oldString, newString) else text.replaceFirst(oldString, newString)
        f.writeText(updated)
        return ToolOutcome.ok("edited ${ctx.jail.relativeOf(f)} ($count replacement${if (count == 1) "" else "s"})")
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }
}

object ListDirTool : Tool {
    override val name = "ls"
    override val description = "List entries of a directory inside the workspace."
    override val readOnly = true
    override val inputSchema = toolSchema(
        required = emptyList(),
        properties = linkedMapOf(
            "path" to stringProp("Directory path (default '.')."),
        ),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val path = input.str("path") ?: "."
        val dir = ctx.jail.resolve(path)
        if (!dir.exists()) return ToolOutcome.error("not found: ${ctx.jail.relativeOf(dir)}")
        if (!dir.isDirectory) return ToolOutcome.error("not a directory: ${ctx.jail.relativeOf(dir)}")

        val children = dir.listFiles() ?: return ToolOutcome.error("cannot list: ${ctx.jail.relativeOf(dir)}")
        if (children.isEmpty()) return ToolOutcome.ok("(empty directory)")

        val sorted = children.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
        val sb = StringBuilder()
        var shown = 0
        for (child in sorted) {
            if (shown >= MAX_LIST_ENTRIES) {
                sb.append("... (${sorted.size - shown} more entries)\n")
                break
            }
            shown++
            val suffix = if (child.isDirectory) "/" else ""
            val size = if (child.isFile) " (${child.length()}B)" else ""
            sb.append(child.name).append(suffix).append(size).append('\n')
        }
        return ToolOutcome.ok(sb.toString().trimEnd('\n'))
    }
}

object GlobTool : Tool {
    override val name = "glob"
    override val description =
        "Find files by glob pattern (supports **, *, ?). Returns workspace-relative paths."
    override val readOnly = true
    override val inputSchema = toolSchema(
        required = listOf("pattern"),
        properties = linkedMapOf(
            "pattern" to stringProp("Glob pattern, e.g. '**/*.kt' or 'src/*.md'."),
            "path" to stringProp("Directory to search from (default '.')."),
        ),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val pattern = input.str("pattern") ?: return ToolOutcome.error("missing 'pattern'")
        if (pattern.isBlank()) return ToolOutcome.error("'pattern' must not be blank")
        val base = ctx.jail.resolve(input.str("path") ?: ".")
        if (!base.isDirectory) return ToolOutcome.error("not a directory: ${ctx.jail.relativeOf(base)}")

        val hits = ArrayList<String>()
        walkFiles(base) { file ->
            val rel = ctx.jail.relativeOf(file)
            if (Glob.matches(pattern, rel)) hits.add(rel)
            hits.size < MAX_LIST_ENTRIES
        }
        if (hits.isEmpty()) return ToolOutcome.ok("no files match '$pattern'")
        hits.sort()
        return ToolOutcome.ok(hits.joinToString("\n"))
    }
}

object GrepTool : Tool {
    override val name = "grep"
    override val description =
        "Regex search file contents. Returns 'path:line: text' matches."
    override val readOnly = true
    override val inputSchema = toolSchema(
        required = listOf("pattern"),
        properties = linkedMapOf(
            "pattern" to stringProp("Java regular expression."),
            "path" to stringProp("Directory or file to search (default '.')."),
            "glob" to stringProp("Only search files matching this glob, e.g. '**/*.kt'."),
            "ignore_case" to boolProp("Case-insensitive matching (default false)."),
        ),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val pattern = input.str("pattern") ?: return ToolOutcome.error("missing 'pattern'")
        if (pattern.isEmpty()) return ToolOutcome.error("'pattern' must not be empty")
        val ignoreCase = input.bool("ignore_case") ?: false
        val glob = input.str("glob")
        val regex = try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: Exception) {
            return ToolOutcome.error("invalid regex: ${e.message}")
        }

        val target = ctx.jail.resolve(input.str("path") ?: ".")
        if (!target.exists()) return ToolOutcome.error("not found: ${ctx.jail.relativeOf(target)}")

        val files = ArrayList<File>()
        if (target.isDirectory) {
            walkFiles(target) { f ->
                val rel = ctx.jail.relativeOf(f)
                if (glob == null || Glob.matches(glob, rel)) files.add(f)
                files.size < MAX_SCAN_FILES
            }
        } else {
            files.add(target)
        }

        val sb = StringBuilder()
        var total = 0
        for (f in files) {
            if (total >= MAX_GREP_HITS) break
            val (text, _) = readTextCapped(f, MAX_SCAN_BYTES)
            if (text.contains(NUL)) continue
            val rel = ctx.jail.relativeOf(f)
            var lineNo = 0
            for (line in text.split('\n')) {
                lineNo++
                if (regex.containsMatchIn(line)) {
                    total++
                    val clipped = if (line.length > MAX_MATCH_LINE_CHARS) {
                        line.take(MAX_MATCH_LINE_CHARS) + "..."
                    } else {
                        line
                    }
                    sb.append(rel).append(':').append(lineNo).append(": ").append(clipped).append('\n')
                    if (total >= MAX_GREP_HITS) {
                        sb.append("[truncated at $MAX_GREP_HITS matches]\n")
                        break
                    }
                }
            }
        }
        if (total == 0) return ToolOutcome.ok("no matches for /$pattern/")
        return ToolOutcome.ok(sb.toString().trimEnd('\n'))
    }
}
