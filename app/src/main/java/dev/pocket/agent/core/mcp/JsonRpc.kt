package dev.pocket.agent.core.mcp

import dev.pocket.agent.core.json.Json

/** JSON-RPC 2.0 标准错误码。 */
object JsonRpcErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

/** 协议层错误：入站错误响应、超时、传输中断都归一成它，避免上层区分五花八门的异常。 */
class McpProtocolException(message: String, val code: Int = 0) : Exception(message)

/** 对端返回的 error 对象。 */
data class JsonRpcFault(val code: Int, val message: String, val data: Json? = null) {
    fun describe(): String = "JSON-RPC error $code: $message"
}

/**
 * 一条入站消息。
 *
 * 区分 [ServerRequest] 与 [Notification] 很重要：前者带 id，**必须**应答，
 * 否则服务端会一直等我们而挂死。
 */
sealed interface IncomingMessage {
    /** 对我们请求的应答。id 已归一为 Long（字符串数字 id 也接受）。 */
    data class Response(val id: Long, val result: Json?, val error: JsonRpcFault?) : IncomingMessage

    /** 服务端反向发起的请求（如 sampling/createMessage），需要应答。 */
    data class ServerRequest(val id: Json, val method: String, val params: Json?) : IncomingMessage

    /** 通知：无 id，无需应答。 */
    data class Notification(val method: String, val params: Json?) : IncomingMessage

    /** 解析失败或既无 method 也无 result/error —— 丢弃但留下原因便于排查。 */
    data class Malformed(val reason: String) : IncomingMessage
}

/** JSON-RPC 2.0 编解码。 */
object JsonRpc {

    const val VERSION = "2.0"

    fun request(id: Long, method: String, params: Json? = null): Json {
        val fields = ArrayList<Pair<String, Json>>(4)
        fields.add("jsonrpc" to Json.Str(VERSION))
        fields.add("id" to Json.Num(id.toDouble()))
        fields.add("method" to Json.Str(method))
        if (params != null) fields.add("params" to params)
        return Json.obj(*fields.toTypedArray())
    }

    fun notification(method: String, params: Json? = null): Json {
        val fields = ArrayList<Pair<String, Json>>(3)
        fields.add("jsonrpc" to Json.Str(VERSION))
        fields.add("method" to Json.Str(method))
        if (params != null) fields.add("params" to params)
        return Json.obj(*fields.toTypedArray())
    }

    /** 应答服务端请求（本项目只回「不支持该方法」）。id 原样回显，字符串 id 也保持字符串。 */
    fun errorResponse(id: Json, code: Int, message: String): Json = Json.obj(
        "jsonrpc" to Json.Str(VERSION),
        "id" to id,
        "error" to Json.obj(
            "code" to Json.Num(code.toDouble()),
            "message" to Json.Str(message),
        ),
    )

    fun parseIncoming(line: String): IncomingMessage {
        val json = Json.parseOrNull(line) ?: return IncomingMessage.Malformed("not valid JSON")
        if (json !is Json.Obj) return IncomingMessage.Malformed("top-level value is not an object")

        val id = json["id"]
        val hasId = id != null && id !is Json.Null
        val method = json.str("method")
        val result = json["result"]
        val errorNode = json["error"]

        if (method != null) {
            // 带 id 的 method = 服务端反向请求；不带 = 通知。
            val params = json.obj("params")
            return if (hasId) {
                IncomingMessage.ServerRequest(id!!, method, params)
            } else {
                IncomingMessage.Notification(method, params)
            }
        }

        if (!hasId) return IncomingMessage.Malformed("missing both 'method' and 'id'")
        val numericId = asLong(id!!) ?: return IncomingMessage.Malformed("non-numeric id: ${id.encode()}")
        if (result == null && errorNode == null) {
            return IncomingMessage.Malformed("response has neither 'result' nor 'error'")
        }
        val fault = if (errorNode is Json.Obj) {
            JsonRpcFault(
                code = errorNode.int("code") ?: 0,
                message = errorNode.str("message") ?: "unknown error",
                data = errorNode["data"],
            )
        } else {
            null
        }
        return IncomingMessage.Response(numericId, result, fault)
    }

    private fun asLong(id: Json): Long? = when (id) {
        is Json.Num -> id.value.toLong()
        is Json.Str -> id.value.toLongOrNull()
        else -> null
    }
}
