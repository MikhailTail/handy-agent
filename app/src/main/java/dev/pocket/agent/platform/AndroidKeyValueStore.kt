package dev.pocket.agent.platform

import android.content.Context
import android.content.SharedPreferences
import dev.pocket.agent.core.platform.KeyValueStore

/**
 * SharedPreferences 支撑的 [KeyValueStore]。
 *
 * 用 `apply()` 而非 `commit()`：写入先进内存映射（同一进程内立刻可读），
 * 落盘异步进行 —— 设置页不必等磁盘，且不存在「写了一半」的可见状态。
 * SharedPreferences 自身线程安全，无需额外加锁。
 */
class AndroidKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** 只回报字符串条目：若同一文件里被写入了别的类型，忽略而非崩溃。 */
    override fun keys(): Set<String> =
        prefs.all.entries.filter { it.value is String }.map { it.key }.toSet()

    companion object {
        const val FILE_NAME = "pocket.agent"

        fun open(context: Context, name: String = FILE_NAME): AndroidKeyValueStore =
            AndroidKeyValueStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
    }
}
