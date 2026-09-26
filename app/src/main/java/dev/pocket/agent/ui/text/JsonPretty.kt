package dev.pocket.agent.ui.text

import dev.pocket.agent.core.json.Json

/**
 * 工具入参 / 输出的可读化。
 *
 * 单行折行 + 缩进的多行两种形态：时间轴上的工具卡片先用 [oneLine] 摘要，
 * 展开后再用 [pretty] 展示完整 JSON。
 */
object JsonPretty {

    private const val MAX_ONE_LINE = 120

    /** 压成一行；超长截断。用于工具卡片标题栏。 */
    fun oneLine(node: Json, max: Int = MAX_ONE_LINE): String {
        val compact = compact(node)
        return if (compact.length <= max) compact else compact.take(max - 1) + "…"
    }

    /** 无空白紧凑编码；字符串里的空白原样保留。 */
    fun compact(node: Json): String = node.encode()

    /** 2 空格缩进的多行编码。 */
    fun pretty(node: Json, indent: Int = 0, step: Int = 2): String {
        val pad = " ".repeat(indent)
        val inner = " ".repeat(indent + step)
        return when (node) {
            is Json.Null -> "null"
            is Json.Bool -> node.value.toString()
            is Json.Num -> formatNumber(node.value)
            is Json.Str -> quote(node.value)
            is Json.Arr -> {
                if (node.items.isEmpty()) "[]"
                else node.items.joinToString(",\n", "[\n", "\n$pad]") { inner + pretty(it, indent + step, step) }
            }
            is Json.Obj -> {
                if (node.fields.isEmpty()) "{}"
                else node.fields.entries.joinToString(",\n", "{\n", "\n$pad}") { (k, v) ->
                    inner + quote(k) + ": " + pretty(v, indent + step, step)
                }
            }
        }
    }

    /** 整数字面量不要显示成 `1.0`。 */
    fun formatNumber(value: Double): String =
        if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private fun quote(s: String): String = Json.Str(s).encode()
}
