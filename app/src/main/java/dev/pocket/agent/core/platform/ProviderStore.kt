package dev.pocket.agent.core.platform

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.provider.ProviderConfig
import dev.pocket.agent.core.provider.ProviderKind

/** 加载 provider 列表的结果：坏掉的条目只记录错误，不让整份配置作废。 */
data class ProviderLoadResult(
    val configs: List<ProviderConfig>,
    val errors: List<String> = emptyList(),
)

/**
 * provider 配置持久化。
 *
 * 关键安全约束：**apiKey 从不写入这里**（它是明文敏感值，走 [CredentialStore] 加密存储）。
 * [save] 会显式丢弃 config.apiKey，[load] 返回的 config.apiKey 恒为空串，
 * 需要带密钥的实例时用 [withSecrets] 现取现用 —— 密钥因此只活在内存里。
 */
class ProviderStore(private val kv: KeyValueStore) {

    fun save(configs: List<ProviderConfig>) {
        val arr = configs.map { encode(it) }
        kv.putString(KEY, Json.Arr(arr).encode())
    }

    /** 已保存的配置（apiKey 一律为空）。无数据或 JSON 损坏时返回空列表 + 错误说明。 */
    fun load(): ProviderLoadResult {
        val raw = kv.getString(KEY) ?: return ProviderLoadResult(emptyList())
        val root = Json.parseOrNull(raw)
            ?: return ProviderLoadResult(emptyList(), listOf("provider store is corrupted (invalid JSON)"))
        if (root !is Json.Arr) {
            return ProviderLoadResult(emptyList(), listOf("provider store must be a JSON array"))
        }
        val configs = ArrayList<ProviderConfig>(root.items.size)
        val errors = ArrayList<String>()
        for ((i, node) in root.items.withIndex()) {
            val decoded = decode(node)
            when {
                decoded == null -> errors.add("entry #$i is not a valid provider object")
                decoded.id.isBlank() -> errors.add("entry #$i has an empty id")
                else -> configs.add(decoded)
            }
        }
        return ProviderLoadResult(dedupe(configs), errors)
    }

    /** 把密钥注入配置（只在构建 provider 的瞬间调用，不要缓存结果）。 */
    fun withSecrets(configs: List<ProviderConfig>, credentials: CredentialStore): List<ProviderConfig> =
        configs.map { cfg -> cfg.copy(apiKey = credentials.get(cfg.id) ?: "") }

    /**
     * 与预置列表合并：预置提供默认 baseUrl / 模型清单，用户保存过的同 id 条目覆盖之；
     * 用户自定义（id 不在预置里）的条目追加在后面。
     * 预置项在结果里始终存在，用户清空配置也不会「provider 列表突然变空」。
     */
    fun mergeWithPresets(
        presets: List<ProviderConfig>,
        saved: List<ProviderConfig>,
    ): List<ProviderConfig> {
        val savedById = saved.associateBy { it.id }
        val merged = ArrayList<ProviderConfig>(presets.size + saved.size)
        for (preset in presets) {
            // 用户没保存过这一项 → 直接用预置原样。**绝不能 continue**：
            // 首次启动时 store 是空的，跳过就等于「provider 列表为空」。
            val override = savedById[preset.id]
            if (override == null) {
                merged.add(preset)
                continue
            }
            merged.add(
                preset.copy(
                    name = override.name.ifBlank { preset.name },
                    baseUrl = override.baseUrl.ifBlank { preset.baseUrl },
                    defaultModel = override.defaultModel.ifBlank { preset.defaultModel },
                    models = if (override.models.isEmpty()) preset.models else override.models,
                    extraHeaders = override.extraHeaders,
                )
            )
        }
        val presetIds = presets.map { it.id }.toSet()
        merged.addAll(saved.filter { it.id !in presetIds })
        return merged
    }

    private fun encode(cfg: ProviderConfig): Json = Json.obj(
        "id" to Json.Str(cfg.id),
        "name" to Json.Str(cfg.name),
        "kind" to Json.Str(cfg.kind.name),
        "baseUrl" to Json.Str(cfg.baseUrl),
        "defaultModel" to Json.Str(cfg.defaultModel),
        "models" to Json.arrOfStrings(cfg.models),
        "extraHeaders" to Json.Obj(cfg.extraHeaders.mapValues { Json.Str(it.value) }),
    )

    private fun decode(node: Json): ProviderConfig? {
        if (node !is Json.Obj) return null
        val id = node.str("id") ?: return null
        val kind = node.str("kind")?.let { raw -> ProviderKind.entries.firstOrNull { it.name == raw } }
            ?: return null
        return ProviderConfig(
            id = id,
            name = node.str("name") ?: id,
            kind = kind,
            baseUrl = node.str("baseUrl") ?: "",
            apiKey = "",
            defaultModel = node.str("defaultModel") ?: "",
            extraHeaders = node.fieldsOf("extraHeaders")
                .mapNotNull { (k, v) -> v.asStringOrNull()?.let { k to it } }
                .toMap(),
            models = node.array("models").mapNotNull { it.asStringOrNull() },
        )
    }

    /** 同 id 只保留第一条：配置文件被手工改坏时，宁可丢掉重复项也不抛异常。 */
    private fun dedupe(configs: List<ProviderConfig>): List<ProviderConfig> {
        val seen = HashSet<String>()
        return configs.filter { it.id.isNotEmpty() && seen.add(it.id) }
    }

    companion object {
        const val KEY = "providers"
    }
}
