package dev.pocket.agent.ui.files

import java.io.File

/** 文件列表中的一项。 */
data class FileEntry(
    val name: String,
    /** workspace 相对路径，始终以 '/' 分段、无前导 '/'。 */
    val path: String,
    val isDir: Boolean,
    val size: Long,
) {
    val readableSize: String get() = if (isDir) "—" else FileBrowser.humanSize(size)
}

/** 文件预览结果。 */
data class FilePreview(
    val path: String,
    val text: String,
    val truncated: Boolean = false,
    val binary: Boolean = false,
    val missing: Boolean = false,
) {
    val ok: Boolean get() = !binary && !missing
}

/** 目录读取失败时的说明（而不是抛异常）。 */
data class DirListing(val path: String, val entries: List<FileEntry>, val error: String? = null)

/**
 * Files 页的纯逻辑：workspace 内按相对路径浏览。
 *
 * 只依赖 `java.io.File`，因此可以在宿主 JVM 上直接单测。所有越界路径都会被
 * 规整回 workspace 内（[normalize] 吃掉 `..`），与 [dev.pocket.agent.core.sandbox.PathJail]
 * 的约束方向一致 —— UI 层不做第二套安全判断，只保证不会展示沙箱外的内容。
 */
object FileBrowser {

    const val ROOT = ""
    const val MAX_PREVIEW_BYTES = 256 * 1024

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** 规整相对路径：去掉前导/重复 '/'，消解 `.` 与 `..`（不逃出根）。 */
    fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (part in path.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }

    fun join(dir: String, name: String): String =
        if (dir.isEmpty()) normalize(name) else normalize("$dir/$name")

    /** 上一级；已在根则返回 null。 */
    fun parentOf(path: String): String? {
        val norm = normalize(path)
        if (norm.isEmpty()) return null
        val idx = norm.lastIndexOf('/')
        return if (idx < 0) ROOT else norm.substring(0, idx)
    }

    /** 面包屑：[根, a, a/b]。 */
    fun breadcrumb(path: String): List<String> {
        val norm = normalize(path)
        val out = ArrayList<String>()
        out.add(ROOT)
        if (norm.isEmpty()) return out
        var acc = ""
        for (part in norm.split('/')) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            out.add(acc)
        }
        return out
    }

    fun displayName(path: String): String =
        if (path.isEmpty()) "/" else path.substringAfterLast('/')

    /**
     * 把 workspace 相对路径解析成真实文件；越界（含符号链接逃逸）返回 null。
     *
     * 这是本文件里**唯一**的「相对路径 → File」入口：浏览、预览、导出都走它，
     * 于是「不会展示沙箱外的内容」这条约束只有一处实现、不会各自为政。
     */
    fun resolve(workspace: File, relPath: String): File? {
        val f = File(workspace, normalize(relPath))
        return if (contained(workspace, f)) f else null
    }

    fun list(workspace: File, relPath: String): DirListing {
        val rel = normalize(relPath)
        val dir = resolve(workspace, rel) ?: return DirListing(rel, emptyList(), "路径越界")
        if (!dir.exists()) return DirListing(rel, emptyList(), "目录不存在")
        if (!dir.isDirectory) return DirListing(rel, emptyList(), "不是目录")

        val children = dir.listFiles() ?: return DirListing(rel, emptyList(), "无法读取目录")
        val entries = children
            .map { FileEntry(it.name, join(rel, it.name), it.isDirectory, if (it.isFile) it.length() else 0L) }
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
        return DirListing(rel, entries)
    }

    fun preview(workspace: File, relPath: String, maxBytes: Int = MAX_PREVIEW_BYTES): FilePreview {
        val rel = normalize(relPath)
        val f = resolve(workspace, rel) ?: return FilePreview(rel, "", missing = true)
        if (!f.isFile) return FilePreview(rel, "", missing = true)

        val limit = minOf(maxBytes, MAX_PREVIEW_BYTES).coerceAtLeast(1)
        return try {
            f.inputStream().use { ins ->
                val buf = ByteArray(limit)
                var read = 0
                while (read < limit) {
                    val n = ins.read(buf, read, limit - read)
                    if (n < 0) break
                    read += n
                }
                val binary = buf.take(read).any { it == 0.toByte() }
                if (binary) {
                    FilePreview(rel, "", binary = true)
                } else {
                    FilePreview(rel, String(buf, 0, read, Charsets.UTF_8), truncated = f.length() > read)
                }
            }
        } catch (e: Exception) {
            FilePreview(rel, "", missing = true)
        }
    }

    /** 目标必须落在 workspace 内（比对 canonicalPath，挡住符号链接逃逸）。 */
    private fun contained(workspace: File, target: File): Boolean = try {
        val root = workspace.canonicalFile
        val t = target.canonicalFile
        t == root || t.path.startsWith(root.path + File.separator)
    } catch (e: Exception) {
        false
    }
}
