package dev.pocket.agent.core.plugin

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.model.ToolSpec
import dev.pocket.agent.core.tool.Tool
import dev.pocket.agent.core.tool.ToolContext
import dev.pocket.agent.core.tool.ToolOutcome
import dev.pocket.agent.core.tool.ToolMerge
import dev.pocket.agent.core.tool.ToolRegistry
import dev.pocket.agent.core.tool.mergeTools
import dev.pocket.agent.core.tool.stringProp
import dev.pocket.agent.core.tool.toolSchema
import java.io.File

/** 插件清单非法。 */
class PluginFormatException(message: String) : Exception(message)

/**
 * 插件以「命令工具」的形式提供能力：把一次调用映射成沙箱里的一条命令。
 *
 * [command] 中的 `{args}` 会被调用的 `args` 字段替换，其余 `{key}` 用同名标量字段替换。
 */
data class PluginToolDecl(
    val name: String,
    val description: String,
    val command: String,
    val readOnly: Boolean = false,
    val timeoutMs: Long? = null,
)

/** 插件元数据；`plugin.json` 的直译。 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "0.0.0",
    val description: String = "",
    val tools: List<PluginToolDecl> = emptyList(),
    val source: String = "",
) {
    companion object {
        fun parse(json: Json, source: String = ""): PluginManifest {
            if (json !is Json.Obj) {
                throw PluginFormatException("plugin manifest must be a JSON object${at(source)}")
            }
            val id = json.str("id")?.trim().orEmpty()
            val name = (json.str("name") ?: id).trim()
            if (id.isEmpty() && name.isEmpty()) {
                throw PluginFormatException("plugin manifest needs 'id' or 'name'${at(source)}")
            }
            if (id.isNotEmpty() && !ID_RE.matches(id)) {
                throw PluginFormatException("invalid plugin id '$id' (use letters, digits, '-', '_', '.')${at(source)}")
            }
            val tools = ArrayList<PluginToolDecl>()
            for ((index, raw) in json.array("tools").withIndex()) {
                val toolName = raw.str("name")?.trim().orEmpty()
                val command = raw.str("command")?.trim().orEmpty()
                if (toolName.isEmpty()) {
                    throw PluginFormatException("plugin '$id' tool #$index has no 'name'${at(source)}")
                }
                if (!ID_RE.matches(toolName)) {
                    throw PluginFormatException("plugin '$id' has invalid tool name '$toolName'${at(source)}")
                }
                if (command.isEmpty()) {
                    throw PluginFormatException("plugin '$id' tool '$toolName' has no 'command'${at(source)}")
                }
                val timeout = (raw["timeoutMs"] as? Json.Num)?.value?.toLong()
                if (timeout != null && timeout <= 0) {
                    throw PluginFormatException("plugin '$id' tool '$toolName' has non-positive timeoutMs${at(source)}")
                }
                tools.add(
                    PluginToolDecl(
                        name = toolName,
                        description = raw.str("description")?.trim().orEmpty(),
                        command = command,
                        readOnly = raw.bool("readOnly") ?: false,
                        timeoutMs = timeout,
                    )
                )
            }
            val dupes = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
            if (dupes.isNotEmpty()) {
                throw PluginFormatException("plugin '$id' declares duplicate tool(s): ${dupes.joinToString(", ")}${at(source)}")
            }
            return PluginManifest(
                id = id.ifEmpty { name },
                name = name.ifEmpty { id },
                version = json.str("version")?.trim().orEmpty().ifEmpty { "0.0.0" },
                description = json.str("description")?.trim().orEmpty(),
                tools = tools,
                source = source,
            )
        }

        private fun at(source: String): String = if (source.isEmpty()) "" else " in $source"

        private val ID_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}

/** 一个插件：贡献工具，并可挂载钩子。 */
interface Plugin {
    val manifest: PluginManifest

    fun tools(): List<Tool> = emptyList()

    /** 注册钩子。宿主在安装时调用一次。 */
    fun install(bus: HookBus) = Unit
}

/**
 * 由清单驱动的插件：每个 [PluginToolDecl] 变成一个命令工具。
 *
 * 工具执行时使用 [ToolContext.shell]（工作目录 = workspace、环境变量白名单化、超时强杀），
 * 因此插件命令与内建 bash 工具受同一套沙箱约束。
 */
class CommandPlugin(override val manifest: PluginManifest) : Plugin {

    override fun tools(): List<Tool> = manifest.tools.map { CommandTool(manifest.id, it) }

    companion object {
        fun fromJson(json: Json, source: String = ""): CommandPlugin = CommandPlugin(PluginManifest.parse(json, source))
    }
}

/** 把一次工具调用翻译成沙箱命令。 */
class CommandTool(private val pluginId: String, private val decl: PluginToolDecl) : Tool {

    override val name: String = decl.name
    override val description: String =
        decl.description.ifBlank { "Plugin '$pluginId' command tool." }
    override val readOnly: Boolean = decl.readOnly

    override val inputSchema: Json = toolSchema(
        required = emptyList(),
        properties = mapOf("args" to stringProp("Arguments appended to the plugin command. Optional.")),
    )

