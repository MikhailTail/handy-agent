package dev.mikhailtail.handyagent.core.skill

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.ToolSpec
import dev.mikhailtail.handyagent.core.tool.Tool
import dev.mikhailtail.handyagent.core.tool.ToolContext
import dev.mikhailtail.handyagent.core.tool.ToolOutcome
import dev.mikhailtail.handyagent.core.tool.stringProp
import dev.mikhailtail.handyagent.core.tool.toolSchema
import java.io.File

/** 技能定义非法（缺少 frontmatter、name 为空等）。发现阶段会被收集成错误，绝不崩溃。 */
class SkillFormatException(message: String) : Exception(message)

/**
 * 一个技能：一段「把工作怎么做讲清楚」的指令，加上可选的工具白名单。
 *
 * 技能不是代码而是**上下文**：模型通过 `skill` 工具把它加载进对话，
 * 之后按其中的步骤行事。这点与 MCP / 插件（提供可执行能力）刻意区分开。
 */
data class Skill(
    val name: String,
    val description: String,
    val instructions: String,
    /** 非空表示加载后只允许这些工具（`skill` 工具本身始终保留，以便切换/退出）。 */
    val allowedTools: List<String> = emptyList(),
    /** 来源文件路径（相对沙箱根），用于 UI 展示与排错。 */
    val source: String = "",
) {
    /** 归一化后的工具白名单，恒包含 `skill`。 */
    fun gatedTools(): Set<String> =
        if (allowedTools.isEmpty()) emptySet() else (allowedTools + SkillTool.NAME).toSet()
}

/** 技能发现结果：坏技能进 [errors]，不影响其余技能可用。 */
data class SkillLoadResult(val skills: List<Skill>, val errors: List<String>)

/**
 * 技能注册表。持有内存态技能集合，并记录「当前激活的技能」。
 *
 * 激活态是有意的全局单点：同一时刻只应有一个技能在生效，
 * 否则两个互相冲突的操作手册会同时进上下文。
 */
class SkillRegistry(skills: List<Skill> = emptyList()) {

    private val byName: Map<String, Skill>

    @Volatile
    private var activeSkill: Skill? = null

    init {
        val duplicates = skills.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate skill names: $duplicates" }
        byName = LinkedHashMap<String, Skill>().apply { skills.forEach { put(it.name, it) } }
    }

    val size: Int get() = byName.size

    fun names(): List<String> = byName.keys.toList()

    fun all(): List<Skill> = byName.values.toList()

    fun get(name: String): Skill? = byName[name]

    fun active(): Skill? = activeSkill

    /** 激活技能；未知技能返回 false（调用方负责回填错误）。 */
    fun activate(name: String): Boolean {
        val skill = byName[name] ?: return false
        activeSkill = skill
        return true
    }

    fun deactivate() {
        activeSkill = null
    }

    /** 当前生效的工具白名单；空集表示不限制。 */
    fun activeAllowedTools(): Set<String> = activeSkill?.gatedTools() ?: emptySet()

    /** 追加到系统提示的清单；无技能时返回空串（调用方据此跳过拼接）。 */
    fun promptSection(): String {
        if (byName.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## Skills\n")
        sb.append("Specialised procedures are available. Before doing work a skill covers, call the `")
        sb.append(SkillTool.NAME)
        sb.append("` tool with the skill name to load its instructions, then follow them.\n")
        for (skill in byName.values) {
            sb.append("- ").append(skill.name)
            if (skill.description.isNotBlank()) sb.append(" — ").append(skill.description)
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun register(skill: Skill): SkillRegistry = SkillRegistry(byName.values.toList() + skill)
}

// ---------------------------------------------------------------------------
// 解析
// ---------------------------------------------------------------------------

/**
 * SKILL.md 解析器：`---` 包裹的 frontmatter + 正文。
 *
 * 只支持 `key: value` 单行标量（够用且无 YAML 依赖）；未知键忽略，
 * 因为技能文件常常出自不同工具链、带一堆无关元数据。
 */
object SkillParser {

    private const val FENCE = "---"

    fun parse(text: String, fallbackName: String = "", source: String = ""): Skill {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size && lines[i].isBlank()) i++
        if (i >= lines.size || lines[i].trim() != FENCE) {
            throw SkillFormatException("missing '---' frontmatter opener${at(source)}")
        }
        i++
        val meta = LinkedHashMap<String, String>()
        var closed = false
        while (i < lines.size) {
            val line = lines[i]
            if (line.trim() == FENCE) {
                closed = true
                i++
                break
            }
            parseMetaLine(line)?.let { (k, v) -> meta[k] = v }
            i++
        }
        if (!closed) throw SkillFormatException("frontmatter is never closed with '---'${at(source)}")

        val body = lines.subList(i, lines.size).joinToString("\n").trim()
        val name = (meta["name"] ?: fallbackName).trim()
        if (name.isEmpty()) throw SkillFormatException("skill has no 'name'${at(source)}")
        if (!NAME_RE.matches(name)) {
            throw SkillFormatException("invalid skill name '$name' (use letters, digits, '-', '_')${at(source)}")
        }
        val description = (meta["description"] ?: "").trim()
        if (description.isEmpty()) throw SkillFormatException("skill '$name' has no 'description'${at(source)}")
        if (body.isEmpty()) throw SkillFormatException("skill '$name' has an empty body${at(source)}")

        val allowed = splitList(meta["allowed-tools"] ?: meta["allowedTools"] ?: meta["tools"])
        return Skill(
            name = name,
            description = description,
            instructions = body,
            allowedTools = allowed,
            source = source,
        )
    }

    private fun at(source: String): String = if (source.isEmpty()) "" else " in $source"

    private fun parseMetaLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val idx = trimmed.indexOf(':')
        if (idx <= 0) return null
        val key = trimmed.substring(0, idx).trim()
        if (key.isEmpty()) return null
        return key to unquote(trimmed.substring(idx + 1).trim())
    }

    private fun unquote(v: String): String {
        if (v.length >= 2) {
            val first = v.first()
            val last = v.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length - 1)
            }
        }
        return v
    }

    private fun splitList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val cleaned = raw.removePrefix("[").removeSuffix("]")
        return cleaned.split(',', '\n')
            .map { unquote(it.trim()) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private val NAME_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
}

