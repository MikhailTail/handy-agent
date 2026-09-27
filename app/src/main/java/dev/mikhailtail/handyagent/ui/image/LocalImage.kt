package dev.mikhailtail.handyagent.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.platform.ImageBytesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片字节存储的位置。
 *
 * 用 CompositionLocal 而不是层层传参：时间轴、审批卡、文件页都要显示图片，
 * 一路透传会把签名污染得到处都是。
 */
val LocalImageStore = staticCompositionLocalOf<ImageBytesStore?> { null }

/**
 * 位图缓存。
 *
 * 时间轴会频繁重组（流式输出时每来一个 delta 就重组一次），如果每次都重新解码
 * 磁盘上的 JPEG，滚动会肉眼可见地卡。按字节数限制上限，交给系统在内存压力下回收。
 */
object BitmapCache {

    private const val MAX_BYTES = 32 * 1024 * 1024

    private val lru = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(id: String): Bitmap? = lru.get(id)

    /** 从存储里读并解码。失败返回 null（图被清理掉了、文件损坏等），由 UI 显示占位。 */
    fun load(store: ImageBytesStore, ref: ImageRef): Bitmap? {
        lru.get(ref.id)?.let { return it }
        val bytes = store.get(ref.id) ?: return null
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return null
        lru.put(ref.id, bmp)
        return bmp
    }

    fun clear() = lru.evictAll()
}

/**
 * 渲染一张本地图片。
 *
 * 解码放在 IO 线程（主线程解码大图会掉帧），解码完成前显示一个同尺寸的占位块，
 * 避免列表高度在图片加载前后跳变。
 */
@Composable
fun LocalImage(
    ref: ImageRef,
    modifier: Modifier = Modifier,
    onClick: ((ImageRef) -> Unit)? = null,
) {
    val store = LocalImageStore.current
    val bitmap by produceState<Bitmap?>(initialValue = BitmapCache.get(ref.id), ref.id) {
        if (store == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) { BitmapCache.load(store, ref) }
    }

    val shape = RoundedCornerShape(10.dp)
    val clickable = if (onClick != null) Modifier.clickable { onClick(ref) } else Modifier

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(clickable),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Text(
                text = "图片不可用（${ref.id}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
