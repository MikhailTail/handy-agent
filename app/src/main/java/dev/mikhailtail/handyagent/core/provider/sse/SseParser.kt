package dev.mikhailtail.handyagent.core.provider.sse

/** 一条完整的 SSE 事件。 */
data class SseEvent(val event: String?, val data: String)

/**
 * 增量 SSE 解析器（W3C EventSource 子集）。
 *
 * 逐行喂入：`event:` / `data:` / 注释 `:` / 空行派发。
 * 多行 data 用 '\n' 连接；同一事件可跨多次 feedLine 累积。
 */
class SseParser {

    private val data = StringBuilder()
    private var event: String? = null
    private var pending = false

    /** 喂入一行（不含换行符）。返回派发的事件，或 null 表示还需更多行。 */
    fun feedLine(line: String): SseEvent? {
        if (line.isEmpty()) return dispatch()

        // 注释行（含 SSE 心跳），忽略。
        if (line[0] == ':') return null

        val colon = line.indexOf(':')
        val field: String
        var value: String
        if (colon < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colon)
            value = line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
        }

        when (field) {
            "event" -> { event = value; pending = true }
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
                pending = true
            }
            // id / retry 对本项目无用，但仍算作「有内容的事件」
            "id", "retry" -> pending = true
            else -> Unit
        }
        return null
    }

    /** 流结束时调用，派发残留事件（多数服务端会补空行，但不能依赖）。 */
    fun flush(): SseEvent? = dispatch()

    fun reset() {
        data.setLength(0)
        event = null
        pending = false
    }

    private fun dispatch(): SseEvent? {
        if (!pending) {
            reset()
            return null
        }
        val payload = data.toString()
        val name = event
        reset()
        return SseEvent(name, payload)
    }
}
