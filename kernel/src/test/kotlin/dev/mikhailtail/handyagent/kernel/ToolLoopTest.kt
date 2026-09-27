package dev.mikhailtail.handyagent.kernel

import dev.mikhailtail.handyagent.kernel.api.FileHost
import dev.mikhailtail.handyagent.kernel.api.LlmClient
import dev.mikhailtail.handyagent.kernel.api.LlmEvent
import dev.mikhailtail.handyagent.kernel.api.LlmRequest
import dev.mikhailtail.handyagent.kernel.api.ShellHost
import dev.mikhailtail.handyagent.kernel.api.ShellResult
import dev.mikhailtail.handyagent.kernel.api.Tool
import dev.mikhailtail.handyagent.kernel.api.ToolContext
import dev.mikhailtail.handyagent.kernel.api.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 多轮工具循环 + 审批护栏。
 *
 * **本文件里最重要的断言是「审批通过前，副作用一次都没发生」** ——
 * 功能坏了用户看得见，护栏漏了没人会发现，直到某次误操作动了不该动的东西。
 */
class ToolLoopTest {

    /** 记录每次写入的假文件系统。 */
    private class RecordingFiles : FileHost {
        val writes = mutableListOf<String>()
        private val store = mutableMapOf<String, String>()

        override fun readText(path: String) = store[path] ?: ""
        override fun writeText(path: String, text: String) {
            writes += path
            store[path] = text
        }
        override fun exists(path: String) = store.containsKey(path)
        override fun isDirectory(path: String) = false
        override fun list(path: String) = emptyList<String>()
        override fun delete(path: String) { store.remove(path) }
        override fun glob(pattern: String, root: String) = emptyList<String>()
    }

    private class NoopShell : ShellHost {
        val ran = mutableListOf<String>()
        override suspend fun run(command: String, workDir: String, timeoutMs: Long): ShellResult {
            ran += command
            return ShellResult("", "", 0)
        }
    }

    private class Ctx(
        override val files: RecordingFiles,
        override val shell: NoopShell,
    ) : ToolContext {
        override val workDir = "/work"
    }

