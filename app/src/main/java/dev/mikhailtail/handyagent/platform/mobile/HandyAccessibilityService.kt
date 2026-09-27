package dev.mikhailtail.handyagent.platform.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.mikhailtail.handyagent.core.mobile.FlatTree
import dev.mikhailtail.handyagent.core.mobile.MobileKey
import dev.mikhailtail.handyagent.core.mobile.MobileResult
import dev.mikhailtail.handyagent.core.mobile.ScreenInfo
import dev.mikhailtail.handyagent.core.mobile.UiTreeFlattener
import dev.mikhailtail.handyagent.core.mobile.UnavailableReason
import dev.mikhailtail.handyagent.core.mobile.WindowInfo
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.platform.ImageBytesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * 无障碍服务：mobile use 的**唯一**能力来源。
 *
 * 必须由用户在「设置 → 无障碍」里手动开启。注意 Android 13+ 的侧载限制：
 * 非应用商店安装的包默认**不允许**开启无障碍，用户需要先在应用详情页右上角
 * 选择「允许受限设置」（见 CapabilityDoctor 的引导）。
 *
 * 三条实现约束（都来自平台本身）：
 * 1. `takeScreenshot` 有 **333ms 间隔限制**，连拍会返回 `INTERVAL_TIME_SHORT`；
 * 2. `dispatchGesture` 必须在**主线程**调用，而工具执行在 IO 线程；
 * 3. 拿到的 `HardwareBuffer` **必须 close**，否则很快耗尽图形内存。
 */
class HandyAccessibilityService : AccessibilityService() {

    companion object {
        /** 服务是异步绑定的，且随时可能被系统关掉 —— 每次用之前都要判空。 */
        @Volatile
        var instance: HandyAccessibilityService? = null
            private set

        /** 截图节流：平台限制 333ms，取 350ms 留点余量。 */
        private const val CAPTURE_INTERVAL_MS = 350L

        private const val GESTURE_TIMEOUT_MS = 3_000L
    }

    @Volatile
    private var lastCaptureAt = 0L

    @Volatile
    private var foregroundPackage: String? = null

    @Volatile
    private var lastContentChangeAt = 0L

    private val dumpSeq = AtomicLong(0)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                event.packageName?.toString()?.let { foregroundPackage = it }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                lastContentChangeAt = SystemClock.uptimeMillis()

