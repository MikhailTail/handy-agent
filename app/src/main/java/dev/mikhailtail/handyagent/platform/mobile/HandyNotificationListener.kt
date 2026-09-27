package dev.mikhailtail.handyagent.platform.mobile

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.mikhailtail.handyagent.core.json.Json

/**
 * 通知监听。
 *
 * 用户需要在「设置 → 通知 → 设备与应用通知」里手动授权（Android 13+ 侧载包同样受
 * 「受限设置」约束，见能力体检页）。
 *
 * 隐私处理：只保留**最近 50 条**，标题/正文各自截断。通知里经常带验证码、转账金额，
 * 所以它默认不会主动进上下文 —— 只有模型显式调用 `mobile_notifications` 才读得到，
 * 而且该工具走审批。
 */
class HandyNotificationListener : NotificationListenerService() {

    companion object {
        /** 内存环形缓冲：给"刚刚发生了什么"用。 */
        private const val MAX_MEMORY = 50

        /** 落盘条数：跨重启后仍可读。 */
        private const val MAX_PERSISTED = 200

        const val KEY = "mobile.notifications"

        private const val TITLE_MAX = 100
        private const val TEXT_MAX = 300

        /** 由 AppContainer 注入，避免服务直接依赖 Android 存储实现。 */
        @Volatile
        var sink: NotificationSink? = null
    }

    /** 持久化出口；由 AppContainer 在启动时接上 SharedPreferences。 */
    fun interface NotificationSink {
        fun onPosted(records: List<Json>)
    }

    private val buffer = ArrayDeque<Json>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val record = toRecord(n) ?: return

        synchronized(buffer) {
            buffer.addLast(record)
            while (buffer.size > MAX_MEMORY) buffer.removeFirst()
            val snapshot = buffer.toList().takeLast(MAX_PERSISTED)
            sink?.onPosted(snapshot)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    override fun onListenerConnected() {
        // 重连后把当前活跃通知补进缓冲（重连前发的那批不会再有回调）
        runCatching { activeNotifications }.getOrNull()?.forEach { onNotificationPosted(it) }
    }

    private fun toRecord(sbn: StatusBarNotification): Json? = runCatching {
        val extras = sbn.notification?.extras ?: return@runCatching null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        // 标题和正文都为空的通知（纯图标/进度条）对模型没有信息量
        if (title.isNullOrBlank() && text.isNullOrBlank()) return@runCatching null

        Json.obj(
            "key" to Json.of(sbn.key.orEmpty()),
            "pkg" to Json.of(sbn.packageName.orEmpty()),
            "title" to Json.of(title?.take(TITLE_MAX)),
            "text" to Json.of(text?.take(TEXT_MAX)),
            "at" to Json.of(sbn.postTime),
        )
    }.getOrNull()
}
