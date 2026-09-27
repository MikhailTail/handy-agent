package dev.mikhailtail.handyagent.core.tool

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import dev.mikhailtail.handyagent.core.sandbox.ProcessShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ctx by lazy { ToolContext(PathJail(tmp.root), ProcessShell(tmp.root)) }

    private val throwing = object : Tool {
        override val name = "boom"
        override val description = "always throws"
        override val inputSchema = Json.obj("type" to Json.Str("object"))
        override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome =
            throw IllegalStateException("kaboom")
    }

    private val escaping = object : Tool {
        override val name = "escape"
        override val description = "violates sandbox"
        override val inputSchema = Json.obj("type" to Json.Str("object"))
        override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
            ctx.jail.resolve("../../etc/passwd")
            return ToolOutcome.ok("unreachable")
        }
    }

    @Test
    fun `builtin registry exposes the eight core tools`() {
        val names = ToolRegistry.builtin().names()
        assertEquals(8, names.size)
        assertTrue(names.containsAll(listOf("read", "write", "edit", "ls", "glob", "grep", "bash", "todo")))
    }

    @Test
    fun `read only tools are flagged for auto approval`() {
        val specs = ToolRegistry.builtin().specs().associateBy { it.name }
        assertEquals(true, specs.getValue("read").readOnly)
        assertEquals(true, specs.getValue("grep").readOnly)
        assertEquals(false, specs.getValue("write").readOnly)
        assertEquals(false, specs.getValue("bash").readOnly)
    }

    @Test
    fun `every builtin tool has a well formed object schema`() {
        for (spec in ToolRegistry.builtin().specs()) {
            assertEquals("schema of ${spec.name}", "object", spec.inputSchema.str("type"))
            assertNotNull("properties of ${spec.name}", spec.inputSchema.obj("properties"))
            assertTrue("description of ${spec.name}", spec.description.isNotBlank())
        }
    }

    @Test
    fun `duplicate names are rejected at construction`() {
        try {
            ToolRegistry(listOf(throwing, throwing))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("boom"))
        }
    }

    @Test
    fun `unknown tool yields an error outcome rather than throwing`() = runBlocking {
        val out = ToolRegistry.builtin().execute("nope", Json.obj(), ctx)
        assertTrue(out.isError)
        assertEquals("unknown tool: nope", out.content)
    }

    @Test
    fun `tool exceptions become error outcomes`() = runBlocking {
        val out = ToolRegistry(listOf(throwing)).execute("boom", Json.obj(), ctx)
        assertTrue(out.isError)
        assertTrue(out.content.contains("IllegalStateException"))
        assertTrue(out.content.contains("kaboom"))
    }

    @Test
    fun `sandbox violations are labelled distinctly`() = runBlocking {
        val out = ToolRegistry(listOf(escaping)).execute("escape", Json.obj(), ctx)
        assertTrue(out.isError)
        assertTrue(out.content.startsWith("sandbox violation"))
    }

    @Test
    fun `register extends a registry without mutating the original`() {
        val base = ToolRegistry(listOf(ReadFileTool))
        val extended = base.register(throwing)
        assertEquals(listOf("read"), base.names())
        assertEquals(2, extended.size)
        assertNotNull(extended.get("boom"))
        assertNull(base.get("boom"))
    }

    @Test
    fun `cancellation is propagated instead of swallowed`() {
        val cancelling = object : Tool {
            override val name = "cancel"
            override val description = "cancels"
            override val inputSchema = Json.obj("type" to Json.Str("object"))
            override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome =
                throw kotlinx.coroutines.CancellationException("stop")
        }
        try {
            runBlocking { ToolRegistry(listOf(cancelling)).execute("cancel", Json.obj(), ctx) }
            throw AssertionError("CancellationException should propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("stop", e.message)
        }
    }
}
