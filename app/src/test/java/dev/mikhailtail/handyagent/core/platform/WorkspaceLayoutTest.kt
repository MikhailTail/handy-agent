package dev.mikhailtail.handyagent.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceLayoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `bootstrap creates every directory it advertises`() {
        val layout = WorkspaceLayout(File(tmp.root, "app")).bootstrap()

        assertTrue(layout.appRoot.isDirectory)
        assertTrue(layout.workspace.isDirectory)
        assertTrue(layout.dotPocket.isDirectory)
        assertTrue(layout.skills.isDirectory)
        assertTrue(layout.plugins.isDirectory)
        assertTrue(layout.sessions.isDirectory)
        assertTrue(layout.isBootstrapped())
    }

    @Test
    fun `bootstrap works when the app root does not exist yet`() {
        val nested = File(File(tmp.root, "a"), "b/c")
        assertFalse(nested.exists())
        val layout = WorkspaceLayout(nested).bootstrap()
        assertTrue(layout.workspace.isDirectory)
        assertTrue(layout.isBootstrapped())
    }

    @Test
    fun `isBootstrapped is false before bootstrap`() {
        assertFalse(WorkspaceLayout(File(tmp.root, "fresh")).isBootstrapped())
    }

    @Test
    fun `bootstrap is idempotent and keeps existing content`() {
        val layout = WorkspaceLayout(File(tmp.root, "app"))
        layout.bootstrap()
        val marker = File(layout.workspace, "notes.txt").apply { writeText("keep me") }

        layout.bootstrap()

        assertTrue(marker.isFile)
        assertEquals("keep me", marker.readText())
    }

    /**
     * 安全不变量：Agent 可以写 workspace，所以**任何能改变 Agent 行为的资产
     * （技能、插件、会话）都必须落在 workspace 之外**，否则模型能给自己提权。
     */
    @Test
    fun `skills and plugins live outside the writable workspace`() {
        val layout = WorkspaceLayout(File(tmp.root, "app")).bootstrap()
        val workspace = layout.workspace.canonicalPath

        for (dir in listOf(layout.skills, layout.plugins, layout.dotPocket, layout.sessions)) {
            assertFalse(
                "agent-writable dir must not sit inside the sandbox: $dir",
                dir.canonicalPath == workspace || dir.canonicalPath.startsWith(workspace + File.separator),
            )
        }
    }

    @Test
    fun `loader directories match the loaders' own constants`() {
        val layout = WorkspaceLayout(File(tmp.root, "app"))
        assertTrue(layout.skills.path.endsWith("skills"))
        assertTrue(layout.plugins.path.endsWith("plugins"))
        // 关键安全约束：skills/plugins 必须落在沙箱**之外**，
        // 否则模型可以直接往里写文件，给自己加权限
        assertFalse(
            "skills 不能位于沙箱内：${layout.skills.path} vs ${layout.workspace.path}",
            layout.skills.path.startsWith(layout.workspace.path),
        )
        assertFalse(
            "plugins 不能位于沙箱内",
            layout.plugins.path.startsWith(layout.workspace.path),
        )
    }

    @Test
    fun `describe reports both directories`() {
        val layout = WorkspaceLayout(File(tmp.root, "app"))
        assertTrue(layout.describe().contains("skills=false"))
        layout.bootstrap()
        assertTrue(layout.describe().contains("skills=true"))
    }
}
