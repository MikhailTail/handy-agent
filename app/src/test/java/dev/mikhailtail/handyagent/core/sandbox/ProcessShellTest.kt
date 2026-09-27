package dev.mikhailtail.handyagent.core.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessShellTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun shell(timeoutMs: Long = 5_000, maxOutput: Int = 8 * 1024) =
        ProcessShell(tmp.root, timeoutMs, maxOutput)

    @Test
    fun `echo writes stdout and exits zero`() {
        val r = shell().run("echo hello")
        assertEquals(0, r.exitCode)
        assertEquals("hello\n", r.stdout)
        assertTrue(r.ok)
    }

    @Test
    fun `non zero exit code is surfaced`() {
        val r = shell().run("exit 3")
        assertEquals(3, r.exitCode)
        assertFalse(r.ok)
    }

    @Test
    fun `stderr is kept separate from stdout`() {
        val r = shell().run("echo oops 1>&2")
        assertEquals(0, r.exitCode)
        assertEquals("", r.stdout)
        assertTrue(r.stderr.contains("oops"))
    }

    @Test
    fun `working directory is the sandbox root`() {
        val r = shell().run("pwd")
        assertEquals(0, r.exitCode)
        assertEquals(tmp.root.canonicalPath, File(r.stdout.trim()).canonicalPath)
    }

    @Test
    fun `cd does not leak between invocations`() {
        val s = shell()
        s.run("cd / && pwd")
        val second = s.run("pwd")
        assertEquals(tmp.root.canonicalPath, File(second.stdout.trim()).canonicalPath)
    }

    @Test
    fun `timeout forcibly kills the process`() {
        val r = shell(timeoutMs = 300).run("sleep 30")
        assertTrue("expected timedOut", r.timedOut)
        assertFalse(r.ok)
        assertTrue(r.render().contains("[timed out]"))
    }

    @Test
    fun `oversized output is truncated without deadlock`() {
        val r = shell(maxOutput = 64).run("yes a | head -n 2000")
        assertTrue("expected truncated", r.truncated)
        assertEquals(64, r.stdout.length)
        assertTrue(r.render().contains("[output truncated]"))
    }

    @Test
    fun `blank command is rejected without starting a process`() {
        val r = shell().run("   ")
        assertEquals(-1, r.exitCode)
        assertTrue(r.stderr.contains("empty command"))
    }

    @Test
    fun `environment is scrubbed to the allowlist`() {
        val r = shell().run("echo \$MY_SECRET_TOKEN; echo \$PATH")
        assertEquals(0, r.exitCode)
        // 注意不要 trim()：首行必须是空字符串，否则会把 PATH 顶上来。
        val lines = r.stdout.split('\n')
        assertEquals("", lines[0])
        assertTrue(lines[1].isNotEmpty())
    }

    @Test
    fun `default shell is an executable path or plain sh`() {
        val s = ProcessShell.defaultShell()
        assertTrue(s == "sh" || File(s).canExecute())
    }
}
