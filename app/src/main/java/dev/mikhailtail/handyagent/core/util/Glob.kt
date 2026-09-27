package dev.mikhailtail.handyagent.core.util

/**
 * 极简 glob 匹配器，支持 `**`（跨目录）、`*`（单层）、`?`（单字符）。
 *
 * 匹配对象是相对 workspace 根的路径（'/' 分隔，无前导 './'）。
 * 若 pattern 不含 '/'，则同时尝试匹配文件名（shell 习惯：`*.kt`）。
 */
object Glob {

    fun toRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when (c) {
                '*' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                        if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                            sb.append("(?:.*/)?")
                            i += 3
                        } else {
                            sb.append(".*")
                            i += 2
                        }
                    } else {
                        sb.append("[^/]*")
                        i++
                    }
                }
                '?' -> { sb.append("[^/]"); i++ }
                '/', '-' -> { sb.append(c); i++ }
                else -> { sb.append(Regex.escape(c.toString())); i++ }
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }

    fun matches(pattern: String, path: String): Boolean {
        val p = path.removePrefix("./")
        val rx = toRegex(pattern)
        if (rx.matches(p)) return true
        if (!pattern.contains('/')) return rx.matches(p.substringAfterLast('/'))
        return false
    }
}
