package dev.mikhailtail.handyagent.kernel.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 一个可被模型调用的工具。
 *
 * 字段刻意与 cc-haha 的 `src/Tool.ts` 对应，只保留手机上真正用得上的那些 ——
 * 它那边的 `render*`（UI 渲染）、`mapToolResultToToolResultBlockParam`（协议适配）
 * 属于宿主细节，搬过来只会增加无处安放的抽象。
 *
 * 对应关系：
 * ```
 * cc-haha Tool.ts         这里
 * ─────────────────────   ──────────────────────
 * name                    name
 * description(input)      description
 * inputSchema (Zod)       inputSchema (JsonObject)
 * isReadOnly()            isReadOnly
 * isDestructive()         isDestructive
 * checkPermissions()      needsApproval（见下）
 * call()                  execute()
 * ```
 */
interface Tool {

    /** 模型看到的工具名。必须与 Anthropic 的 `tool_use.name` 完全一致。 */
    val name: String

    /** 给模型看的说明。写得越清楚，模型用错的概率越低。 */
    val description: String

    /** JSON Schema 形态的参数定义，直接进请求体。 */
    val inputSchema: JsonObject

    /**
     * 只读工具**永不需要审批**。
     *
     * 这条不是优化而是体验底线：如果读文件也要点确认，用户会在第三次就关掉无障碍权限。
     * cc-haha 也是这个口径（`isReadOnly()`）。
     */
    val isReadOnly: Boolean get() = false

    /** 破坏性操作（删除、覆盖）。即便在宽松模式下也倾向于提示。 */
    val isDestructive: Boolean get() = false

    /**
     * 是否需要用户审批。默认由 [isReadOnly] 推导，特殊工具可以覆盖。
     *
     * 注意：这是**工具自身**的声明，最终决定权在权限管线（模式 + 规则）。
     * 工具不该知道当前是什么模式 —— 那是管线的职责。
     */
    fun needsApproval(): Boolean = !isReadOnly

    suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult
}

/**
 * 工具执行结果。
 *
 * [content] 直接作为 Anthropic 的 `tool_result.content` 发给模型 ——
 * 所以它既可能是字符串，也可能是 block 数组。**保持 JsonElement 而不做转换**，
 * 免得在中间层丢掉结构（这条在转录透传上已经吃过一次亏）。
 */
data class ToolResult(
    val content: JsonElement,
    val isError: Boolean = false,
)

/**
 * 工具执行时能碰到的一切外部能力。
 *
 * 全部是接口而非具体实现 —— `:kernel-tools` 因此保持纯 JVM，
 * 可以在电脑上用假实现跑单测，无需真实文件系统或设备。
 */
interface ToolContext {
    val files: FileHost
    val shell: ShellHost
    /** 工作目录（沙箱内的路径），工具用它解析相对路径。 */
    val workDir: String
}

/**
 * 文件系统端口 —— **没有路径沙箱**。
 *
 * 这是刻意的，也是与 cc-haha 对齐的：它在桌面上同样不限制路径，护栏靠的是
 * 审批（每个写操作弹卡）与权限模式，而不是把模型的视野圈起来。
 *
 * 在 Android 上，圈路径更是错位的安全措施 —— **进程隔离已经由 UID 与权限模型做了**，
 * 应用能碰到什么由系统决定。若在 `filesDir` 里再圈一块，效果只会是
 * "Agent 看不见你的照片和下载"，而那恰恰是它该看的东西。
 *
 * 所以这里直接以真实绝对路径工作；越权由 Android 拦，误操作由审批拦。
 */
interface FileHost {
    fun readText(path: String): String
    fun writeText(path: String, text: String)
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    /** 列目录（非递归），返回完整路径。 */
    fun list(path: String): List<String>
    fun delete(path: String)
    /** glob 匹配，返回完整路径。 */
    fun glob(pattern: String, root: String): List<String>
}

/** 命令执行端口。 */
interface ShellHost {
    suspend fun run(command: String, workDir: String, timeoutMs: Long): ShellResult
}

data class ShellResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)
