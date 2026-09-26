package dev.pocket.agent.core.tool

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.sandbox.PathJail
import dev.pocket.agent.core.sandbox.ProcessShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TodoToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ctx by lazy { ToolContext(PathJail(tmp.root), ProcessShell(tmp.root)) }
    private val registry by lazy { ToolRegistry(listOf(TodoTool)) }

    private fun run(input: Json) = runBlocking { registry.execute("todo", input, ctx) }

    private fun item(content: String, status: String) = Json.obj(
        "content" to Json.Str(content),
        "status" to Json.Str(status),
    )

    @Test
    fun `todo renders a full list and replaces prior state`() {
        val first = run(Json.obj("items" to Json.arr(item("design", "completed"), item("build", "in_progress"))))
        assertFalse(first.isError)
        assertEquals("[x] design\n[>] build", first.content)

        val second = run(Json.obj("items" to Json.arr(item("ship", "pending"))))
        assertEquals("[ ] ship", second.content)
        assertEquals(1, ctx.todos.snapshot().size)
    }

    @Test
    fun `status defaults to pending when omitted`() {
        val out = run(Json.obj("items" to Json.arr(Json.obj("content" to Json.Str("later")))))
        assertEquals("[ ] later", out.content)
    }

    @Test
    fun `invalid status is rejected with a helpful message`() {
        val out = run(Json.obj("items" to Json.arr(item("x", "doing"))))
        assertTrue(out.isError)
        assertTrue(out.content.contains("invalid"))
        assertTrue(out.content.contains("doing"))
    }

    @Test
    fun `blank content is rejected and store is untouched`() {
        run(Json.obj("items" to Json.arr(item("keep", "pending"))))
        val out = run(Json.obj("items" to Json.arr(item("   ", "pending"))))
        assertTrue(out.isError)
        assertTrue(out.content.contains("content must not be empty"))
        assertEquals(1, ctx.todos.snapshot().size)
    }

    @Test
    fun `empty list clears the store`() {
        run(Json.obj("items" to Json.arr(item("a", "pending"))))
        val out = run(Json.obj("items" to Json.arr(emptyList())))
        assertEquals("task list cleared", out.content)
        assertTrue(ctx.todos.snapshot().isEmpty())
        assertEquals("(no tasks)", ctx.todos.render())
    }

    @Test
    fun `missing items field clears rather than crashes`() {
        val out = run(Json.obj())
        assertEquals("task list cleared", out.content)
    }
}
