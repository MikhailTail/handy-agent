package dev.pocket.agent.core.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 可脚本化的假传输层：记录请求，回放预设的 SSE 行。 */
class FakeHttpEngine(
    var lines: List<String> = emptyList(),
    var failWith: Exception? = null,
    var executeResponse: String = "",
) : HttpEngine {

    val requests = ArrayList<HttpRequest>()
    val lastRequest: HttpRequest get() = requests.last()

    override fun streamLines(req: HttpRequest): Flow<String> = flow {
        requests.add(req)
        failWith?.let { throw it }
        for (l in lines) emit(l)
    }

    override suspend fun execute(req: HttpRequest): String {
        requests.add(req)
        failWith?.let { throw it }
        return executeResponse
    }

    /** 把 SSE 事件列表展开成逐行序列（带空行分隔），模拟真实服务端。 */
    companion object {
        fun sse(vararg events: Pair<String?, String>): List<String> {
            val out = ArrayList<String>()
            for ((name, data) in events) {
                if (name != null) out.add("event: $name")
                for (l in data.split('\n')) out.add("data: $l")
                out.add("")
            }
            return out
        }

        fun sseData(vararg datas: String): List<String> {
            val out = ArrayList<String>()
            for (d in datas) {
                for (l in d.split('\n')) out.add("data: $l")
                out.add("")
            }
            return out
        }
    }
}
