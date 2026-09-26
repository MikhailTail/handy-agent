package dev.pocket.agent.core.sandbox

import java.io.File

/** 任何试图逃出 workspace 根目录的访问都会抛这个异常。 */
class SandboxViolationException(message: String) : Exception(message)

/**
 * 路径监狱：所有文件工具都必须经过它解析路径。
 *
 * 规则：
 * 1. 前导 '/' 视为「沙箱根」，即 chroot 语义（`/etc/passwd` -> `<root>/etc/passwd`）。
 * 2. `..` 会被文本归一化，归最后仍必须落在 root 之内。
 * 3. 已存在的路径走 canonicalFile()，因此符号链接逃逸也会被拦截。
 */
class PathJail(rootDir: File) {

    val root: File = rootDir.absoluteFile.also { it.mkdirs() }.canonicalFile
    private val rootPath: String = root.path

    init {
        require(rootPath.isNotEmpty() && rootPath != "/") { "Sandbox root must not be filesystem root" }
    }

    fun resolve(userPath: String): File {
        val raw = userPath.trim()
        if (raw.isEmpty()) throw SandboxViolationException("Empty path")
        if (raw.contains(NUL)) throw SandboxViolationException("Path contains NUL byte")
        val rel = raw.removePrefix("/")
        val candidate = File(root, rel)
        val canon = canonicalize(candidate)
        if (!contains(canon)) {
            throw SandboxViolationException("Path escapes sandbox: $userPath")
        }
        return canon
    }

    fun contains(f: File): Boolean {
        val p = f.path
        return p == rootPath || p.startsWith(rootPath + File.separator)
    }

    /** 返回相对 root 的显示路径（不含前导斜杠）。 */
    fun relativeOf(f: File): String {
        val p = f.canonicalPath
        if (p == rootPath) return "."
        if (!p.startsWith(rootPath + File.separator)) return p
        return p.substring(rootPath.length + 1)
    }

    private fun canonicalize(f: File): File {
        val missing = ArrayDeque<String>()
        var cur: File? = f.absoluteFile
        while (cur != null && !cur.exists()) {
            val name = cur.name
            if (name.isNotEmpty()) missing.addFirst(name)
            cur = cur.parentFile
        }
        val base = (cur ?: return normalize(f.absoluteFile)) .canonicalFile
        var result = base
        for (name in missing) result = File(result, name)
        return normalize(result)
    }

    /** 纯文本归一化：折叠 '.' 与 '..'。 */
    private fun normalize(f: File): File = File(normalizePath(f.path))

    companion object {
        private val NUL: Char = 0.toChar()

        fun normalizePath(path: String): String {
            val parts = path.split('/')
            val out = ArrayList<String>(parts.size)
            for (part in parts) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.lastIndex) else out.add("..")
                    else -> out.add(part)
                }
            }
            return "/" + out.joinToString("/")
        }
    }
}
