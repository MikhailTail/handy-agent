package dev.pocket.agent.core.util

/** 单行 diff 记录。 */
data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { CONTEXT, ADD, DEL }

    val prefix: Char
        get() = when (kind) {
            Kind.CONTEXT -> ' '
            Kind.ADD -> '+'
            Kind.DEL -> '-'
        }
}

data class DiffStats(val added: Int, val removed: Int) {
    val isEmpty: Boolean get() = added == 0 && removed == 0
    override fun toString(): String = "+$added -$removed"
}

/**
 * 纯 Kotlin 的 LCS 行级 diff。
 * 用于 edit/write 工具的「审批前预览」，以及 UI 红绿渲染。
 */
object DiffEngine {

    /** 超过这个规模就退化为「整块替换」，避免 O(n*m) 卡死 UI。 */
    private const val MAX_DP_CELLS = 4_000_000

    fun compute(oldText: String, newText: String): List<DiffLine> {
        if (oldText == newText) return splitLines(oldText).map { DiffLine(DiffLine.Kind.CONTEXT, it) }

        val a = splitLines(oldText)
        val b = splitLines(newText)

        // 1) 掐掉公共前后缀，大幅缩小 DP 规模
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) suffix++

        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)

        val out = ArrayList<DiffLine>(a.size + b.size)
        for (i in 0 until prefix) out.add(DiffLine(DiffLine.Kind.CONTEXT, a[i]))

        if (midA.size.toLong() * midB.size.toLong() > MAX_DP_CELLS) {
            for (line in midA) out.add(DiffLine(DiffLine.Kind.DEL, line))
            for (line in midB) out.add(DiffLine(DiffLine.Kind.ADD, line))
        } else {
            out.addAll(lcsDiff(midA, midB))
        }

        for (i in 0 until suffix) out.add(DiffLine(DiffLine.Kind.CONTEXT, a[a.size - suffix + i]))
        return out
    }

    private fun lcsDiff(a: List<String>, b: List<String>): List<DiffLine> {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return b.map { DiffLine(DiffLine.Kind.ADD, it) }
        if (m == 0) return a.map { DiffLine(DiffLine.Kind.DEL, it) }

        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(DiffLine(DiffLine.Kind.CONTEXT, a[i])); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out.add(DiffLine(DiffLine.Kind.DEL, a[i])); i++ }
                else -> { out.add(DiffLine(DiffLine.Kind.ADD, b[j])); j++ }
            }
        }
        while (i < n) { out.add(DiffLine(DiffLine.Kind.DEL, a[i])); i++ }
        while (j < m) { out.add(DiffLine(DiffLine.Kind.ADD, b[j])); j++ }
        return out
    }

    fun stats(lines: List<DiffLine>): DiffStats {
        var added = 0
        var removed = 0
        for (l in lines) when (l.kind) {
            DiffLine.Kind.ADD -> added++
            DiffLine.Kind.DEL -> removed++
            else -> Unit
        }
        return DiffStats(added, removed)
    }

    /** 生成标准 unified diff；无变化时返回空串。 */
    fun unified(
        oldText: String,
        newText: String,
        oldName: String = "a",
        newName: String = "b",
        context: Int = 3,
    ): String {
        require(context >= 0) { "context must be >= 0" }
        val ops = compute(oldText, newText)
        if (ops.none { it.kind != DiffLine.Kind.CONTEXT }) return ""

        val size = ops.size
        val oldNo = IntArray(size + 1)
        val newNo = IntArray(size + 1)
        var o = 1
        var n = 1
        for (idx in 0 until size) {
            oldNo[idx] = o
            newNo[idx] = n
            when (ops[idx].kind) {
                DiffLine.Kind.CONTEXT -> { o++; n++ }
                DiffLine.Kind.DEL -> o++
                DiffLine.Kind.ADD -> n++
            }
        }
        oldNo[size] = o
        newNo[size] = n

        val changed = ops.indices.filter { ops[it].kind != DiffLine.Kind.CONTEXT }
        val groups = ArrayList<IntRange>()
        var start = changed.first()
        var end = changed.first()
        for (idx in changed.drop(1)) {
            if (idx - end <= 2 * context + 1) end = idx
            else { groups.add(start..end); start = idx; end = idx }
        }
        groups.add(start..end)

        val sb = StringBuilder()
        sb.append("--- ").append(oldName).append('\n')
        sb.append("+++ ").append(newName).append('\n')
        for (g in groups) {
            val s = maxOf(0, g.first - context)
            val e = minOf(size - 1, g.last + context)
            var oldCount = 0
            var newCount = 0
            for (idx in s..e) when (ops[idx].kind) {
                DiffLine.Kind.CONTEXT -> { oldCount++; newCount++ }
                DiffLine.Kind.DEL -> oldCount++
                DiffLine.Kind.ADD -> newCount++
            }
            val oldStart = if (oldCount == 0) oldNo[s] - 1 else oldNo[s]
            val newStart = if (newCount == 0) newNo[s] - 1 else newNo[s]
            sb.append("@@ -").append(oldStart).append(',').append(oldCount)
                .append(" +").append(newStart).append(',').append(newCount).append(" @@\n")
            for (idx in s..e) {
                sb.append(ops[idx].prefix).append(ops[idx].text).append('\n')
            }
        }
        return sb.toString()
    }

    /** UI 渲染用：带行号的分组视图。 */
    fun renderSideBySide(lines: List<DiffLine>): List<String> =
        lines.map { "${it.prefix} ${it.text}" }

    /** 拆分行为「行列表」；空文本是 0 行，join("\n") 可无损还原。 */
    fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split('\n')

    fun joinLines(lines: List<String>): String = lines.joinToString("\n")
}
