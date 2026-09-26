package dev.pocket.agent.core.platform

import dev.pocket.agent.core.plugin.PluginLoader
import dev.pocket.agent.core.skill.SkillLoader
import java.io.File

/**
 * App 私有目录布局。
 *
 * ```
 * <appRoot>/
 *   workspace/             <- PathJail 的根，Agent 只能看到这里
 *   .pocket/skills/        <- 每个技能一个子目录（SKILL.md）
 *   .pocket/plugins/       <- 每个插件一个子目录（plugin.json）
 *   sessions/              <- 历史会话落盘（Loop 9 使用）
 * ```
 *
 * 为什么 skills/plugins 放在 appRoot 而不是 workspace 里：
 * Agent 能写 workspace，若技能/插件也在其中，模型就能自己给自己加权限（提权路径）。
 * 因此这些「能改变 Agent 行为」的目录必须在沙箱之外，只能由用户从 Files 页写入。
 */
class WorkspaceLayout(val appRoot: File) {

    val workspace: File get() = File(appRoot, WORKSPACE_DIR)

    val dotPocket: File get() = File(appRoot, DOT_DIR)

    /** Skills 目录。加载器签名是「传根目录、内部拼 `.pocket/skills`」，所以加载时传 [appRoot]。 */
    val skills: File get() = File(appRoot, SkillLoader.SKILLS_DIR)

    /** Plugins 目录；同 [skills]，加载时传 [appRoot]。 */
    val plugins: File get() = File(appRoot, PluginLoader.PLUGINS_DIR)

    val sessions: File get() = File(appRoot, SESSIONS_DIR)

    /** 创建全部目录；幂等，可重复调用。返回自身便于链式书写。 */
    fun bootstrap(): WorkspaceLayout {
        listOf(appRoot, workspace, dotPocket, skills, plugins, sessions).forEach { it.mkdirs() }
        return this
    }

    fun isBootstrapped(): Boolean =
        workspace.isDirectory && skills.isDirectory && plugins.isDirectory && sessions.isDirectory

    /** 人类可读的一行摘要，用于启动日志 / 设置页。 */
    fun describe(): String =
        "workspace=${workspace.absolutePath} skills=${skills.isDirectory} plugins=${plugins.isDirectory}"

    companion object {
        const val WORKSPACE_DIR = "workspace"
        const val DOT_DIR = ".pocket"
        const val SESSIONS_DIR = "sessions"
    }
}
