package dev.mikhailtail.handyagent.core.permission

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import dev.mikhailtail.handyagent.core.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PermissionBrokerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jail by lazy { PathJail(tmp.root) }
    private val specs by lazy { ToolRegistry.builtin().specs() }
    private val specByName by lazy { specs.associateBy { it.name } }

    /** 记录所有被询问的请求，并按脚本回答。 */
    private class ScriptedApprover(
        private val answers: MutableList<PermissionVerdict> = mutableListOf(),
    ) : PermissionApprover {
        val seen = mutableListOf<PermissionRequest>()
        var default: PermissionVerdict? = null

        fun enqueue(v: PermissionVerdict) = apply { answers.add(v) }

        override suspend fun approve(request: PermissionRequest): PermissionVerdict? {
            seen.add(request)
            return if (answers.isNotEmpty()) answers.removeAt(0) else default
        }
    }

    private fun write(path: String, text: String) {
        val f = jail.resolve(path)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    // --- 默认策略 ---------------------------------------------------------

    @Test
    fun `read-only tools are auto-allowed without asking`() = runBlocking {
        val approver = ScriptedApprover()
        val broker = PermissionBroker(approver)
        broker.applyDefaults(specs)

        val outcome = broker.check("read", Json.obj("path" to Json.Str("a.txt")), specByName["read"], jail)

        assertTrue(outcome.allowed)
        assertEquals("auto-allowed (read-only)", outcome.reason)
        assertTrue("no prompt should reach the approver", approver.seen.isEmpty())
    }

    @Test
    fun `mutating tools ask and respect approval`() = runBlocking {
        val approver = ScriptedApprover().enqueue(PermissionVerdict.allowOnce())
        val broker = PermissionBroker(approver)
        broker.applyDefaults(specs)

        write("a.txt", "old\n")
        val outcome = broker.check(
            "write",
            Json.obj("path" to Json.Str("a.txt"), "content" to Json.Str("new\n")),
            specByName["write"],
            jail,
        )

        assertTrue(outcome.allowed)
        assertEquals(1, approver.seen.size)
        assertTrue("write preview must carry the diff", approver.seen[0].hasDiff)
    }

    @Test
    fun `denial is returned as a reason, not an exception`() = runBlocking {
        val approver = ScriptedApprover().enqueue(PermissionVerdict.denyOnce())
        val broker = PermissionBroker(approver)
        broker.applyDefaults(specs)

        val outcome = broker.check(
            "bash",
            Json.obj("command" to Json.Str("rm -rf /")),
            specByName["bash"],
            jail,
        )

        assertFalse(outcome.allowed)
        assertTrue(outcome.reason.contains("user denied"))
    }

    @Test
    fun `never policy short-circuits before asking`() = runBlocking {
        val approver = ScriptedApprover()
        val broker = PermissionBroker(approver)
        broker.applyDefaults(specs)
        broker.setMode("bash", PermissionMode.NEVER)

        val outcome = broker.check("bash", Json.obj("command" to Json.Str("ls")), specByName["bash"], jail)

        assertFalse(outcome.allowed)
        assertTrue(outcome.reason.contains("disabled by policy"))
        assertTrue(approver.seen.isEmpty())
    }

    @Test
    fun `always policy skips the prompt`() = runBlocking {
        val approver = ScriptedApprover()
        val broker = PermissionBroker(approver)
        broker.applyDefaults(specs)
        broker.setMode("bash", PermissionMode.ALWAYS)

        val outcome = broker.check("bash", Json.obj("command" to Json.Str("ls")), specByName["bash"], jail)

        assertTrue(outcome.allowed)
        assertTrue(approver.seen.isEmpty())
    }

    @Test
    fun `remember session persists the decision`() = runBlocking {
        val approver = ScriptedApprover().enqueue(PermissionVerdict.allowSession())
        val broker = PermissionBroker(approver)
        broker.applyDefaults(specs)

        val first = broker.check("bash", Json.obj("command" to Json.Str("ls")), specByName["bash"], jail)
        assertTrue(first.allowed)
        assertTrue(first.remembered)
        assertEquals(PermissionMode.ALWAYS, broker.modeOf("bash"))

        // 第二次同工具调用不再询问
        val second = broker.check("bash", Json.obj("command" to Json.Str("pwd")), specByName["bash"], jail)
        assertTrue(second.allowed)
        assertEquals(1, approver.seen.size)
    }

    @Test
    fun `remember denial persists and blocks retries`() = runBlocking {
        val approver = ScriptedApprover().enqueue(PermissionVerdict.denySession())
        val broker = PermissionBroker(approver)
        broker.applyDefaults(specs)

        broker.check("write", Json.obj("path" to Json.Str("x"), "content" to Json.Str("y")), specByName["write"], jail)
        assertEquals(PermissionMode.NEVER, broker.modeOf("write"))

        val retry = broker.check("write", Json.obj("path" to Json.Str("x"), "content" to Json.Str("y")), specByName["write"], jail)
        assertFalse(retry.allowed)
        assertEquals(1, approver.seen.size)
    }

    @Test
    fun `missing approver fails closed`() = runBlocking {
        val broker = PermissionBroker(approver = null)
        broker.applyDefaults(specs)

        val outcome = broker.check("write", Json.obj("path" to Json.Str("x"), "content" to Json.Str("y")), specByName["write"], jail)

        assertFalse("must never silently allow without an approver", outcome.allowed)
        assertTrue(outcome.reason.contains("no approver"))
    }

    @Test
    fun `approver returning null is treated as denial`() = runBlocking {
        val approver = ScriptedApprover().apply { default = null }
        val broker = PermissionBroker(approver)
        broker.applyDefaults(specs)

        val outcome = broker.check("bash", Json.obj("command" to Json.Str("ls")), specByName["bash"], jail)
        assertFalse(outcome.allowed)
    }

    @Test
    fun `unknown tools default to ask`() = runBlocking {
        val approver = ScriptedApprover().enqueue(PermissionVerdict.allowOnce())
        val broker = PermissionBroker(approver)
        broker.applyDefaults(specs)

        val outcome = broker.check("mcp__github__create_issue", Json.obj("title" to Json.Str("x")), null, jail)

        assertTrue(outcome.allowed)
        assertEquals(1, approver.seen.size)
        assertEquals("mcp__github__create_issue", approver.seen[0].toolName)
    }

    @Test
    fun `requiresApproval matches check semantics`() {
        val broker = PermissionBroker(null)
        broker.applyDefaults(specs)
        assertFalse(broker.requiresApproval("read", specByName["read"]))
        assertTrue(broker.requiresApproval("write", specByName["write"]))
        assertTrue(broker.requiresApproval("unknown-tool", null))
    }

    @Test
    fun `snapshot is a defensive copy`() {
        val broker = PermissionBroker(null)
        broker.applyDefaults(specs)
        val snap = broker.snapshot()
        broker.setMode("bash", PermissionMode.NEVER)
        assertEquals(PermissionMode.ASK, snap["bash"])
    }

    // --- 预览 -------------------------------------------------------------

    @Test
    fun `write preview on new file diffs against empty`() {
        val previewer = ToolPreviewer()
        val req = previewer.preview(
            "write",
            Json.obj("path" to Json.Str("new.txt"), "content" to Json.Str("a\nb")),
            specByName["write"],
            jail,
        )
        assertTrue(req.summary.startsWith("create new.txt"))
        assertTrue(req.diffLines.all { it.kind.name != "DEL" })
        assertEquals(2, req.stats.added)
        assertTrue(req.diffText.startsWith("--- /dev/null"))
    }

    @Test
    fun `write preview on existing file reports overwrite with real diff`() {
        write("a.txt", "keep\ndrop\n")
        val req = ToolPreviewer().preview(
            "write",
            Json.obj("path" to Json.Str("a.txt"), "content" to Json.Str("keep\nadd\n")),
            specByName["write"],
            jail,
        )
        assertTrue(req.summary.startsWith("overwrite a.txt"))
        assertEquals(1, req.stats.added)
        assertEquals(1, req.stats.removed)
    }

    @Test
    fun `edit preview shows the exact replacement`() {
        write("a.kt", "val x = 1\nval y = 2\n")
        val req = ToolPreviewer().preview(
            "edit",
            Json.obj(
                "path" to Json.Str("a.kt"),
                "old_string" to Json.Str("val x = 1"),
                "new_string" to Json.Str("val x = 42"),
            ),
            specByName["edit"],
            jail,
        )
        assertTrue(req.summary.contains("1 replacement"))
        assertEquals(1, req.stats.added)
        assertEquals(1, req.stats.removed)
        assertTrue(req.diffText.contains("-val x = 1"))
        assertTrue(req.diffText.contains("+val x = 42"))
    }

    @Test
    fun `edit preview flags ambiguous match without lying about the diff`() {
        write("a.kt", "x\nx\n")
        val req = ToolPreviewer().preview(
            "edit",
            Json.obj(
                "path" to Json.Str("a.kt"),
                "old_string" to Json.Str("x"),
                "new_string" to Json.Str("y"),
            ),
            specByName["edit"],
            jail,
        )
        assertTrue(req.summary.contains("ambiguous"))
        assertTrue("no change means no diff", req.diffLines.isEmpty())
    }

    @Test
    fun `edit preview on missing file does not throw`() {
        val req = ToolPreviewer().preview(
            "edit",
            Json.obj(
                "path" to Json.Str("ghost.kt"),
                "old_string" to Json.Str("a"),
                "new_string" to Json.Str("b"),
            ),
            specByName["edit"],
            jail,
        )
        assertTrue(req.summary.contains("file not found"))
        assertFalse(req.hasDiff)
    }

    @Test
    fun `edit preview never escapes the sandbox`() {
        val req = ToolPreviewer().preview(
            "write",
            Json.obj("path" to Json.Str("../../etc/passwd"), "content" to Json.Str("x")),
            specByName["write"],
            jail,
        )
        // 预览阶段不抛异常，但也绝不会读到沙箱外的文件
        assertTrue(req.summary.isNotEmpty())
        assertFalse(req.hasDiff)
    }

    @Test
    fun `bash preview carries the command`() {
        val req = ToolPreviewer().preview(
            "bash",
            Json.obj("command" to Json.Str("echo hi")),
            specByName["bash"],
            jail,
        )
        assertEquals("run: echo hi", req.summary)
        assertFalse(req.hasDiff)
    }

    @Test
    fun `huge diff is capped for ui safety`() {
        val big = ToolPreviewer(maxDiffLines = 10).preview(
            "write",
            Json.obj("path" to Json.Str("big.txt"), "content" to Json.Str((1..100).joinToString("\n"))),
            specByName["write"],
            jail,
        )
        assertEquals(10, big.diffLines.size)
        assertNotNull(big.diffText)
    }
}
