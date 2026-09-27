package dev.mikhailtail.handyagent.server

import dev.mikhailtail.handyagent.kernel.ApprovalDecision
import dev.mikhailtail.handyagent.kernel.ApprovalGate
import dev.mikhailtail.handyagent.kernel.ApprovalRequest
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * 通过 WebSocket 向用户要审批。
 *
 * 事件的字段与 cc-haha 的 `permission_request` 一致：
 * `{ type, requestId, toolName, toolUseId, input, description }`；
 * 用户回 `{ type: 'permission_response', requestId, allowed }`。
 *
 * **超时一律视为拒绝**，不是放行。这条方向不能反：审批这种东西，
 * 拿不准的时候必须是"不做"，而非"做了再说"。
 */
class WsApprovalGate(
    private val session: WebSocketSession,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : ApprovalGate {

    /** requestId → 等待中的结论。用户回包时在此唤醒等待方。 */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** 前端回了包。找不到对应 requestId 就忽略（可能是超时后才到的迟到回复）。 */
    fun onResponse(requestId: String, allowed: Boolean) {
        pending.remove(requestId)?.complete(allowed)
    }

    override suspend fun request(request: ApprovalRequest): ApprovalDecision {
        val deferred = CompletableDeferred<Boolean>()
        pending[request.requestId] = deferred

        session.send(
            Frame.Text(
                buildJsonObject {
                    put("type", JsonPrimitive("permission_request"))
                    put("requestId", JsonPrimitive(request.requestId))
                    put("toolName", JsonPrimitive(request.toolName))
                    put("toolUseId", JsonPrimitive(request.requestId))
                    put("input", request.input)
                    put("description", JsonPrimitive(request.description))
                    put("displayName", JsonPrimitive(request.toolName))
                }.toString(),
            ),
        )

        val allowed = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(request.requestId)

        return when (allowed) {
            true -> ApprovalDecision.Allowed
            false -> ApprovalDecision.Denied("用户拒绝了这次操作")
            null -> ApprovalDecision.Denied(
                "等待审批超时（${timeoutMs / 1000} 秒）—— 已按拒绝处理。若仍需执行请重新发起。",
            )
        }
    }

    /** 连接断开时唤醒所有等待者，避免协程悬在那里。 */
    fun cancelAll() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    private companion object {
        /** 用户可能在看别的 App，给足时间；但也不能无限等。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