/**
 * 从沙箱内发现技能：
 * - `<root>/.pocket/skills/<dir>/SKILL.md`
 * - `<root>/.pocket/skills/<name>.md`
 *
 * 目录不存在是正常情况（用户没放技能），返回空结果而不是错误。
 */
object SkillLoader {

    const val SKILLS_DIR = ".pocket/skills"

    fun discover(root: File): SkillLoadResult {
        val dir = File(root, SKILLS_DIR)
        if (!dir.isDirectory) return SkillLoadResult(emptyList(), emptyList())
        val skills = ArrayList<Skill>()
        val errors = ArrayList<String>()

        val entries = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        for (entry in entries) {
            val file = when {
                entry.isDirectory -> File(entry, "SKILL.md")
                entry.isFile && entry.name.endsWith(".md", ignoreCase = true) -> entry
                else -> null
            } ?: continue
            if (!file.isFile) {
                if (entry.isDirectory) errors.add("skill '${entry.name}': missing SKILL.md")
                continue
            }
            val rel = "$SKILLS_DIR/${if (entry.isDirectory) entry.name + "/SKILL.md" else entry.name}"
            try {
                skills.add(SkillParser.parse(file.readText(), fallbackName = entry.nameWithoutExtension, source = rel))
            } catch (e: SkillFormatException) {
                errors.add("${e.message}")
            } catch (e: Exception) {
                errors.add("skill '$rel': ${e::class.simpleName}: ${e.message}")
            }
        }

        val duplicates = skills.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            errors.add("duplicate skill name(s) ignored: ${duplicates.joinToString(", ")}")
            val seen = HashSet<String>()
            return SkillLoadResult(skills.filter { seen.add(it.name) }, errors)
        }
        return SkillLoadResult(skills, errors)
    }
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/**
 * `skill` 工具：把技能指令加载进对话。
 *
 * 只读工具 → 免审批。它不产生副作用，只把文本喂给模型；
 * 技能里描述的真实副作用（跑命令、改文件）仍会走各自的审批路径。
 */
class SkillTool(private val registry: SkillRegistry) : Tool {

    override val name: String = NAME
    override val description: String =
        "Load a specialised skill's instructions into the conversation. " +
            "Call with the skill name; the returned instructions tell you how to perform that kind of task."

    override val inputSchema: Json = toolSchema(
        required = listOf("name"),
        properties = mapOf("name" to stringProp("Name of the skill to load, exactly as listed in the system prompt.")),
    )

    override val readOnly: Boolean = true

    override fun spec(): ToolSpec = ToolSpec(name, description, inputSchema, readOnly = true)

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val requested = input.str("name")?.trim()
        if (requested.isNullOrEmpty()) {
            return ToolOutcome.error("missing required field 'name'; available skills: ${available()}")
        }
        val skill = registry.get(requested)
            ?: return ToolOutcome.error("unknown skill '$requested'; available skills: ${available()}")

        registry.activate(skill.name)
        val sb = StringBuilder()
        sb.append("Skill '").append(skill.name).append("' loaded. Follow these instructions.\n\n")
        sb.append(skill.instructions)
        if (skill.allowedTools.isNotEmpty()) {
            sb.append("\n\n---\nTool access is now restricted to: ")
            sb.append(skill.gatedTools().joinToString(", "))
            sb.append(". Call `").append(NAME).append("` again with another skill to switch, or finish the task.")
        }
        return ToolOutcome.ok(sb.toString())
    }

    private fun available(): String =
        if (registry.size == 0) "(none installed)" else registry.names().joinToString(", ")

    companion object {
        const val NAME = "skill"
    }
}