            else -> Unit
        }
    }

    // ------------------------------------------------------------------ 感知

    fun screenInfo(): ScreenInfo {
        val dm = resources.displayMetrics
        val rotation = runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.rotation
        }.getOrDefault(0)
        return ScreenInfo(
            width = dm.widthPixels,
            height = dm.heightPixels,
            densityDpi = dm.densityDpi,
            rotation = AccessibilityTreeMapper.rotationOf(rotation),
        )
    }

    fun currentPackage(): String? =
        foregroundPackage ?: runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /**
     * 读一次界面。
     *
     * 取 `rootInActiveWindow`，拿不到就退回到"当前活动窗口"的 root ——
     * 输入法弹起、系统弹窗遮挡时前者常常为 null。
     */
    fun dumpTree(includeInvisible: Boolean = false): MobileResult<FlatTree> {
        val root = resolveRoot()
            ?: return MobileResult.Unavailable(
                UnavailableReason.NOT_FOUND,
                "拿不到当前窗口的控件树（可能界面正在切换）",
            )
        val uiNode = AccessibilityTreeMapper.toUiNode(root)
            ?: return MobileResult.Failed("控件树转换失败")
        val tree = UiTreeFlattener.flatten(
            root = uiNode,
            screen = screenInfo(),
            packageName = runCatching { root.packageName?.toString() }.getOrNull(),
            dumpId = "d" + dumpSeq.incrementAndGet(),
            options = dev.mikhailtail.handyagent.core.mobile.FlattenOptions(includeInvisible = includeInvisible),
        )
        return MobileResult.Ok(tree)
    }

    private fun resolveRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        return runCatching { windows.firstOrNull { it.isActive }?.root }.getOrNull()
    }

    fun windows(): List<WindowInfo> = runCatching {
        windows.map { AccessibilityTreeMapper.toWindowInfo(it) }
    }.getOrDefault(emptyList())

    /**
     * 截屏。
     *
     * [store] 由调用方注入（core 的接口），这样 core 不必知道 Android 的文件布局。
     */
    suspend fun capture(store: ImageBytesStore): MobileResult<ImageRef> {
        // 节流：距上次不足 350ms 就等一等，否则平台直接报错
        val since = SystemClock.uptimeMillis() - lastCaptureAt
        if (since in 0 until CAPTURE_INTERVAL_MS) delay(CAPTURE_INTERVAL_MS - since)

        val result = withTimeoutOrNull(2_000) { awaitScreenshot() }
            ?: return MobileResult.Unavailable(UnavailableReason.TIMEOUT, "截图超时")

        return when (result) {
            is Shot.Ok -> {
                lastCaptureAt = SystemClock.uptimeMillis()
                val buffer = result.buffer
                try {
                    val bmp = AndroidImageCodec.fromHardwareBuffer(buffer, result.colorSpace)
                        ?: return MobileResult.Failed("截屏结果无法解码")
                    val ref = AndroidImageCodec.encode(bmp, store, "img_${nextImageId()}.jpg")
                        ?: return MobileResult.Failed("截屏压缩失败")
                    MobileResult.Ok(ref)
                } finally {
                    // 硬性要求：不 close 会很快耗尽图形内存
                    runCatching { buffer.close() }
                }
            }

            is Shot.Secure -> {
                lastCaptureAt = SystemClock.uptimeMillis()
                MobileResult.Unavailable(
                    UnavailableReason.SECURE_WINDOW,
                    "当前界面受保护（FLAG_SECURE），系统拒绝截图",
                )
            }

            is Shot.RateLimited -> {
                delay(CAPTURE_INTERVAL_MS)
                MobileResult.Unavailable(UnavailableReason.RATE_LIMITED, "截图过于频繁")
            }

            is Shot.Failed -> MobileResult.Failed(result.message)
        }
    }

    private sealed interface Shot {
        class Ok(val buffer: android.hardware.HardwareBuffer, val colorSpace: android.graphics.ColorSpace?) : Shot
        data object Secure : Shot
        data object RateLimited : Shot
        data class Failed(val message: String) : Shot
    }

    private suspend fun awaitScreenshot(): Shot = suspendCancellableCoroutine { cont ->
        // 必须给 executor；回调本身很轻（只取 buffer / 错误码），放主线程即可
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    if (cont.isActive) cont.resume(Shot.Ok(result.hardwareBuffer, result.colorSpace))
                }

                override fun onFailure(errorCode: Int) {
                    val shot = when (errorCode) {
                        // 银行/支付类窗口带 FLAG_SECURE，系统直接拒绝截图 —— 这是硬停信号
                        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> Shot.Secure
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> Shot.RateLimited
                        else -> Shot.Failed("截图失败：code=$errorCode")
                    }
                    if (cont.isActive) cont.resume(shot)
                }
            },
        )
    }

    private fun nextImageId(): String =
        "s" + SystemClock.uptimeMillis().toString(36) + "_" + dumpSeq.incrementAndGet()

    // ------------------------------------------------------------------ 操作

    suspend fun tap(x: Int, y: Int, durationMs: Int = 60): MobileResult<Unit> {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, durationMs)
    }

    suspend fun swipe(path: List<Pair<Int, Int>>, durationMs: Int = 300): MobileResult<Unit> {
        if (path.size < 2) return MobileResult.Failed("滑动手势至少需要两个点")
        val p = Path().apply {
            moveTo(path.first().first.toFloat(), path.first().second.toFloat())
            for (i in 1 until path.size) lineTo(path[i].first.toFloat(), path[i].second.toFloat())
        }
        return dispatch(p, durationMs)
    }

    private suspend fun dispatch(path: Path, durationMs: Int): MobileResult<Unit> {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.toLong()))
            .build()

        // dispatchGesture 必须在主线程
        return withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                // 必须显式给类型参数：回调里会 resume 三种不同的子类型，
                // 靠第一次 resume 推断会固定成 Ok，后续 Unavailable/Failed 就编译不过
                suspendCancellableCoroutine<MobileResult<Unit>> { cont ->
                    val ok = runCatching {
                        dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(d: GestureDescription?) {
                                if (cont.isActive) cont.resume(MobileResult.Ok(Unit))
                            }

                            override fun onCancelled(d: GestureDescription?) {
                                // 用户触屏会抢占手势；平台没有主动 cancel 的 API
                                if (cont.isActive) {
                                    cont.resume(
                                        MobileResult.Unavailable(
                                            UnavailableReason.GESTURE_PREEMPTED,
                                            "手势被用户触摸打断",
                                        )
                                    )
                                }
                            }
                        }, null)
                    }.getOrDefault(false)

                    if (!ok && cont.isActive) {
                        cont.resume(MobileResult.Failed("dispatchGesture 被拒绝"))
                    }
                }
            } ?: MobileResult.Unavailable(UnavailableReason.TIMEOUT, "手势执行超时")
        }
    }

    fun globalAction(key: MobileKey): MobileResult<Unit> {
        val action = when (key) {
            MobileKey.BACK -> GLOBAL_ACTION_BACK
            MobileKey.HOME -> GLOBAL_ACTION_HOME
            MobileKey.RECENTS -> GLOBAL_ACTION_RECENTS
            MobileKey.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            MobileKey.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            MobileKey.LOCK_SCREEN -> GLOBAL_ACTION_LOCK_SCREEN
            MobileKey.ENTER, MobileKey.DELETE, MobileKey.SEARCH ->
                return typeKey(key)
        }
        val ok = runCatching { performGlobalAction(action) }.getOrDefault(false)
        return if (ok) {
            MobileResult.Ok(Unit)
        } else {
            MobileResult.Failed("系统拒绝了 ${key.name} 动作")
        }
    }

    /**
     * 回车 / 删除 / 搜索。
     *
     * 无障碍**没有**通用按键注入能力（`injectInputEvent` 需要签名的 `INJECT_EVENTS`），
     * 这几个键只能由自带输入法经 `InputConnection` 发送。输入法还没接入（P8），
     * 所以这里明确报"不支持"，而不是假装成功 —— 让模型知道该换策略（例如改用
     * `mobile_type` 的 submit 参数走 IME）。
     */
    private fun typeKey(key: MobileKey): MobileResult<Unit> = MobileResult.Unavailable(
        UnavailableReason.IME_NOT_SELECTED,
        "按键 ${key.name} 需要自带输入法支持，当前尚未接入",
    )

    /**
     * 输入文本。
     *
     * 第一档走 `ACTION_SET_TEXT`（原子、无需输入法），很多 Compose/WebView 控件不实现它，
     * 此时由调用方降级到 IME 或剪贴板方案。
     */
    suspend fun typeText(text: String): MobileResult<Unit> {
        val focus = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: return MobileResult.Unavailable(
                UnavailableReason.NOT_FOUND,
                "没有找到输入框，请先点击目标输入框",
            )

        // 密码框一律拒绝（执行侧还有一层同样的检查）
        if (runCatching { focus.isPassword }.getOrDefault(false)) {
            return MobileResult.Failed("目标是密码框，已拒绝自动输入")
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = runCatching {
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)

        if (ok && verifyText(focus, text)) return MobileResult.Ok(Unit)

        // 降级：剪贴板 + 粘贴（命中率更高，但会动用户剪贴板）
        return pasteFromClipboard(focus, text)
    }

    private fun verifyText(node: AccessibilityNodeInfo, expected: String): Boolean =
        runCatching { node.text?.toString()?.contains(expected.take(20)) == true }.getOrDefault(false)

    private fun pasteFromClipboard(node: AccessibilityNodeInfo, text: String): MobileResult<Unit> {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return MobileResult.Failed("剪贴板不可用")
        return try {
            cm.setPrimaryClip(ClipData.newPlainText("Handy Agent", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                MobileResult.Ok(Unit)
            } else {
                MobileResult.Failed("粘贴失败，该输入框可能禁止粘贴")
            }
        } catch (e: Exception) {
            MobileResult.Failed("粘贴时出错：${e.message}")
        }
    }

    /** 动作后等界面稳定：内容不再变化或超时即返回。 */
    suspend fun settle(maxWaitMs: Long = 600, quietMs: Long = 150) {
        val deadline = SystemClock.uptimeMillis() + maxWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            val since = SystemClock.uptimeMillis() - lastContentChangeAt
            if (since >= quietMs) return
            delay(50)
        }
    }
}
