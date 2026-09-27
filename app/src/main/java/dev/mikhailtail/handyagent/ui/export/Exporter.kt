package dev.mikhailtail.handyagent.ui.export

import dev.mikhailtail.handyagent.ui.files.FileBrowser
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 一次导出的描述：**导出什么、叫什么名字、按什么类型给系统**。
 *
 * 导出源有两种（见 [Source]）：workspace 里的路径，或一段内联文本（对话记录 ——
 * 记录只活在内存里，本来就不在沙箱中）。把源放进 plan 里而不是当作 `write()` 的
 * 另一个参数，是为了让「内联导出」完全不需要 workspace 概念。
 */
data class ExportPlan(
    /** 给用户看的一行说明，如 `src/main.kt`、`整个 workspace`。 */
    val label: String,
    val suggestedName: String,
    val mimeType: String,
    /** true = 打包成 zip 再导出（目录必然为 true）。 */
    val zipped: Boolean = false,
    val source: Source,
) {
    sealed interface Source {
        /** 沙箱内的一条相对路径；`relPath == ""` 表示整个 workspace。 */
        data class Workspace(val root: File, val relPath: String) : Source

        /** 直接写出去的文本。 */
        data class Inline(val text: String) : Source
    }
}

/** 导出结果。[error] 非 null 表示失败，其余字段是成功时的统计。 */
data class ExportResult(
    val bytes: Long = 0,
    val entries: Int = 0,
    /** 跳过的符号链接 / 特殊文件数，用于告诉用户「少导出了什么」。 */
    val skipped: Int = 0,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null

    /** 人类可读的一行结果，直接进 Toast。 */
    fun describe(): String = when {
        error != null -> "导出失败：$error"
        entries > 1 -> "已导出 $entries 个文件 · ${humanSize(bytes)}"
        else -> "已导出 · ${humanSize(bytes)}"
    }

    companion object {
        fun humanSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}

/**
 * 导出的**纯逻辑**：规划文件名/类型 + 把内容写进任意 [OutputStream]。
 *
 * 为什么写成「往 OutputStream 写」而不是「返回 ByteArray / 返回 File」：
 * 两条出口共用同一段代码 ——
 * 1. SAF「另存为」拿到的是 `ContentResolver.openOutputStream(uri)`；
 * 2. 「分享」先落到 `cacheDir/exports/` 再由 FileProvider 授权出去。
 * 两者都不需要把整个 zip 读进内存（workspace 可能很大），出错也只是流写失败。
 *
 * 也因此这个类零 `android.*` 依赖，可以在宿主 JVM 上直接单测（见 ExporterTest）。
 */
object Exporter {

    /** 单次导出的条目上限：超过就整体拒绝，而不是写出半个 zip。 */
    const val MAX_ENTRIES = 20_000

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** 文件名里不能出现的字符（含控制字符）：SAF / 各家文件系统都会拒绝。 */
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    /** 「另存为」与分享都用得上的兜底类型：没有扩展名对应关系时的安全选择。 */
    const val OCTET_STREAM = "application/octet-stream"

    private const val OCTET = OCTET_STREAM

    /** 扩展名 → MIME。用于把文件正确地标给系统（分享时的类型、保存时的图标）。 */
    private val MIME_BY_EXT = mapOf(
        "txt" to "text/plain", "text" to "text/plain", "log" to "text/plain",
        "sh" to "text/plain", "bash" to "text/plain", "ini" to "text/plain",
        "cfg" to "text/plain", "conf" to "text/plain", "properties" to "text/plain",
        "kt" to "text/plain", "kts" to "text/plain", "java" to "text/plain",
        "py" to "text/plain", "rb" to "text/plain", "go" to "text/plain",
        "c" to "text/plain", "h" to "text/plain", "cpp" to "text/plain",
        "rs" to "text/plain", "sql" to "text/plain", "yml" to "text/plain",
        "yaml" to "text/plain", "toml" to "text/plain", "gradle" to "text/plain",
        "json" to "application/json",
        "xml" to "application/xml",
        "csv" to "text/csv",
        "md" to "text/markdown", "markdown" to "text/markdown",
        "html" to "text/html", "htm" to "text/html",
        "css" to "text/css",
        "js" to "text/javascript", "mjs" to "text/javascript",
        "png" to "image/png",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "gz" to "application/gzip",
        "apk" to "application/vnd.android.package-archive",
    )

    /**
     * MIME → 系统「另存为」时会自动追加的扩展名。
     *
     * 这不是洁癖：DocumentsUI 的规则是「提供的文件名若不以该 MIME 的扩展名结尾，
     * 就补一个」。于是用 `text/plain` 保存 `main.kt` 会得到 `main.kt.txt`。
     * 所以只有【扩展名与 MIME 对得上】时才报具体 MIME，否则一律报 octet-stream
     * （octet-stream 无对应扩展名，系统不会补，文件名原样保留）。
     */
    private val EXT_OF_MIME = mapOf(
        "text/plain" to setOf("txt"),
        "application/json" to setOf("json"),
        "application/xml" to setOf("xml"),
        "text/csv" to setOf("csv"),
        "text/markdown" to setOf("md", "markdown"),
        "text/html" to setOf("html", "htm"),
        "text/css" to setOf("css"),
        "text/javascript" to setOf("js", "mjs"),
        "image/png" to setOf("png"),
        "image/jpeg" to setOf("jpg", "jpeg"),
        "image/gif" to setOf("gif"),
        "image/webp" to setOf("webp"),
        "application/pdf" to setOf("pdf"),
        "application/zip" to setOf("zip"),
        "application/gzip" to setOf("gz"),
        "application/vnd.android.package-archive" to setOf("apk"),
    )

    fun timestamp(now: LocalDateTime = LocalDateTime.now()): String = now.format(STAMP)

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.lastIndex) "" else name.substring(dot + 1).lowercase()
    }

    /** 文件「本来是什么类型」；分享时用它，因为分享不涉及扩展名补全。 */
    fun mimeOf(name: String): String = MIME_BY_EXT[extensionOf(name)] ?: OCTET

    /** 「另存为」时该报的类型：保证系统不会给好文件名再补一个扩展名。 */
    fun saveAsMime(name: String): String {
        val mime = mimeOf(name)
        val ext = extensionOf(name)
        return if (EXT_OF_MIME[mime]?.contains(ext) == true) mime else OCTET
    }

    /** 去掉路径分隔符与控制字符，并保证非空 —— SAF 的 EXTRA_TITLE 不接受这些。 */
    fun sanitizeName(raw: String): String {
        val cleaned = ILLEGAL.replace(raw, "_").trim().trim('.')
        return cleaned.ifEmpty { "export" }
    }

    /** 目录导出为 `<目录名>-<时间戳>.zip`；根目录则叫 `workspace-<时间戳>.zip`。 */
    fun zipNameFor(relPath: String, now: LocalDateTime = LocalDateTime.now()): String {
        val base = FileBrowser.normalize(relPath).substringAfterLast('/').ifEmpty { "workspace" }
        return sanitizeName("$base-${timestamp(now)}.zip")
    }

    /**
     * 规划一次 workspace 导出；目标不存在或越界时返回 null（UI 只提示，不抛）。
     */
    fun plan(workspace: File, relPath: String, now: LocalDateTime = LocalDateTime.now()): ExportPlan? {
        val rel = FileBrowser.normalize(relPath)
        val target = FileBrowser.resolve(workspace, rel) ?: return null
        if (!target.exists()) return null

        val label = rel.ifEmpty { "整个 workspace" }
        return if (target.isDirectory) {
            ExportPlan(
                label = label,
                suggestedName = zipNameFor(rel, now),
                mimeType = "application/zip",
                zipped = true,
                source = ExportPlan.Source.Workspace(workspace, rel),
            )
        } else {
            ExportPlan(
                label = rel,
                suggestedName = sanitizeName(target.name),
                mimeType = mimeOf(target.name),
                zipped = false,
                source = ExportPlan.Source.Workspace(workspace, rel),
            )
        }
    }

    /** 对话记录的导出计划：内容直接内联，不经过 workspace（记录本身不在沙箱里）。 */
    fun inlinePlan(name: String, label: String, text: String): ExportPlan = ExportPlan(
        label = label,
        suggestedName = sanitizeName(name),
        mimeType = saveAsMime(name),
        zipped = false,
        source = ExportPlan.Source.Inline(text),
    )

    /**
     * 把 [plan] 的内容写进 [out]。
     *
     * 不关闭 [out]（交给调用方；SAF 的流由 `use` 关闭即提交），但 zip 分支会调用
     * `ZipOutputStream.finish()`，因此返回时内容已经是完整可读的。
     */
    fun write(
        plan: ExportPlan,
        out: OutputStream,
        /** 仅测试用的收敛点：默认就是 [MAX_ENTRIES]。 */
        maxEntries: Int = MAX_ENTRIES,
    ): ExportResult {
        when (val source = plan.source) {
            is ExportPlan.Source.Inline -> return try {
                val bytes = source.text.toByteArray(StandardCharsets.UTF_8)
                out.write(bytes)
                out.flush()
                ExportResult(bytes.size.toLong(), entries = 1)
            } catch (e: Exception) {
                ExportResult(error = e.message ?: "写入失败")
            }

            is ExportPlan.Source.Workspace -> {
                val target = FileBrowser.resolve(source.root, source.relPath)
                    ?: return ExportResult(error = "路径越界，已拒绝导出")
                if (!target.exists()) return ExportResult(error = "文件不存在")
                return if (target.isDirectory) {
                    zipDirectory(target, out, maxEntries)
                } else {
                    copyFile(target, out)
                }
            }
        }
    }

    private fun copyFile(file: File, out: OutputStream): ExportResult = try {
        var total = 0L
        file.inputStream().use { ins -> total = ins.copyTo(out) }
        out.flush()
        ExportResult(total, entries = 1)
    } catch (e: Exception) {
        ExportResult(error = e.message ?: "写入失败")
    }

    /** zip 内的一条：目录以 `/` 结尾。 */
    private data class ZipItem(val name: String, val file: File, val dir: Boolean)

    /**
     * 先走一遍目录树把条目收集齐，再开始写。
     *
     * 两段式的理由：条目超限时要在**写出任何字节之前**失败。若边写边判断，
     * SAF 上会留下一个半截的 zip 文档 —— 用户看到的是「导出成功但文件打不开」。
     */
    private class ZipWalker(private val maxEntries: Int) {
        val items = ArrayList<ZipItem>()
        var skipped = 0
        var overflow = false

        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: return
            for (child in children) {
                if (items.size > maxEntries) {
                    overflow = true
                    return
                }
                val name = prefix + child.name
                when {
                    // 符号链接一律跳过：即使解析后仍在 workspace 内，
                    // 也没必要把「同一份内容」在 zip 里出现两次。
                    Files.isSymbolicLink(child.toPath()) -> skipped++
                    child.isDirectory -> {
                        items.add(ZipItem("$name/", child, dir = true))
                        walk(child, "$name/")
                    }
                    child.isFile -> items.add(ZipItem(name, child, dir = false))
                    else -> skipped++
                }
            }
        }
    }

    private fun zipDirectory(root: File, out: OutputStream, maxEntries: Int): ExportResult {
        val walker = ZipWalker(maxEntries)
        // zip 里保留顶层目录名：「导出 src」得到 src/main.kt，解压不会散落一地。
        walker.walk(root, "${root.name}/")

        if (walker.overflow) return ExportResult(error = "条目超过 $maxEntries 个，请改为导出子目录")
        if (walker.items.isEmpty()) return ExportResult(error = "目录是空的")

        var bytes = 0L
        var entries = 0
        return try {
            val zip = ZipOutputStream(out)
            for (item in walker.items) {
                val entry = ZipEntry(item.name).apply { time = item.file.lastModified() }
                zip.putNextEntry(entry)
                if (!item.dir) {
                    item.file.inputStream().use { ins -> bytes += ins.copyTo(zip) }
                    entries++
                }
                zip.closeEntry()
            }
            zip.finish()
            zip.flush()
            ExportResult(bytes, entries, walker.skipped)
        } catch (e: Exception) {
            ExportResult(error = e.message ?: "打包失败")
        }
    }
}
