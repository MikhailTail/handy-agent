package dev.mikhailtail.handyagent.kernel

import dev.mikhailtail.handyagent.kernel.api.Tool
import kotlinx.serialization.json.JsonObject

/**
 * 权限模式 —— 取值与 cc-haha 的 `PermissionMode` 一致。
 *
 * 手机端只实现前四个：`auto`（走分类器）与 `bubble`（冒泡到父会话）在单会话手机上
 * 没有对应物，留成概念空位比硬造一个语义更诚实。
 */
enum class PermissionMode {
    /** 读免审，写/执行命令都要问。**手机端的默认值**。 */
    DEFAULT,

    /** 文件编辑自动放行，执行命令仍要问。 */
    ACCEPT_EDITS,

    /** 全部放行，不问。 */
    BYPASS_PERMISSIONS,

    /** 只读：任何写操作都直接拒绝（不是"问用户"，是根本不给做）。 */
    PLAN;

    /** 线上表示 —— 与 cc-haha 的 `PermissionMode` 字符串一致（注意是 camelCase）。 */
    fun toWire(): String = when (this) {
        DEFAULT -> "default"
        ACCEPT_EDITS -> "acceptEdits"
        BYPASS_PERMISSIONS -> "bypassPermissions"
        PLAN -> "plan"
    }

    companion object {
        /** 未知取值一律回落到 [DEFAULT] —— 宁可多问一次，也不要因为解析失败变成全放行。 */
        fun fromWire(value: String?): PermissionMode = when (value) {
            "acceptEdits" -> ACCEPT_EDITS
            "bypassPermissions" -> BYPASS_PERMISSIONS
            "plan" -> PLAN
            else -> DEFAULT
        }
    }
}

/** 一次审批请求的内容。字段与 cc-haha 的 `permission_request` 事件对应。 */
data class ApprovalRequest(
    val requestId: String,
    val toolName: String,
    val input: JsonObject,
    val description: String,
)

/** 审批结论。 */
sealed interface ApprovalDecision {
    data object Allowed : ApprovalDecision

    /** 用户拒绝。[message] 会作为 tool_result 回给模型，让它知道该换个做法。 */
    data class Denied(val message: String) : ApprovalDecision
}

/**
 * 审批网关 —— 把"要不要问"和"怎么问"分开。
 *
 * `:kernel` 只依赖这个接口，于是可以用假的网关在电脑上穷举所有路径
 * （允许 / 拒绝 / 超时），不必真的弹 UI。
 */
interface ApprovalGate {
    suspend fun request(request: ApprovalRequest): ApprovalDecision
}

/**
 * 权限管线：决定一个工具调用**能不能执行**。
 *
 * 决策顺序即优先级，四条规则：
 *
 * 1. **只读工具永远放行** —— 读文件也要点确认的话，用户会在第三次就放弃使用。
 * 2. **PLAN 模式拒绝一切写操作**，且不弹卡：这是"计划模式"，语义是"只看不做"，
 *    给个"是否允许"的选项反而自相矛盾。
 * 3. **BYPASS_PERMISSIONS 全放行**；**ACCEPT_EDITS 放行文件编辑但拦命令**。
 * 4. 其余情况**问用户**。
 *
 * 注意这里**不看工具自己怎么声称**（除 `isReadOnly`）：工具不该知道当前模式，
 * 那是管线的职责。cc-haha 也是这个分工（`Tool.checkPermissions` 只是候选，
 * 最终由权限层裁决）。
 */
class PermissionPipeline(
    private val gate: ApprovalGate,
    @Volatile var mode: PermissionMode = PermissionMode.DEFAULT,
) {

    suspend fun authorize(tool: Tool, input: JsonObject, requestId: String): ApprovalDecision {
        if (tool.isReadOnly) return ApprovalDecision.Allowed

        return when (mode) {
            PermissionMode.BYPASS_PERMISSIONS -> ApprovalDecision.Allowed

            PermissionMode.PLAN -> ApprovalDecision.Denied(
                "当前是计划模式（只读），不能执行 ${tool.name}。请先给出方案，由用户切换模式后再执行。",
            )

            PermissionMode.ACCEPT_EDITS ->
                if (isFileEdit(tool)) ApprovalDecision.Allowed else askUser(tool, input, requestId)

            PermissionMode.DEFAULT -> askUser(tool, input, requestId)
        }
    }

    private suspend fun askUser(
        tool: Tool,
        input: JsonObject,
        requestId: String,
    ): ApprovalDecision = gate.request(
        ApprovalRequest(
            requestId = requestId,
            toolName = tool.name,
            input = input,
            description = describe(tool, input),
        ),
    )

    /**
     * 哪些工具算"文件编辑"。
     *
     * 按名字而不是按某个标志位 —— 标志位会让工具自己声明"我很安全"，那是模型能间接
     * 影响的东西（提示词可以诱导它挑工具）。名字是固定的，改不了。
     */
    private fun isFileEdit(tool: Tool): Boolean =
        tool.name == "Write" || tool.name == "Edit"
}

/**
 * 把工具调用翻译成一句人话，给审批卡用。
 *
 * 用户看到的是 `Bash: rm -rf build/` 还是 `{"command":"rm -rf build/"}`，
 * 决定了他会不会认真看。原始 JSON 会让人条件反射地点"允许"。
 */
fun describe(tool: Tool, input: JsonObject): String {
    fun field(key: String): String? =
        runCatching { input[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } }
            .getOrNull()

    return when (tool.name) {
        "Write" -> "写入文件 ${field("file_path") ?: "?"}"
        "Edit" -> "修改文件 ${field("file_path") ?: "?"}"
        "Bash" -> "执行命令：${field("command")?.take(120) ?: "?"}"
        "Read" -> "读取 ${field("file_path") ?: "?"}"
        "Glob" -> "查找文件 ${field("pattern") ?: "?"}"
        "Grep" -> "搜索内容 /${field("pattern") ?: "?"}/"
        else -> "执行 ${tool.name}"
    }
}
