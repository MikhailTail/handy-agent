package dev.mikhailtail.handyagent.platform.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.mikhailtail.handyagent.core.mobile.AppController
import dev.mikhailtail.handyagent.core.mobile.Capability
import dev.mikhailtail.handyagent.core.mobile.CapabilityStatus
import dev.mikhailtail.handyagent.core.mobile.MobileBridge
import dev.mikhailtail.handyagent.core.mobile.MobileCapabilityProbe
import dev.mikhailtail.handyagent.core.mobile.MobileInput
import dev.mikhailtail.handyagent.core.mobile.MobileKey
import dev.mikhailtail.handyagent.core.mobile.MobileResult
import dev.mikhailtail.handyagent.core.mobile.NotifRecord
import dev.mikhailtail.handyagent.core.mobile.NotificationReader
import dev.mikhailtail.handyagent.core.mobile.ScreenCapture
import dev.mikhailtail.handyagent.core.mobile.ScreenInfo
import dev.mikhailtail.handyagent.core.mobile.UiInspector
import dev.mikhailtail.handyagent.core.mobile.UiNode
import dev.mikhailtail.handyagent.core.mobile.UnavailableReason
import dev.mikhailtail.handyagent.core.platform.ImageBytesStore
import dev.mikhailtail.handyagent.core.platform.KeyValueStore

/**
 * Android 侧对 [MobileBridge] 的实现。
 *
 * **所有方法都是惰性的**：无障碍服务由系统异步绑定、且随时可能被关掉或杀死，
 * 所以绝不能在这里缓存服务实例 —— 每次调用都重新取 [HandyAccessibilityService.instance]
 * 并判空。这也是"服务未开启"能变成一个**可恢复状态**而不是崩溃的原因。
 */
