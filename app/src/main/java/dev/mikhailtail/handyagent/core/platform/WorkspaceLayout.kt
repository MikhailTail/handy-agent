package dev.mikhailtail.handyagent.core.platform

import dev.mikhailtail.handyagent.core.plugin.PluginLoader
import dev.mikhailtail.handyagent.core.skill.SkillLoader
import java.io.File

/**
 * App 私有目录布局（多 workspace 版）。
 *
 * ```
 * <appRoot>/
 *   workspaces/
 *     <id>/
 *       files/        <- PathJail 的根：该 workspace 里所有会话共享这块沙箱
 *       sessions/     <- 该 workspace 下的会话
 *   .pocket/skills/   <- 全局：技能对所有 workspace 生效
 *   .pocket/plugins/  <- 全局：插件同理
 *   images/           <- 全局：图片字节与会话解耦，切换 workspace 不必重存
 * ```
 *
 * 为什么 skills/plugins/images 放在 workspace 之外：
 * - skills/plugins 是「能改变 Agent 行为」的东西，放进沙箱等于让模型自己给自己提权；
 * - images 与具体 workspace 无关，会话文件里只存文件名，放全局才能跨 workspace 复用。
 *
 * 为什么会话挂在 workspace 下而不是全局：用户要的是「同一 workspace 里的会话共享文件」，
 * 那把会话和它的沙箱绑在一起最自然，删除 workspace 时也能一并清理。
 */
class WorkspaceLayout(val appRoot: File) {

    /** 所有 workspace 的容器目录。 */
    val workspaces: File get() = File(appRoot, WORKSPACES_DIR)

    /** 某个 workspace 的专属目录。 */
    fun dirOf(workspaceId: String): File = File(workspaces, safeId(workspaceId))

    /** 某个 workspace 的沙箱根 —— [PathJail] 作用域就是这个目录。 */
    fun filesOf(workspaceId: String): File = File(dirOf(workspaceId), FILES_DIR)

    /** 某个 workspace 下的会话目录。 */
    fun sessionsOf(workspaceId: String): File = File(dirOf(workspaceId), SESSIONS_DIR)

    val dotPocket: File get() = File(appRoot, DOT_DIR)

    /** Skills 目录。加载器签名是「传根目录、内部拼 `.pocket/skills`」，所以加载时传 [appRoot]。 */
    val skills: File get() = File(appRoot, SkillLoader.SKILLS_DIR)

    /** Plugins 目录；同 [skills]，加载时传 [appRoot]。 */
    val plugins: File get() = File(appRoot, PluginLoader.PLUGINS_DIR)

    /** 图片字节目录（全局）。 */
    val images: File get() = File(appRoot, IMAGES_DIR)

    /** 默认 workspace 的沙箱根；等价于 [filesOf]`(DEFAULT_WORKSPACE_ID)`。 */
    val workspace: File get() = filesOf(DEFAULT_WORKSPACE_ID)

    /** 默认 workspace 的会话目录。 */
    val sessions: File get() = sessionsOf(DEFAULT_WORKSPACE_ID)

    /**
     * 建全部目录并做一次性迁移；幂等，可重复调用。
     *
     * 迁移：老版本是「单 workspace + 全局会话」，布局为 `<appRoot>/workspace` 与
     * `<appRoot>/sessions`。这里把它们搬进默认 workspace 下，**保证用户已有的会话和
     * 文件不丢**。只在目标不存在时才搬，所以不会覆盖新数据。
     */
    fun bootstrap(): WorkspaceLayout {
        listOf(appRoot, workspaces, dotPocket, skills, plugins, images).forEach { it.mkdirs() }
        val defaultFiles = filesOf(DEFAULT_WORKSPACE_ID)
        val defaultSessions = sessionsOf(DEFAULT_WORKSPACE_ID)
        defaultFiles.mkdirs()
        defaultSessions.mkdirs()

        migrateLegacy(File(appRoot, LEGACY_WORKSPACE_DIR), defaultFiles)
        migrateLegacy(File(appRoot, LEGACY_SESSIONS_DIR), defaultSessions)
        return this
    }

    /** 老目录整体搬过去；目标已有内容就跳过（宁可留旧数据，也不覆盖新的）。 */
    private fun migrateLegacy(legacy: File, target: File) {
        if (!legacy.exists() || !legacy.isDirectory) return
        val children = legacy.listFiles() ?: return
        if (children.isEmpty()) {
            runCatching { legacy.delete() }
            return
        }
        if (target.listFiles()?.isNotEmpty() == true) return
        children.forEach { child ->
            runCatching { child.renameTo(File(target, child.name)) }
        }
        runCatching { if (legacy.listFiles()?.isEmpty() == true) legacy.delete() }
    }

    fun isBootstrapped(): Boolean =
        workspaces.isDirectory && skills.isDirectory && plugins.isDirectory &&
            images.isDirectory && filesOf(DEFAULT_WORKSPACE_ID).isDirectory

    /** 人类可读的一行摘要，用于启动日志 / 设置页。 */
    fun describe(): String =
        "workspace=${workspace.absolutePath} skills=${skills.isDirectory} plugins=${plugins.isDirectory}"

    /** workspace id 会变成目录名，必须挡住路径穿越（它来自用户输入/持久化）。 */
    private fun safeId(id: String): String {
        val cleaned = id.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return cleaned.ifBlank { DEFAULT_WORKSPACE_ID }
    }

    companion object {
        /** 默认 workspace 的 id；老数据迁移到这里。 */
        const val DEFAULT_WORKSPACE_ID = "default"

        const val WORKSPACES_DIR = "workspaces"
        const val FILES_DIR = "files"
        const val SESSIONS_DIR = "sessions"
        const val DOT_DIR = ".pocket"
        const val IMAGES_DIR = "images"

        /** 老版本的目录名，仅用于迁移。 */
        private const val LEGACY_WORKSPACE_DIR = "workspace"
        private const val LEGACY_SESSIONS_DIR = "sessions"
    }
}
