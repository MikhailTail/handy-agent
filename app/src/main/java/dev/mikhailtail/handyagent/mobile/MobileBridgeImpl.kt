package dev.mikhailtail.handyagent.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.mikhailtail.handyagent.kernel.api.MobileApp
import dev.mikhailtail.handyagent.kernel.api.MobileCapability
import dev.mikhailtail.handyagent.kernel.api.MobileKey
import dev.mikhailtail.handyagent.kernel.api.MobileNotification
import dev.mikhailtail.handyagent.kernel.api.MobileResult
import dev.mikhailtail.handyagent.kernel.api.MobileState
import dev.mikhailtail.handyagent.kernel.api.ScrollDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * 无障碍服务的本体 —— mobile use 的唯一能力来源。
 *
 * **四项能力缺一不可**（声明在 `res/xml/accessibility_service_config.xml`）：
 * `canRetrieveWindowContent` 读控件树、`canPerformGestures` 分发手势、
 * `canTakeScreenshot` 截屏（API 30+）、`flagRetrieveInteractiveWindows` 拿得到通知栏等窗口。
 *
 * 几个必须遵守的平台约束，踩了就是偶发崩溃或静默失败：
 * - `dispatchGesture` **必须在主线程**调用；
 * - `takeScreenshot` 有**调用频率限制**（约每 333ms 一次），连打会被限流；
 * - 截图返回的 `HardwareBuffer` **必须 close**，否则会耗尽图形内存；
 * - 节点引用**不能跨步骤保存** —— 界面一变就失效，必须每步重新 dump。
 */
class MobileBridgeImpl : AccessibilityService(), MobileCapability {

    /** 最近一次快照的节点表。索引只在对应的 dumpId 内有效。 */
    private val lastNodes = AtomicReference<List<AccessibilityNodeInfo>>(emptyList())
    private val lastDumpId = AtomicReference<String?>(null)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ─── MobileCapability ───────────────────────────────────────────────────

    override fun isReady(): Boolean = instance === this

    override fun unavailableReason(): String? =
        if (isReady()) null else "无障碍服务未连接（可能被系统关闭或尚未授权）"

    override suspend fun listApps(): List<MobileApp> = withContext(Dispatchers.IO) {
        val pm = packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { info ->
                MobileApp(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString(),
                )
            }
            // 同一个应用可能有多个启动 Activity，按包名去重。
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }

