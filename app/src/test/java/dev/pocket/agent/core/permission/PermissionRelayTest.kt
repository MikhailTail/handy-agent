package dev.pocket.agent.core.permission

import dev.pocket.agent.core.json.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loop 9：UI 起来之前 / 之后切换审批实现的链表，必须 fail-closed。 */
class PermissionRelayTest {

    private fun request(tool: String = "write") = PermissionRequest(
        toolName = tool,
        input = Json.obj("path" to Json.Str("a.txt")),
        summary = "write a.txt",
    )

    @Test
    fun `unbound relay returns null so the broker denies`() = runBlocking {
        val relay = PermissionRelay()
        assertFalse(relay.bound)
        assertNull(relay.approve(request()))
    }

    @Test
    fun `bound relay forwards to the target`() = runBlocking {
        val relay = PermissionRelay()
        var seen: PermissionRequest? = null
        relay.target = PermissionApprover { req ->
            seen = req
            PermissionVerdict.allowSession()
        }
        val verdict = relay.approve(request("edit"))
        assertTrue(relay.bound)
        assertEquals("edit", seen?.toolName)
        assertTrue(verdict!!.allowed)
        assertEquals(RememberScope.SESSION, verdict.scope)
    }

    @Test
    fun `rebinding swaps the implementation without recreating the relay`() = runBlocking {
        val relay = PermissionRelay()
        relay.target = PermissionApprover { PermissionVerdict.allowOnce() }
        assertTrue(relay.approve(request())!!.allowed)

        relay.target = PermissionApprover { PermissionVerdict.denyOnce() }
        assertFalse(relay.approve(request())!!.allowed)
    }

    @Test
    fun `unbinding after the UI dies goes back to fail-closed`() = runBlocking {
        val relay = PermissionRelay()
        relay.target = PermissionApprover { PermissionVerdict.allowOnce() }
        relay.target = null
        assertNull(relay.approve(request()))
    }

    @Test
    fun `target returning null stays null instead of becoming an allow`() = runBlocking {
        val relay = PermissionRelay()
        relay.target = PermissionApprover { null }
        assertNull(relay.approve(request()))
    }
}
