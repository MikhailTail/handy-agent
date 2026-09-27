package dev.mikhailtail.handyagent.platform.mobile

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.platform.ImageBytesStore
import java.io.ByteArrayOutputStream

/**
 * 截图的编解码。
 *
 * 两条硬约束：
 * 1. **长边不超过 1280、JPEG 质量 80** —— 1080×2400 的原始截图直接 base64 有 2~3MB，
 *    几轮下来就把上下文撑爆了；压到 1280/q80 通常 120~250KB，界面文字仍然清晰可读。
 * 2. `takeScreenshot` 给的是 [HardwareBuffer]，**必须 close**，而且要 copy 成软件位图才能
 *    压缩（HARDWARE 位图不支持 compress）。
 */
object AndroidImageCodec {

    const val MAX_LONG_EDGE = 1280
    const val JPEG_QUALITY = 80

    /** 单张图上限；超过就降档重压，仍然超就放弃（宁可没图也不能把请求撑爆）。 */
    const val MAX_BYTES = 400 * 1024

    /**
     * [HardwareBuffer] → 软件 [Bitmap]。
     *
     * 调用方负责 `buffer.close()` —— 这里 close 会让返回的 Bitmap 失效。
     */
    fun fromHardwareBuffer(buffer: HardwareBuffer, colorSpace: ColorSpace?): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val wrapped = runCatching {
            Bitmap.wrapHardwareBuffer(buffer, colorSpace)
        }.getOrNull() ?: return null
        // HARDWARE 位图不能 compress / getPixel，复制成 ARGB_8888 才可用
        return wrapped.copy(Bitmap.Config.ARGB_8888, false)
    }

    /**
     * 按比例缩放并压缩，写入 [store]，返回可放进历史的 [ImageRef]。
     * 全程失败返回 null —— 截图失败不该中断整轮对话。
     */
    fun encode(source: Bitmap, store: ImageBytesStore, id: String): ImageRef? {
        // 逐档降级：先试 1280/q80，不行就压质量、再降分辨率，直到达标
        for ((longEdge, quality) in LADDER) {
            val bmp = scaleTo(source, longEdge) ?: continue
            val bytes = compress(bmp, quality) ?: continue
            if (bytes.size > MAX_BYTES) continue
            if (!store.put(id, bytes)) return null
            return ImageRef(
                id = id,
                mediaType = "image/jpeg",
                width = bmp.width,
                height = bmp.height,
                byteSize = bytes.size,
            )
        }
        // 各档都不达标：宁可没图，也不能把请求撑爆
        return null
    }

    /** 只缩不放：放大截图只会浪费字节。 */
    fun scaleDown(source: Bitmap): Bitmap? = scaleTo(source, MAX_LONG_EDGE)

    fun scaleTo(source: Bitmap, maxLongEdge: Int): Bitmap? = runCatching {
        val long = maxOf(source.width, source.height)
        if (long <= maxLongEdge) return@runCatching source
        val ratio = maxLongEdge.toFloat() / long.toFloat()
        val w = (source.width * ratio).toInt().coerceAtLeast(1)
        val h = (source.height * ratio).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(source, w, h, true)
    }.getOrNull()

    private fun compress(bmp: Bitmap, quality: Int): ByteArray? = runCatching {
        ByteArrayOutputStream(256 * 1024).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }.getOrNull()

    private val LADDER = listOf(
        MAX_LONG_EDGE to JPEG_QUALITY,
        MAX_LONG_EDGE to 70,
        1024 to 70,
        896 to 60,
    )
}
