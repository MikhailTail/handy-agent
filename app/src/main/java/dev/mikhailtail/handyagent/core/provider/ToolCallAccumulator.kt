package dev.mikhailtail.handyagent.core.provider

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.Block

/**
 * 把分片的 tool_call 增量聚合成完整调用。
 *
 * Anthropic 的 `input_json_delta` 与 OpenAI 的 `function.arguments` 都是
 * 逐 token 到达的 JSON 片段，必须按 index 累积后整体解析。
 */
class ToolCallAccumulator {

    private class Partial {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
        var started = false
        var startEmitted = false
    }

    private val parts = LinkedHashMap<Int, Partial>()

    fun isEmpty(): Boolean = parts.values.none { it.name != null }

    fun clear() = parts.clear()

    /** 累积一个分片；返回「首次得知工具名」时需要通知 UI 的信息。 */
    fun accept(index: Int, id: String?, name: String?, argsDelta: String?): Started? {
        val p = parts.getOrPut(index) { Partial() }
        if (!id.isNullOrEmpty()) p.id = id
        var started: Started? = null
        if (!name.isNullOrEmpty() && p.name == null) {
            p.name = name
            started = Started(index, p.id ?: "call_$index", name)
            p.startEmitted = true
        } else if (p.name != null && !p.startEmitted) {
            started = Started(index, p.id ?: "call_$index", p.name!!)
            p.startEmitted = true
        }
        if (!argsDelta.isNullOrEmpty()) p.args.append(argsDelta)
        return started
    }

    data class Started(val index: Int, val id: String, val name: String)

    /** 是否所有已开始的调用都拿到了工具名。 */
    fun hasUnnamedCalls(): Boolean = parts.values.any { it.name == null }

    /**
     * 聚合结果，按 index 升序。参数 JSON 解析失败时退化为空对象并在 [issues] 记录原因，
     * 交由工具层报错（比静默丢弃更容易排查）。
     */
    fun build(): BuildResult {
        val out = ArrayList<Block.ToolUse>(parts.size)
        val issues = ArrayList<String>()
        for ((index, p) in parts.entries.sortedBy { it.key }) {
            val name = p.name ?: continue
            val raw = p.args.toString()
            val parsed: Json = if (raw.isBlank()) {
                Json.Obj(emptyMap())
            } else {
                val v = Json.parseOrNull(raw)
                if (v == null) {
                    issues.add("tool '$name' 参数不是合法 JSON: ${raw.take(200)}")
                    Json.Obj(emptyMap())
                } else {
                    v
                }
            }
            out.add(Block.ToolUse(p.id ?: "call_$index", name, parsed))
        }
        return BuildResult(out, issues)
    }

    data class BuildResult(val calls: List<Block.ToolUse>, val issues: List<String>)
}
