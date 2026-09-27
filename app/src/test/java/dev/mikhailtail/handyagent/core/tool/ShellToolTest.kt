package dev.mikhailtail.handyagent.core.tool

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import dev.mikhailtail.handyagent.core.sandbox.ProcessShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ctx by lazy { ToolContext(PathJail(tmp.root), ProcessShell(tmp.root)) }
    private val registry by lazy { ToolRegistry(listOf(ShellTool, WriteFileTool, ReadFileTool)) }

    private fun bash(cmd: String) =
        runBlocking { registry.execute("bash", Json.obj("command" to Json.Str(cmd)), ctx) }

    @Test
    fun `successful command renders exit code and stdout`() {
        val out = bash("echo hi")
        assertFalse(out.isError)
        // 只断言 stdout 段：宿主容器的动态链接器会向 stderr 打无关 warning，
        // 真机上不会出现，实现层刻意不过滤 stderr（静默吞掉真实错误更危险）。
        assertTrue(out.content.startsWith("exit=0\nhi"))
    }

    @Test
    fun `failing command is not flagged as a tool error`() {
        val out = bash("echo bad 1>&2; exit 7")
        assertFalse("exit code should not be a tool error", out.isError)
        assertTrue(out.content.contains("exit=7"))
        assertTrue(out.content.contains("--- stderr ---"))
        assertTrue(out.content.contains("bad"))
    }

    @Test
    fun `timeout is surfaced as a tool error`() {
        val out = runBlocking {
            registry.execute(
                "bash",
                Json.obj("command" to Json.Str("sleep 30"), "timeout_ms" to Json.of(300)),
                ctx,
            )
        }
        assertTrue(out.isError)
        assertTrue(out.content.contains("[timed out]"))
    }

    @Test
    fun `shell shares the workspace with the file tools`() {
        runBlocking {
            registry.execute(
                "write",
                Json.obj("path" to Json.Str("note.txt"), "content" to Json.Str("from-tool")),
                ctx,
            )
        }
        val out = bash("cat note.txt")
        assertTrue(out.content.contains("from-tool"))
    }

    @Test
    fun `blank command is an error`() {
        val out = bash("   ")
        assertTrue(out.isError)
    }
}
