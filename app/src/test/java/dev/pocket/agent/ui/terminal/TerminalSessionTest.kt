package dev.pocket.agent.ui.terminal

import dev.pocket.agent.core.sandbox.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loop 9：终端页的纯逻辑（历史、上限、提示符渲染），不需要真的起进程。 */
class TerminalSessionTest {

    private fun result(
        command: String,
        exitCode: Int = 0,
        stdout: String = "",
        stderr: String = "",
        timedOut: Boolean = false,
    ) = ShellResult(
        command = command,
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        timedOut = timedOut,
    )

    /** 记录每条命令的注入执行器，默认返回成功。 */
    private class Recorder(private val respond: (String) -> ShellResult) {
        val calls = ArrayList<String>()
        fun exec(cmd: String): ShellResult {
            calls.add(cmd)
            return respond(cmd)
        }
    }

    private fun session(
        maxEntries: Int = TerminalSession.MAX_ENTRIES,
        respond: (String) -> ShellResult = { result(it) },
    ): Pair<TerminalSession, Recorder> {
        val recorder = Recorder(respond)
        return TerminalSession(recorder::exec, maxEntries) to recorder
    }

    // -------------------------------------------------------------- 输入规约

    @Test
    fun `blank commands are ignored and never reach the shell`() {
        val (session, recorder) = session()
        assertNull(session.run(""))
        assertNull(session.run("   "))
        assertNull(session.run("\t\n"))
        assertTrue(session.snapshot().isEmpty())
        assertTrue("blank input must not spawn a process", recorder.calls.isEmpty())
    }

    @Test
    fun `commands are trimmed before execution and display`() {
        val (session, recorder) = session()
        val entry = session.run("  ls -la  ")
        assertEquals("ls -la", entry!!.command)
        assertEquals(listOf("ls -la"), recorder.calls)
        assertEquals("ls -la", session.snapshot().single().command)
    }

    // -------------------------------------------------------------- 结果记账

    @Test
    fun `successful command is recorded as ok`() {
        val (session, _) = session { result(it, stdout = "hello\n") }
        val entry = session.run("echo hello")!!
        assertTrue(entry.ok)
        assertEquals(0, entry.exitCode)
        assertFalse(entry.timedOut)
        assertEquals(1, session.snapshot().size)
    }

    @Test
    fun `entry render puts the prompt above the output`() {
        val (session, _) = session { result(it, stdout = "a\nb\n") }
        val entry = session.run("ls")!!
        val rendered = entry.render()
        assertTrue("missing prompt line: $rendered", rendered.startsWith("$ ls\n"))
        assertTrue("missing body: $rendered", rendered.contains("a\nb"))
        // 成功的命令不加 exit 尾巴，保持终端该有的干净。
        assertFalse("no trailing marker expected: $rendered", rendered.contains("[exit="))
    }

    @Test
    fun `empty output still renders the prompt line`() {
        val (session, _) = session { result(it) }
        val entry = session.run("true")!!
        assertTrue(entry.render().startsWith("$ true"))
        // exit=0 仍来自 ShellResult.render()，不是空白条目。
        assertTrue(entry.render().contains("exit=0"))
    }

    @Test
    fun `failing command renders an exit trailer`() {
        val (session, _) = session { result(it, exitCode = 2, stderr = "boom\n") }
        val entry = session.run("false")!!
        assertFalse(entry.ok)
        val rendered = entry.render()
        assertTrue("stderr dropped: $rendered", rendered.contains("boom"))
        assertTrue("missing exit trailer: $rendered", rendered.endsWith("[exit=2]"))
    }

    @Test
    fun `timed out command renders the kill marker`() {
        val (session, _) = session { result(it, exitCode = 124, timedOut = true) }
        val entry = session.run("sleep 999")!!
        assertFalse(entry.ok)
        assertTrue(entry.timedOut)
        assertTrue(entry.render().endsWith("[超时被强杀]"))
    }

    // -------------------------------------------------------------- 历史上限

    @Test
    fun `history keeps the newest entries and drops the oldest whole entry`() {
        val (session, _) = session(maxEntries = 3)
        (1..5).forEach { session.run("c$it") }

        val kept = session.snapshot()
        assertEquals(3, kept.size)
        assertEquals(listOf("c3", "c4", "c5"), kept.map { it.command })
    }

    @Test
    fun `history cap never truncates the entry you are looking at`() {
        val (session, _) = session(maxEntries = 1) { result(it, stdout = "long output\n") }
        session.run("c1")
        session.run("c2")
        val kept = session.snapshot().single()
        assertEquals("c2", kept.command)
        // 整条保留：提示符、输出、exit 都在。
        assertTrue(kept.render().contains("long output"))
        assertTrue(kept.render().contains("exit=0"))
    }

    @Test
    fun `clear wipes the history but keeps the session usable`() {
        val (session, _) = session()
        session.run("a")
        session.run("b")
        session.clear()
        assertTrue(session.snapshot().isEmpty())
        session.run("c")
        assertEquals(listOf("c"), session.snapshot().map { it.command })
    }
}
