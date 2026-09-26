package dev.pocket.agent.core.platform

import dev.pocket.agent.core.json.Json

/**
 * provider 密钥的存取。对外只见过期即无的明文字符串，密文形态由实现决定。
 */
interface CredentialStore {

    /** 保存密钥；传空串等价于 [remove]（不留下「已配置但为空」的脏状态）。 */
    fun put(providerId: String, apiKey: String)

    /** 未配置或密文不可用时返回 null。 */
    fun get(providerId: String): String?

    fun remove(providerId: String)

    /** 已配置密钥的 provider id 集合。 */
    fun ids(): Set<String>

    fun has(providerId: String): Boolean = !get(providerId).isNullOrEmpty()
}

/** 单测 / 首次启动的临时实现：不加密，仅内存。 */
class InMemoryCredentialStore : CredentialStore {

    private val map = LinkedHashMap<String, String>()

    override fun put(providerId: String, apiKey: String) {
        if (apiKey.isEmpty()) remove(providerId) else map[providerId] = apiKey
    }

    override fun get(providerId: String): String? = map[providerId]

    override fun remove(providerId: String) {
        map.remove(providerId)
    }

    override fun ids(): Set<String> = LinkedHashSet(map.keys)
}

/**
 * 加密落盘的密钥库。
 *
 * 存储格式（单条）：
 * `{"v":1,"data":"<Base64(iv||ciphertext||tag)>"}`
 * 带版本号是为了将来换算法时能识别旧数据并降级处理，而不是解密崩溃。
 *
 * **永不抛异常**：密钥解不开只应表现为「没配密钥」，让设置页提示用户重填，
 * 而不是让整个 Agent 启动失败。
 */
class KeyValueCredentialStore(
    private val kv: KeyValueStore,
    private val cipher: SecretCipher,
) : CredentialStore {

    override fun put(providerId: String, apiKey: String) {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        if (apiKey.isEmpty()) {
            remove(providerId)
            return
        }
        val envelope = Json.obj(
            "v" to Json.Num(VERSION.toDouble()),
            "data" to Json.Str(cipher.encrypt(apiKey)),
        )
        kv.putString(keyOf(providerId), envelope.encode())
    }

    override fun get(providerId: String): String? {
        val raw = kv.getString(keyOf(providerId)) ?: return null
        val root = Json.parseOrNull(raw) ?: return null
        val version = root.int("v") ?: return null
        if (version != VERSION) return null
        val data = root.str("data") ?: return null
        return try {
            cipher.decrypt(data)
        } catch (e: CipherException) {
            null
        }
    }

    override fun remove(providerId: String) = kv.remove(keyOf(providerId))

    override fun ids(): Set<String> =
        kv.keys()
            .filter { it.startsWith(PREFIX) && it.length > PREFIX.length }
            .map { it.removePrefix(PREFIX) }
            .filter { get(it) != null }
            .toSortedSet()

    private fun keyOf(providerId: String) = PREFIX + providerId

    companion object {
        const val PREFIX = "credential."
        const val VERSION = 1
    }
}
