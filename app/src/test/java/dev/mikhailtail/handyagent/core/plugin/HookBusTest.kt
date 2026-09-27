package dev.mikhailtail.handyagent.core.plugin

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.tool.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookBusTest {

    private val anyInput = Json.obj("a" to Json.Num(1.0))

    // ------------------------------------------------------------- PreToolUse

    @Test
    fun `empty bus allows the input through unchanged`() = runBlocking {
        val bus = HookBus()
        val result = bus.preToolUse("read", anyInput)
        assertFalse(result.denied)
        assertEquals(anyInput, result.input)
        assertFalse(bus.hasPreToolUse)
    }

    @Test
    fun `allow hooks run in order`() = runBlocking {
        val bus = HookBus()
        val seen = ArrayList<String>()
        bus.onPreToolUse { seen.add("first"); HookDecision.Allow }
        bus.onPreToolUse { seen.add("second"); HookDecision.Allow }
        bus.preToolUse("read", anyInput)
        assertEquals(listOf("first", "second"), seen)
        assertEquals(2, bus.count())
    }

    @Test
    fun `replace input is passed to the next hook`() = runBlocking {
        val bus = HookBus()
        val rewritten = Json.obj("a" to Json.Num(2.0))
        bus.onPreToolUse { HookDecision.ReplaceInput(rewritten) }
        var observed: Json? = null
        bus.onPreToolUse { observed = it.input; HookDecision.Allow }

        val result = bus.preToolUse("read", anyInput)
        assertEquals(rewritten, observed)
        assertEquals(rewritten, result.input)
        assertFalse(result.denied)
    }

    @Test
    fun `deny short circuits and records the reason`() = runBlocking {
        val bus = HookBus()
        val seen = ArrayList<String>()
        bus.onPreToolUse { HookDecision.deny("nope") }
        bus.onPreToolUse { seen.add("should not run"); HookDecision.Allow }

        val result = bus.preToolUse("bash", anyInput)
        assertTrue(result.denied)
        assertEquals("nope", result.reason)
        assertEquals("hook#0", result.deniedBy)
        assertTrue("later hooks must not run after a deny", seen.isEmpty())
    }

    @Test
    fun `a throwing hook is skipped fail-open and recorded`() = runBlocking {
        val bus = HookBus()
        bus.onPreToolUse { throw IllegalStateException("boom") }
        bus.onPreToolUse { HookDecision.Allow }

        val result = bus.preToolUse("read", anyInput)
        assertFalse("a broken hook must not block the call", result.denied)
        assertEquals(anyInput, result.input)

        val errors = bus.drainErrors()
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("PreToolUse hook#0 failed"))
        assertTrue(errors.single().contains("IllegalStateException"))
        assertTrue(bus.drainErrors().isEmpty())
        assertTrue(bus.peekErrors().isEmpty())
    }

    // ------------------------------------------------------------- 通知型钩子

    @Test
    fun `post tool use delivers the outcome to every hook and swallows failures`() = runBlocking {
        val bus = HookBus()
        val got = ArrayList<String>()
        bus.onPostToolUse { got.add("a:${(it as HookEvent.PostToolUse).outcome.content}") }
        bus.onPostToolUse { throw RuntimeException("noisy") }
        bus.onPostToolUse { got.add("c") }

        bus.postToolUse("write", anyInput, ToolOutcome.ok("done"), 12L)
        assertEquals(listOf("a:done", "c"), got)
        assertTrue(bus.peekErrors().single().contains("PostToolUse hook#1 failed"))
    }

    @Test
    fun `post tool use carries the error outcome through`() = runBlocking {
        val bus = HookBus()
        var captured: HookEvent.PostToolUse? = null
        bus.onPostToolUse { captured = it as HookEvent.PostToolUse }
        bus.postToolUse("bash", anyInput, ToolOutcome.error("kaboom"), 3L)
        assertEquals("kaboom", captured!!.outcome.content)
        assertTrue(captured!!.outcome.isError)
        assertEquals(3L, captured!!.durationMs)
    }

    // ------------------------------------------------------------- 提示词钩子

    @Test
    fun `user prompt hooks chain their rewrites`() = runBlocking {
        val bus = HookBus()
        bus.onUserPromptSubmit { it.text + " [A]" }
        bus.onUserPromptSubmit { it.text + " [B]" }
        assertEquals("go [A] [B]", bus.userPromptSubmit("go"))
    }

    @Test
    fun `a throwing prompt hook preserves the previous rewrite`() = runBlocking {
        val bus = HookBus()
        bus.onUserPromptSubmit { it.text + " [A]" }
        bus.onUserPromptSubmit { throw IllegalStateException("bad hook") }
        assertEquals("go [A]", bus.userPromptSubmit("go"))
        assertTrue(bus.hasUserPromptSubmit)
        assertTrue(bus.peekErrors().single().contains("UserPromptSubmit hook#1 failed"))
    }

    @Test
    fun `session start fires every session hook once per call`() = runBlocking {
        val bus = HookBus()
        var count = 0
        bus.onSessionStart { count++ }
        bus.sessionStart()
        bus.sessionStart()
        assertEquals(2, count)
        assertTrue(bus.hasSessionStart)
    }

    // ------------------------------------------------------------- 错误缓冲

    @Test
    fun `error buffer is bounded and drops the oldest`() = runBlocking {
        val bus = HookBus(maxErrors = 2)
        bus.onPreToolUse { throw IllegalStateException("e") }
        repeat(3) { bus.preToolUse("read", anyInput) }

        val errors = bus.drainErrors()
        assertEquals(2, errors.size)
        assertTrue(errors.all { it.contains("hook#0 failed") })
    }
}