    override fun spec(): ToolSpec = ToolSpec(name, description, inputSchema, readOnly)

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val args = input.str("args")?.trim().orEmpty()
        val command = render(decl.command, input, args)
        if (command.isBlank()) return ToolOutcome.error("plugin '$pluginId' tool '$name' produced an empty command")
        val result = ctx.shell.run(command, decl.timeoutMs)
        val rendered = result.render()
        return if (result.ok) ToolOutcome.ok(rendered) else ToolOutcome.error(rendered)
    }

    private fun render(template: String, input: Json, args: String): String {
        var out = template.replace(ARGS, args)
        for ((key, value) in (input as? Json.Obj)?.fields ?: emptyMap()) {
            if (key == "args") continue
            out = out.replace("{$key}", scalar(value) ?: "")
        }
        return out.trim()
    }

    private fun scalar(v: Json): String? = when (v) {
        is Json.Str -> v.value
        is Json.Num -> if (v.value % 1.0 == 0.0) v.value.toLong().toString() else v.value.toString()
        is Json.Bool -> v.value.toString()
        else -> null
    }

    private companion object {
        const val ARGS = "{args}"
    }
}

/** 插件发现结果：坏清单进 [errors]，其余照常可用。 */
data class PluginLoadResult(val manifests: List<PluginManifest>, val errors: List<String>)

/**
 * 插件宿主：安装 / 卸载插件，汇总它们贡献的工具与提示片段。
 *
 * [bus] 是共享的钩子总线；AgentLoop 直接使用它，因此插件挂的钩子立刻生效。
 */
class PluginHost(val bus: HookBus = HookBus()) {

    private val plugins = LinkedHashMap<String, Plugin>()

    val size: Int get() = plugins.size

    /** 安装插件。重复 id 返回 false（不覆盖已装插件，避免运行中工具被悄悄替换）。 */
    fun install(plugin: Plugin): Boolean {
        val id = plugin.manifest.id
        if (id.isEmpty() || plugins.containsKey(id)) return false
        plugins[id] = plugin
        plugin.install(bus)
        return true
    }

    fun installManifest(manifest: PluginManifest): Boolean = install(CommandPlugin(manifest))

    fun uninstall(id: String): Boolean = plugins.remove(id) != null

    fun manifests(): List<PluginManifest> = plugins.values.map { it.manifest }

    fun get(id: String): Plugin? = plugins[id]

    fun tools(): List<Tool> = plugins.values.flatMap { it.tools() }

    /** 与既有工具表合并，跳过重名 —— 否则 ToolRegistry 构造会失败，整个会话起不来。 */
    fun combineRegistry(base: ToolRegistry): ToolMerge = mergeTools(base, tools())

    // 从 `<root>/.pocket/plugins/<name>/plugin.json` 发现并安装。返回错误列表（空 = 全成功）。
    fun loadFrom(root: File): List<String> {
        val result = PluginLoader.discover(root)
        for (manifest in result.manifests) installManifest(manifest)
        return result.errors
    }

    fun promptSection(): String {
        if (plugins.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## Plugins\n")
        sb.append("The following plugins contribute extra tools (call them like any other tool):\n")
        for (plugin in plugins.values) {
            val m = plugin.manifest
            sb.append("- ").append(m.name).append(" (").append(m.id).append(" v").append(m.version).append(')')
            if (m.description.isNotBlank()) sb.append(" — ").append(m.description)
            val toolNames = plugin.tools().map { it.name }
            if (toolNames.isNotEmpty()) sb.append(" [tools: ").append(toolNames.joinToString(", ")).append(']')
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}

/** 插件目录发现：只认 `<root>/.pocket/plugins/<dir>/plugin.json`。 */
object PluginLoader {

    const val PLUGINS_DIR = ".pocket/plugins"
    const val MANIFEST_NAME = "plugin.json"

    fun discover(root: File): PluginLoadResult {
        val dir = File(root, PLUGINS_DIR)
        if (!dir.isDirectory) return PluginLoadResult(emptyList(), emptyList())
        val manifests = ArrayList<PluginManifest>()
        val errors = ArrayList<String>()

        for (entry in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (!entry.isDirectory) continue
            val manifestFile = File(entry, MANIFEST_NAME)
            val rel = "$PLUGINS_DIR/${entry.name}/$MANIFEST_NAME"
            if (!manifestFile.isFile) {
                errors.add("$rel: missing $MANIFEST_NAME")
                continue
            }
            val parsed = Json.parseOrNull(manifestFile.readText())
            if (parsed == null) {
                errors.add("$rel: invalid JSON")
                continue
            }
            try {
                manifests.add(PluginManifest.parse(parsed, rel))
            } catch (e: PluginFormatException) {
                errors.add(e.message ?: "$rel: invalid manifest")
            }
        }

        val dupes = manifests.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dupes.isNotEmpty()) {
            errors.add("duplicate plugin id(s) ignored: ${dupes.joinToString(", ")}")
            val seen = HashSet<String>()
            return PluginLoadResult(manifests.filter { seen.add(it.id) }, errors)
        }

        val toolOwners = HashMap<String, String>()
        for (m in manifests) {
            for (t in m.tools) {
                val owner = toolOwners.put(t.name, m.id)
                if (owner != null) errors.add("tool '${t.name}' declared by both '$owner' and '${m.id}' (kept '$owner')")
            }
        }
        return PluginLoadResult(manifests, errors)
    }
}
