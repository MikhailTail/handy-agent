package dev.mikhailtail.handyagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 「所有文件访问权限」的检测与引导。
 *
 * 为什么必须单独处理：Android 11 起，`MANAGE_EXTERNAL_STORAGE` **无法用运行时弹窗申请**，
 * 用户必须自己去系统设置里开。没开时文件工具会失败，而错误只会是普通的一句
 * "Permission denied" —— 用户很难把它和"要去设置里开个开关"联系起来。
 */
object StorageAccess {

    /** 是否已获得全盘访问。Android 10 及以下没有这个概念，直接算已授权。 */
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    /**
     * 打开本应用的「所有文件访问权限」设置页。
     *
     * 先试带包名的直达页；部分 ROM 没有这个 Activity，就退到权限列表页，
     * 再不行退到应用详情页 —— 三级兜底，保证用户总能走到能开的地方。
     */
    fun openSettings(context: Context) {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.fromParts("package", context.packageName, null)),
                )
                add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null)),
            )
        }

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }
}
