package dev.mikhailtail.handyagent

import android.app.Application
import dev.mikhailtail.handyagent.platform.AppContainer

/**
 * 进程级组装根的持有者。
 *
 * 用 `by lazy` 而不是在 [onCreate] 里直接 new：Android 只给 onCreate 很短的预算，
 * 任何磁盘/Keystore 访问都应发生在首次真正需要使用 Agent 的时候（ViewModel 的 IO 协程里）。
 */
class HandyApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
    }
}