    private fun writeTool() = object : Tool {
        override val name = "Write"
        override val description = ""
        override val inputSchema = JsonObject(emptyMap())
        override val isReadOnly = false
        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val path = input["file_path"]!!.let { (it as JsonPrimitive).content }
            ctx.files.writeText(path, "written")
            return ToolResult(JsonPrimitive("已写入 $path"))
        }
    }

    private fun readTool() = object : Tool {
        override val name = "Read"
        override val description = ""
        override val inputSchema = JsonObject(emptyMap())
        override val isReadOnly = true
        override suspend fun execute(input: JsonObject, ctx: ToolContext) = ToolResult(JsonPrimitive("内容"))
    }

    /** 第一轮要求调用工具，第二轮收尾 —— 模拟真实模型看着结果收敛的行为。 */
    private class TwoTurnLlm(
        private val toolName: String,
        private val toolInput: String,
        private val toolId: String = "call_1",
    ) : LlmClient {
        private var call = 0
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            if (call++ == 0) {
                emit(LlmEvent.ToolUseStart(toolId, toolName))
                emit(LlmEvent.ToolInputDelta(toolInput))
                emit(LlmEvent.MessageStop("tool_use", null))
            } else {
                emit(LlmEvent.TextDelta("做完了"))
                emit(LlmEvent.MessageStop("end_turn", null))
            }
        }
    }

    private fun req() = LlmRequest(model = "test", system = null, messages = emptyList())

    /**
     * 护栏的核心不变量。
     *
     * 用户拒绝之后，**磁盘上不能有任何痕迹**。这条比"返回了错误消息"重要得多 ——
     * 界面上显示"已拒绝"而文件其实被写了，是最坏的情况。
     */
    @Test
    fun `denied approval leaves zero side effects`() = runTest {
        val files = RecordingFiles()
        val gate = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest) = ApprovalDecision.Denied("不")
        }
        val engine = QueryEngine(
            llm = TwoTurnLlm("Write", """{"file_path":"/work/a.txt"}"""),
            tools = listOf(writeTool()),
            permissions = PermissionPipeline(gate, PermissionMode.DEFAULT),
            toolContext = Ctx(files, NoopShell()),
        )

        val events = engine.run(req()).toList()

        assertTrue(files.writes.isEmpty(), "拒绝后不该有任何写入，实际写了：${files.writes}")
        val finished = events.filterIsInstance<EngineEvent.ToolFinished>().single()
        assertTrue(finished.isError, "被拒绝的调用应标记为错误")
        // 拒绝也必须回 tool_result，否则模型会以为工具卡住而反复重试。
        assertTrue(finished.output.contains("拒绝"), finished.output)
    }

    @Test
    fun `allowed approval performs the write`() = runTest {
        val files = RecordingFiles()
        val gate = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest) = ApprovalDecision.Allowed
        }
        val engine = QueryEngine(
            llm = TwoTurnLlm("Write", """{"file_path":"/work/a.txt"}"""),
            tools = listOf(writeTool()),
            permissions = PermissionPipeline(gate, PermissionMode.DEFAULT),
            toolContext = Ctx(files, NoopShell()),
        )

        engine.run(req()).toList()

        assertEquals(listOf("/work/a.txt"), files.writes)
    }

    /** 只读工具不该打断流程去弹卡 —— 这条在端到端里表现为"读文件不弹窗"。 */
    @Test
    fun `read-only tools run without approval`() = runTest {
        var asked = 0
        val gate = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest): ApprovalDecision {
                asked++
                return ApprovalDecision.Allowed
            }
        }
        val engine = QueryEngine(
            llm = TwoTurnLlm("Read", """{"file_path":"/work/a.txt"}"""),
            tools = listOf(readTool()),
            permissions = PermissionPipeline(gate, PermissionMode.DEFAULT),
            toolContext = Ctx(RecordingFiles(), NoopShell()),
        )

        engine.run(req()).toList()

        assertEquals(0, asked, "只读工具不该触发审批")
    }

    /**
     * 多轮：第一轮调工具、第二轮收尾，两轮的 assistant 消息都要产出。
     *
     * 这条验证 `while` 真的在转 —— 若退出信号写错（比如拿 stop_reason 判断），
     * 第一轮之后就会停下，界面上表现为"模型说要调工具然后就没下文了"。
     */
    @Test
    fun `loop continues until a turn has no tool calls`() = runTest {
        val files = RecordingFiles()
        val gate = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest) = ApprovalDecision.Allowed
        }
        val engine = QueryEngine(
            llm = TwoTurnLlm("Write", """{"file_path":"/work/a.txt"}"""),
            tools = listOf(writeTool()),
            permissions = PermissionPipeline(gate, PermissionMode.DEFAULT),
            toolContext = Ctx(files, NoopShell()),
        )

        val events = engine.run(req()).toList()

        assertEquals(2, events.filterIsInstance<EngineEvent.TurnComplete>().size, "应有两轮")
        assertEquals(1, events.filterIsInstance<EngineEvent.ToolFinished>().size)
        val run = events.filterIsInstance<EngineEvent.RunComplete>().single()
        assertEquals(2, run.turns)
        assertEquals(listOf("做完了"), events.filterIsInstance<EngineEvent.TextDelta>().map { it.text })
    }

    /** 未注册的工具名要变成可读错误，而不是崩掉整轮。 */
    @Test
    fun `unknown tool name yields an error result not a crash`() = runTest {
        val engine = QueryEngine(
            llm = TwoTurnLlm("Nope", """{}"""),
            tools = listOf(writeTool()),
            permissions = PermissionPipeline(
                object : ApprovalGate {
                    override suspend fun request(request: ApprovalRequest) = ApprovalDecision.Allowed
                },
                PermissionMode.DEFAULT,
            ),
            toolContext = Ctx(RecordingFiles(), NoopShell()),
        )

        val events = engine.run(req()).toList()

        val finished = events.filterIsInstance<EngineEvent.ToolFinished>().single()
        assertTrue(finished.isError && finished.output.contains("未知工具"), finished.output)
    }

    /** 计划模式下写操作被拒，且**不弹卡**。 */
    @Test
    fun `plan mode blocks writes without prompting`() = runTest {
        val files = RecordingFiles()
        var asked = 0
        val gate = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest): ApprovalDecision {
                asked++
                return ApprovalDecision.Allowed
            }
        }
        val engine = QueryEngine(
            llm = TwoTurnLlm("Write", """{"file_path":"/work/a.txt"}"""),
            tools = listOf(writeTool()),
            permissions = PermissionPipeline(gate, PermissionMode.PLAN),
            toolContext = Ctx(files, NoopShell()),
        )

        engine.run(req()).toList()

        assertTrue(files.writes.isEmpty(), "计划模式不该真的写")
        assertEquals(0, asked, "计划模式不该弹卡（那会让人以为点了就能写）")
    }
}