    override suspend fun getState(): MobileState {
        val root = rootInActiveWindow
        val flat = UiTreeFlattener.flatten(root)

        val dumpId = UUID.randomUUID().toString().take(8)
        lastNodes.set(flat.nodes)
        lastDumpId.set(dumpId)

        val metrics = resources.displayMetrics
        val shot = captureScreenshot()

        return MobileState(
            dumpId = dumpId,
            packageName = root?.packageName?.toString(),
            appLabel = root?.packageName?.toString()?.let { pkg ->
                runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
                        .toString()
                }.getOrNull()
            },
            treeText = flat.text,
            // 截图被 FLAG_SECURE 拒绝时为 null —— 这是**能力限制**而不是错误，
            // 界面上仍然可以用控件清单操作。
            screenshotBase64 = shot,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            secure = shot == null && root != null,
        )
    }

    override suspend fun click(index: Int, dumpId: String): MobileResult<Unit> {
        val node = resolveNode(index, dumpId) ?: return staleOrMissing(index, dumpId)

        // 优先用无障碍动作：它直接作用在元素上，不受布局位置影响。
        val acted = runCatching {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }.getOrDefault(false)

        if (acted) return MobileResult.Ok(Unit)

        // 元素不可点（很多自绘控件如此）时退回坐标点击：手势落在元素中心，
        // 仍然比让模型自己猜坐标准。
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return tap(rect.centerX(), rect.centerY())
    }

    override suspend fun tap(x: Int, y: Int): MobileResult<Unit> {
        val ok = withContext(Dispatchers.Main) {
            withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                        .build()
                    // dispatchGesture 必须在主线程，否则静默失败。
                    val dispatched = dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(description: GestureDescription?) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onCancelled(description: GestureDescription?) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                        null,
                    )
                    if (!dispatched && cont.isActive) cont.resume(false)
                }
            }
        }
        return if (ok == true) MobileResult.Ok(Unit)
        else MobileResult.Failed("点击 ($x, $y) 未成功（手势被拒绝或超时）")
    }

    override suspend fun setValue(index: Int, dumpId: String, text: String): MobileResult<Unit> {
        val node = resolveNode(index, dumpId) ?: return staleOrMissing(index, dumpId)

        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)

        return if (ok) MobileResult.Ok(Unit)
        else MobileResult.Failed(
            "这个输入框不接受直接赋值（有些自绘输入框如此）。" +
                "可以先 mobile_click 选中它，再用 mobile_press_key 逐字输入。",
        )
    }

    override suspend fun scroll(
        index: Int?,
        dumpId: String,
        direction: ScrollDirection,
        pages: Double,
    ): MobileResult<Unit> {
        val (w, h) = screenSize()
        val (sx, sy, ex, ey) = when (direction) {
            ScrollDirection.DOWN -> listOf(w / 2, h * 3 / 4, w / 2, h / 4)
            ScrollDirection.UP -> listOf(w / 2, h / 4, w / 2, h * 3 / 4)
            ScrollDirection.RIGHT -> listOf(w / 4, h / 2, w * 3 / 4, h / 2)
            ScrollDirection.LEFT -> listOf(w * 3 / 4, h / 2, w / 4, h / 2)
        }.map { it.toFloat() }

        val ok = withContext(Dispatchers.Main) {
            withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val path = Path().apply {
                        moveTo(sx, sy)
                        lineTo(ex, ey)
                    }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                        .build()
                    dispatchGesture(
                        gesture,
                        object : GestureResultCallback() {
                            override fun onCompleted(description: GestureDescription?) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onCancelled(description: GestureDescription?) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                        null,
                    )
                }
            }
        }
        return if (ok == true) MobileResult.Ok(Unit) else MobileResult.Failed("滚动未成功")
    }

    override suspend fun pressKey(key: MobileKey): MobileResult<Unit> {
        // GLOBAL_ACTION_* 是系统级动作（返回/主页/任务切换）；
        // 其余按键走 dispatchGesture 之外的路径没有通用做法，这里只支持系统动作。
        val globalAction = when (key) {
            MobileKey.BACK -> GLOBAL_ACTION_BACK
            MobileKey.HOME -> GLOBAL_ACTION_HOME
            MobileKey.APP_SWITCH -> GLOBAL_ACTION_RECENTS
            else -> null
        }
        if (globalAction != null) {
            val ok = withContext(Dispatchers.Main) { performGlobalAction(globalAction) }
            return if (ok) MobileResult.Ok(Unit) else MobileResult.Failed("按键 $key 未生效")
        }
        return MobileResult.Failed(
            "$key 需要作用在具体输入框上。请先点击目标控件，再用 mobile_set_value 写入。",
        )
    }

    override suspend fun launchApp(packageName: String): MobileResult<Unit> =
        withContext(Dispatchers.IO) {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return@withContext MobileResult.Failed("找不到可启动的应用：$packageName")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }
                .fold(
                    onSuccess = { MobileResult.Ok(Unit) },
                    onFailure = { MobileResult.Failed("启动失败：${it.message}") },
                )
        }

    override suspend fun readNotifications(): List<MobileNotification> {
        // 通知读取要靠 NotificationListenerService，与无障碍是两套授权。
        // 阶段 5 暂未接入，返回空列表而不是伪造数据。
        return emptyList()
    }

    // ─── 内部 ───────────────────────────────────────────────────────────────

    /**
     * 按 index + dumpId 取出节点。
     *
     * **dumpId 不匹配一律拒绝**（返回 null 由调用方转成 Stale）：
     * 界面早已变化，旧索引此刻可能指向完全不同的元素 —— 在手机上那可能是"删除"
     * 或"确认转账"。宁可让模型重新观察一次。
     */
    private fun resolveNode(index: Int, dumpId: String): AccessibilityNodeInfo? {
        if (lastDumpId.get() != dumpId) return null
        return lastNodes.get().getOrNull(index)
    }

    private fun staleOrMissing(index: Int, dumpId: String): MobileResult<Unit> =
        if (lastDumpId.get() != dumpId) {
            MobileResult.Stale(
                "dump_id 已过期（界面变化或不是最近一次观察）。请重新调用 mobile_get_state 取新清单。",
            )
        } else {
            MobileResult.Failed("序号 $index 不在最近一次观察的清单里，请重新调用 mobile_get_state。")
        }

    private fun screenSize(): Pair<Int, Int> =
        resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /**
     * 截屏（API 30+）。
     *
     * 用带回调的 `takeScreenshot` 而不是旧版 API —— 只有它能区分
     * `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`（受保护界面），那正是"银行/支付界面"
     * 应该停手交还给用户的信号。
     *
     * **HardwareBuffer 必须 close**：它占的是图形内存，泄漏几次就会让后续截图失败。
     */
    private suspend fun captureScreenshot(): String? =
        withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        // 回调线程：用主线程执行器即可，我们在回调里只做编码不做重活。
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(result: ScreenshotResult) {
                                val bitmap = Bitmap.wrapHardwareBuffer(
                                    result.hardwareBuffer,
                                    result.colorSpace,
                                )
                                result.hardwareBuffer.close()   // 必须关，见上
                                val encoded = bitmap?.let { encodeJpeg(it) }
                                bitmap?.recycle()
                                if (cont.isActive) cont.resume(encoded)
                            }

                            override fun onFailure(errorCode: Int) {
                                // 受保护界面会走到这里（ERROR_TAKE_SCREENSHOT_SECURE_WINDOW）。
                                // 返回 null 而不是抛错：拿不到截图仍可用控件清单操作，
                                // 由上层决定是否硬停。
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }

    private fun encodeJpeg(bitmap: Bitmap): String? = runCatching {
        // 长边缩到 1280：手机上全屏截图是 1080×2400，原图进上下文太占空间，
        // 而模型判断界面元素并不需要那个分辨率。
        val scaled = scaleToMaxEdge(bitmap, MAX_IMAGE_EDGE)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== bitmap) scaled.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt(),
            (bitmap.height * ratio).toInt(),
            true,
        )
    }

    companion object {
        /**
         * 服务实例 —— 由 `onServiceConnected` 写入。
         *
         * 用静态引用是因为 Android 的无障碍服务由系统绑定，应用无法自行创建实例；
         * 工具层要通过它拿到能力入口。
         */
        @Volatile
        var instance: MobileBridgeImpl? = null
            private set

        /** 工具层用的门面：服务没连上时返回 null，工具据此提示用户去开启。 */
        val capability: MobileCapability?
            get() = instance

        private const val GESTURE_TIMEOUT_MS = 5_000L
        private const val SCREENSHOT_TIMEOUT_MS = 5_000L
        private const val MAX_IMAGE_EDGE = 1280
        private const val JPEG_QUALITY = 80
    }
}