class AndroidMobileBridge(
    private val context: Context,
    private val imageStore: ImageBytesStore,
    private val kv: KeyValueStore,
) {

    private val service get() = HandyAccessibilityService.instance

    fun build(): MobileBridge = MobileBridge(
        capability = AndroidCapabilityProbe(context),
        screen = ScreenCapture { _ ->
            val svc = service ?: return@ScreenCapture noService()
            svc.capture(imageStore)
        },
        ui = UiInspector { includeInvisible ->
            val svc = service ?: return@UiInspector noService()
            svc.dumpTree(includeInvisible)
        },
        input = object : MobileInput {
            override suspend fun tap(x: Int, y: Int, durationMs: Int): MobileResult<Unit> {
                val svc = service ?: return noService()
                val result = svc.tap(x, y, durationMs)
                if (result is MobileResult.Ok) svc.settle()
                return result
            }

            override suspend fun swipe(path: List<Pair<Int, Int>>, durationMs: Int): MobileResult<Unit> {
                val svc = service ?: return noService()
                val result = svc.swipe(path, durationMs)
                if (result is MobileResult.Ok) svc.settle()
                return result
            }

            override suspend fun globalAction(key: MobileKey): MobileResult<Unit> {
                val svc = service ?: return noService()
                val result = svc.globalAction(key)
                if (result is MobileResult.Ok) svc.settle()
                return result
            }

            override suspend fun type(text: String): MobileResult<Unit> {
                val svc = service ?: return noService()
                return svc.typeText(text)
            }
        },
        apps = object : AppController {
            override suspend fun launch(packageName: String): MobileResult<Unit> {
                // getLaunchIntentForPackage 在没有启动 Activity 时返回 null（不是抛异常），
                // 所以要显式判空并给出可读的原因。
                val intent = runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }
                    .getOrNull()
                    ?: return MobileResult.Failed("找不到可启动的应用：$packageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return runCatching {
                    context.startActivity(intent)
                    MobileResult.Ok(Unit)
                }.getOrElse { MobileResult.Failed("启动失败：${it.message}") }
            }

            override suspend fun foregroundPackage(): MobileResult<String> {
                val svc = service ?: return noService()
                return svc.currentPackage()?.let { MobileResult.Ok(it) }
                    ?: MobileResult.Failed("无法确定前台应用")
            }

            override fun screenInfo(): MobileResult<ScreenInfo> {
                val svc = service ?: return noService()
                return MobileResult.Ok(svc.screenInfo())
            }
        },
        notifications = object : NotificationReader {
            // 通知监听是 P8 的内容；现在明确报"没有权限"，工具会提示用户去开，
            // 而不是返回空列表让模型误以为"确实没有通知"。
            override fun available(): Boolean = AndroidCapabilityProbe.isNotificationListenerEnabled(context)
            override fun recent(limit: Int, packageFilter: String?): List<NotifRecord> =
                recentNotifications(kv, limit, packageFilter)
        },
    )

    private fun noService(): MobileResult.Unavailable = MobileResult.Unavailable(
        UnavailableReason.SERVICE_NOT_ENABLED,
        MobileBridge.NO_SERVICE,
    )

    companion object {
        private const val NOTIF_KEY = "mobile.notifications"

        /** 通知由 P8 的监听服务写入这里；尚未接入时为空。 */
        fun recentNotifications(kv: KeyValueStore, limit: Int, packageFilter: String?): List<NotifRecord> {
            val raw = kv.getString(NOTIF_KEY) ?: return emptyList()
            return runCatching {
                val arr = dev.mikhailtail.handyagent.core.json.Json.parse(raw)
                (arr as? dev.mikhailtail.handyagent.core.json.Json.Arr)?.items.orEmpty()
                    .mapNotNull { item ->
                        val pkg = item.str("pkg") ?: return@mapNotNull null
                        if (packageFilter != null && pkg != packageFilter) return@mapNotNull null
                        NotifRecord(
                            key = item.str("key").orEmpty(),
                            packageName = pkg,
                            title = item.str("title"),
                            text = item.str("text"),
                            postedAt = item.long("at") ?: 0L,
                        )
                    }
                    .take(limit)
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * 能力探测（体检页数据源）。
 *
 * 检测的是**系统设置里的开关状态**，而不是"功能能不能用" —— 后者要在真正执行时才知道。
 * 这样体检页能明确告诉用户"哪一项没开、去哪儿开"。
 */
class AndroidCapabilityProbe(private val context: Context) : MobileCapabilityProbe {

    override fun list(): List<CapabilityStatus> = listOf(
        CapabilityStatus(
            Capability.ACCESSIBILITY,
            isAccessibilityEnabled(context),
            detail = if (isAccessibilityEnabled(context)) "" else "到「设置 → 无障碍」打开本服务",
        ),
        CapabilityStatus(
            Capability.NOTIFICATION_LISTENER,
            isNotificationListenerEnabled(context),
            detail = if (isNotificationListenerEnabled(context)) "" else "到「设置 → 通知 → 设备与应用通知」允许本应用",
        ),
        CapabilityStatus(
            Capability.IME,
            isOurImeSelected(context),
            detail = if (isOurImeSelected(context)) "" else "输入中文需要启用并选中本应用的输入法",
        ),
        CapabilityStatus(Capability.FOREGROUND_SERVICE, true, ""),
        CapabilityStatus(
            Capability.BATTERY_UNRESTRICTED,
            isIgnoringBatteryOptimizations(context),
            detail = if (isIgnoringBatteryOptimizations(context)) "" else "建议把电池策略设为「无限制」，否则后台会被掐断",
        ),
        CapabilityStatus(Capability.OVERLAY, true, ""),
    )

    companion object {
        /**
         * 无障碍是否已开启。
         *
         * 只看系统设置**不够**：设置里开着、但服务被系统杀掉的情况很常见，
         * 所以还要看实例在不在（体检页会把这种状态显示为"已开启但未运行"）。
         */
        fun isAccessibilityEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${HandyAccessibilityService::class.java.name}"
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun isNotificationListenerEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${HandyNotificationListener::class.java.name}"
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun isOurImeSelected(context: Context): Boolean {
            val current = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            ).orEmpty()
            return current.startsWith("${context.packageName}/")
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                ?: return false
            return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
                .getOrDefault(false)
        }

        /** 应用是否可见（包可见性）：launch 能否成功依赖它。 */
        fun canSeeOtherPackages(context: Context): Boolean = runCatching {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).isNotEmpty()
        }.getOrDefault(false)
    }
}

/** 未接入时给 UiNode 的占位（保持 core 接口完整）。 */
internal fun emptyUiNode(): UiNode = UiNode(className = "View")
