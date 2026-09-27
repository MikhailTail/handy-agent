package dev.mikhailtail.handyagent.core.platform

import dev.mikhailtail.handyagent.core.model.ImageData
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.provider.ImageResolver
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * 图片字节的落盘存储。
 *
 * 只用 `java.io`，所以实现放在 core、可以在宿主 JVM 上测；
 * Android 侧只负责 Bitmap 的编解码（见 platform/mobile/AndroidImageCodec）。
 *
 * 所有方法都不抛异常：图片是「锦上添花」的能力，读写失败应当降级为文本占位，
 * 绝不能让一次截图失败中断整轮对话。
 */
interface ImageBytesStore {
    fun put(id: String, bytes: ByteArray): Boolean
    fun get(id: String): ByteArray?
    fun delete(id: String): Boolean
    fun list(): List<String>
    fun totalBytes(): Long

    /** 保留最近 [maxCount] 张、[maxAgeDays] 天内的图片，返回删除数量。 */
    fun purge(now: Long, maxCount: Int = 200, maxAgeDays: Int = 7): Int
}

class FileImageBytesStore(private val dir: File) : ImageBytesStore {

    init {
        runCatching { dir.mkdirs() }
    }

    override fun put(id: String, bytes: ByteArray): Boolean {
        if (!safeId(id)) return false
        return try {
            val target = File(dir, id)
            // 先写临时文件再移动，避免读到写了一半的图。
            // 用 Files.move 而非 renameTo —— 后者在 Windows 上无法覆盖已存在的目标文件。
            val tmp = File(dir, "$id.tmp")
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun get(id: String): ByteArray? {
        if (!safeId(id)) return null
        return try {
            val f = File(dir, id)
            if (f.isFile) f.readBytes() else null
        } catch (e: Exception) {
            null
        }
    }

    override fun delete(id: String): Boolean {
        if (!safeId(id)) return false
        return runCatching { File(dir, id).delete() }.getOrDefault(false)
    }

    override fun list(): List<String> =
        dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    override fun totalBytes(): Long =
        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    override fun purge(now: Long, maxCount: Int, maxAgeDays: Int): Int {
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return 0
        val cutoff = now - maxAgeDays.toLong() * 24L * 60L * 60L * 1000L
        var removed = 0
        files.forEachIndexed { index, f ->
            if (index >= maxCount || f.lastModified() < cutoff) {
                if (f.delete()) removed++
            }
        }
        return removed
    }

    /**
     * id 会成为文件名，必须挡住路径穿越 —— 它可能来自模型输出或落盘的会话文件，
     * 属于不可信输入。
     */
    private fun safeId(id: String): Boolean =
        id.isNotBlank() &&
            !id.contains('/') &&
            !id.contains('\\') &&
            !id.contains("..") &&
            !id.contains(NUL)

    private companion object {
        const val NUL = '\u0000'
    }
}

/** 把 [ImageRef] 解析成 base64 载荷交给 Provider 序列化；读不到就返回 null（降级为文本占位）。 */
class StoreImageResolver(private val store: ImageBytesStore) : ImageResolver {
    override fun resolve(ref: ImageRef): ImageData? {
        if (!ref.valid) return null
        val bytes = store.get(ref.id) ?: return null
        if (bytes.isEmpty()) return null
        return ImageData(ref.mediaType, Base64.getEncoder().encodeToString(bytes))
    }
}
