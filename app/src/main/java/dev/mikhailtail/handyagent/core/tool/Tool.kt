package dev.mikhailtail.handyagent.core.tool

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.mobile.MobileBridge
import dev.mikhailtail.handyagent.core.mobile.MobileSessionState
import dev.mikhailtail.handyagent.core.model.ImageRef
import dev.mikhailtail.handyagent.core.model.ToolSpec
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import dev.mikhailtail.handyagent.core.sandbox.ProcessShell
import dev.mikhailtail.handyagent.core.sandbox.SandboxViolationException
import kotlinx.coroutines.CancellationException

/** 工具执行结果。isError=true 时内容会作为 tool_result 的错误块回填给模型。 */
data class ToolOutcome(
    val content: String,
    val isError: Boolean = false,
    /**
     * 工具产出的图片（例如 mobile_screenshot 的截图）。
     * 随 tool_result 一起回给模型，同时用于审批预览与时间轴展示。
     */
    val images: List<ImageRef> = emptyList(),
) {
    companion object {
        fun ok(content: String) = ToolOutcome(content)
        fun error(content: String) = ToolOutcome(content, isError = true)
        fun withImages(content: String, images: List<ImageRef>) =
            ToolOutcome(content, images = images)
    }
}

/** 工具运行所需的环境句柄；全部由平台层注入，工具本身零 android.* 依赖。 */
class ToolContext(
    val jail: PathJail,
    val shell: ProcessShell,
    val todos: TodoStore = TodoStore(),
    /** 设备操作能力；null = 当前平台不支持（桌面 / 测试环境）。 */
    val mobile: MobileBridge? = null,
    /** 会话内的 mobile 状态：最近一次界面快照、敏感标记、步数。 */
    val mobileState: MobileSessionState = MobileSessionState(),
)

/** 一个可被模型调用的工具。 */
interface Tool {
    val name: String
    val description: String
    val inputSchema: Json

    /** 只读工具可免审批直接执行。 */
    val readOnly: Boolean get() = false

    fun spec(): ToolSpec = ToolSpec(name, description, inputSchema, readOnly)

    suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome
}

/** 工具注册表：向 AgentLoop 提供 spec 列表，并按名字分发执行。 */
class ToolRegistry(tools: List<Tool> = emptyList()) {

    private val byName: Map<String, Tool>

    init {
        val duplicates = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate tool names: $duplicates" }
        byName = LinkedHashMap<String, Tool>().apply { tools.forEach { put(it.name, it) } }
    }

    val size: Int get() = byName.size

    fun names(): List<String> = byName.keys.toList()

    fun specs(): List<ToolSpec> = byName.values.map { it.spec() }

    fun get(name: String): Tool? = byName[name]

    fun register(tool: Tool): ToolRegistry = ToolRegistry(byName.values.toList() + tool)

    fun registerAll(extra: List<Tool>): ToolRegistry = ToolRegistry(byName.values.toList() + extra)

    /**
     * 分发执行。任何工具内部异常都会被转成 isError 结果，绝不让异常穿透到 AgentLoop ——
     * 模型需要看到错误文本才能自我纠正，而崩溃只会中断整轮对话。
     */
    suspend fun execute(name: String, input: Json, ctx: ToolContext): ToolOutcome {
        val tool = byName[name] ?: return ToolOutcome.error("unknown tool: $name")
        return try {
            tool.execute(input, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SandboxViolationException) {
            ToolOutcome.error("sandbox violation: ${e.message}")
        } catch (e: Throwable) {
            ToolOutcome.error("${e::class.simpleName ?: "error"}: ${e.message ?: "no message"}")
        }
    }

    companion object {
        /** 内置工具全集（不含 MCP / 插件动态工具）。 */
        fun builtin(): ToolRegistry = ToolRegistry(
            listOf(
                ReadFileTool,
                WriteFileTool,
                EditFileTool,
                ListDirTool,
                GlobTool,
                GrepTool,
                ShellTool,
                TodoTool,
            )
        )
    }
}

// ---------------------------------------------------------------------------
// JSON-Schema 构造助手：让各工具的 schema 声明保持紧凑、可读。
// ---------------------------------------------------------------------------

fun toolSchema(required: List<String>, properties: Map<String, Json>): Json = Json.obj(
    "type" to Json.Str("object"),
    "properties" to Json.obj(properties),
    "required" to Json.arrOfStrings(required),
)

fun stringProp(description: String): Json =
    Json.obj("type" to Json.Str("string"), "description" to Json.Str(description))

fun intProp(description: String): Json =
    Json.obj("type" to Json.Str("integer"), "description" to Json.Str(description))

fun boolProp(description: String): Json =
    Json.obj("type" to Json.Str("boolean"), "description" to Json.Str(description))

fun arrayProp(description: String, items: Json): Json = Json.obj(
    "type" to Json.Str("array"),
    "description" to Json.Str(description),
    "items" to items,
)
