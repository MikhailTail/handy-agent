package dev.mikhailtail.handyagent.core.platform

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.ReasoningEffort
import dev.mikhailtail.handyagent.core.permission.PermissionMode

/**
 * 全局用户设置（非敏感，明文 JSON 存 key-value）。
 *
 * 字段全部带默认值：任何一项缺失/损坏都退化成默认行为，绝不因为设置读不出来而拒绝启动。
 * 唯一的例外是 [providerId]：空串表示「还没选」，由上层决定回退到第一个可用 provider。
 */
data class AppSettings(
    val providerId: String = "",
    /** 空串表示用 provider 的 defaultModel。 */
    val model: String = "",
    val effort: ReasoningEffort = ReasoningEffort.DEFAULT,
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    /** 用户显式改过的工具策略（未列出的工具由 PermissionBroker 的默认规则决定）。 */
    val permissionModes: Map<String, PermissionMode> = emptyMap(),
    val mcpEnabled: Boolean = true,
    /** 上下文压缩阈值（占输入预算比例）。 */
    val compactThreshold: Double = 0.85,
) {

    fun resolvedModel(fallback: String): String = model.ifBlank { fallback }

    fun toJson(): Json = Json.obj(
        "providerId" to Json.Str(providerId),
        "model" to Json.Str(model),
        "effort" to Json.Str(effort.label),
        "maxIterations" to Json.Num(maxIterations.toDouble()),
        "mcpEnabled" to Json.Bool(mcpEnabled),
        "compactThreshold" to Json.Num(compactThreshold),
        "permissionModes" to Json.Obj(
            permissionModes.entries.associate { (tool, mode) -> tool to Json.Str(mode.name) }
        ),
    )

    companion object {
        const val DEFAULT_MAX_ITERATIONS = 24
        private const val MIN_ITERATIONS = 1
        private const val MAX_ITERATIONS = 200

        fun fromJson(node: Json): AppSettings {
            val modes = LinkedHashMap<String, PermissionMode>()
            node.fieldsOf("permissionModes").forEach { (tool, value) ->
                val raw = value.asStringOrNull() ?: return@forEach
                PermissionMode.entries.firstOrNull { it.name == raw }?.let { modes[tool] = it }
            }
            return AppSettings(
                providerId = node.str("providerId") ?: "",
                model = node.str("model") ?: "",
                effort = ReasoningEffort.fromLabel(node.str("effort")),
                maxIterations = (node.int("maxIterations") ?: DEFAULT_MAX_ITERATIONS)
                    .coerceIn(MIN_ITERATIONS, MAX_ITERATIONS),
                permissionModes = modes,
                mcpEnabled = node.bool("mcpEnabled") ?: true,
                compactThreshold = (node.double("compactThreshold") ?: 0.85).coerceIn(0.1, 1.0),
            )
        }
    }
}

/** 设置的读写。读取永不抛异常：缺失/损坏 → 默认值。 */
class SettingsStore(private val kv: KeyValueStore) {

    fun load(): AppSettings {
        val raw = kv.getString(KEY) ?: return AppSettings()
        val node = Json.parseOrNull(raw) ?: return AppSettings()
        return try {
            AppSettings.fromJson(node)
        } catch (e: Exception) {
            // 例如字段类型完全意外；退化为默认设置而不是让启动失败。
            AppSettings()
        }
    }

    fun save(settings: AppSettings) {
        kv.putString(KEY, settings.toJson().encode())
    }

    /** 原子地「读-改-写」，设置页改单个开关时不必回传整份对象。 */
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(load())
        save(next)
        return next
    }

    companion object {
        const val KEY = "settings"
    }
}
