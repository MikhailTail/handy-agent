package dev.mikhailtail.handyagent.core.platform

/**
 * 最小键值持久化抽象。
 *
 * 只暴露字符串读写，理由：Android 侧是 SharedPreferences、单测侧是内存 Map，
 * 两者都能满足；把「结构化数据」的编解码留给上层（JSON），
 * 于是所有解析/容错逻辑都能在宿主 JVM 上被真实测到。
 *
 * 契约：
 * - [getString] 对不存在的 key 返回 null（不抛异常）。
 * - [putString] 同步落盘（调用方可能在主线程之外，实现需自行保证线程安全）。
 */
interface KeyValueStore {

    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun remove(key: String)

    /** 当前全部 key（不含未落盘的写入），顺序不保证。 */
    fun keys(): Set<String>
}

/** 单测 / 无持久化场景用。线程安全。 */
class InMemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {

    private val map = LinkedHashMap<String, String>().apply { putAll(initial) }

    @Synchronized
    override fun getString(key: String): String? = map[key]

    @Synchronized
    override fun putString(key: String, value: String) {
        map[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        map.remove(key)
    }

    @Synchronized
    override fun keys(): Set<String> = LinkedHashSet(map.keys)

    /** 测试辅助：底层原文快照，用于断言「密钥没有以明文落盘」。 */
    @Synchronized
    fun snapshot(): Map<String, String> = LinkedHashMap(map)
}

/**
 * 给一组 key 加统一前缀，避免同一次会话里不同模块的 key 撞车。
 * [remove]/[keys] 只作用于自己的前缀，不会误删别人的数据。
 */
class PrefixedKeyValueStore(
    private val delegate: KeyValueStore,
    private val prefix: String,
) : KeyValueStore {

    init {
        require(prefix.isNotEmpty()) { "prefix must not be empty" }
    }

    override fun getString(key: String): String? = delegate.getString(prefix + key)

    override fun putString(key: String, value: String) = delegate.putString(prefix + key, value)

    override fun remove(key: String) = delegate.remove(prefix + key)

    override fun keys(): Set<String> =
        delegate.keys().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
}
