package dev.mikhailtail.handyagent.ui.export

import java.io.File
import java.io.FileOutputStream

/** 落到缓存目录里、等待被分享出去的一份导出。 */
data class StagedExport(val file: File, val bytes: Long, val entries: Int)

/**
 * 「分享」出口的落盘逻辑（纯 JVM，可单测）。
 *
 * 为什么分享要**先落盘**：`ACTION_SEND` 传的是 `content://` URI，接收方（文件管理器、
 * 聊天软件）会在另一个进程里按需读取 —— 而 `Exporter` 的 workspace 源是随时可能变的
 * 沙箱文件。所以先把内容定稿到 `cacheDir/exports/`，再把它授权出去：分享出去的是
 * **此刻这一份**，之后 Agent 改动 workspace 不会让接收方读到半截内容。
 *
 * 缓存目录里的旧导出会顺手清理（[TTL_MS]），否则每分享一次就永久多一份副本。
 */
object ExportStaging {

    /** 缓存里的导出保留时长：够用户慢慢挑「分享到哪」，又不至于长期占地方。 */
    const val TTL_MS: Long = 24 * 60 * 60 * 1000L

    /**
     * 把 [plan] 写进 [dir]，返回落盘结果；失败（含建目录失败）返回 null。
     *
     * 同名文件不会被覆盖 —— 见 [uniqueName]。写失败时删掉半截文件再返回 null，
     * 避免留下一个「看着像成功」的空壳。
     */
    fun stageInto(dir: File, plan: ExportPlan, now: Long = System.currentTimeMillis()): StagedExport? {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        pruneOld(dir, now)

        val file = File(dir, uniqueName(dir, plan.suggestedName))
        val result = try {
            FileOutputStream(file).use { out -> Exporter.write(plan, out) }
        } catch (e: Exception) {
            ExportResult(error = e.message ?: "写入失败")
        }
        if (!result.ok) {
            file.delete()
            return null
        }
        // 显式盖时间戳：TTL 从「这次导出」算起，而不是沿用源文件的修改时间。
        file.setLastModified(now)
        return StagedExport(file, result.bytes, result.entries)
    }

    /** 删掉 [dir] 里超过 [ttlMs] 的导出，返回删除个数。 */
    fun pruneOld(dir: File, now: Long, ttlMs: Long = TTL_MS): Int {
        val files = dir.listFiles() ?: return 0
        var removed = 0
        for (f in files) {
            if (!f.isFile) continue
            if (now - f.lastModified() > ttlMs && f.delete()) removed++
        }
        return removed
    }

    /**
     * 在 [dir] 里挑一个不冲突的文件名：`a.zip` → `a-1.zip` → `a-2.zip`。
     *
     * 不覆盖的理由：用户可能正把上一份分享给某个 App（对方进程还没读完），
     * 覆盖会让它读到新内容；也是「分享两次得到两份」这种直觉行为。
     */
    fun uniqueName(dir: File, rawName: String): String {
        val name = Exporter.sanitizeName(rawName)
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot <= 0) name else name.substring(0, dot)
        val ext = if (dot <= 0) "" else name.substring(dot)
        var i = 1
        while (true) {
            val candidate = "$stem-$i$ext"
            if (!File(dir, candidate).exists()) return candidate
            i++
        }
    }
}
