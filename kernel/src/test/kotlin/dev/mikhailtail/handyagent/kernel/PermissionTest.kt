package dev.mikhailtail.handyagent.kernel

import dev.mikhailtail.handyagent.kernel.api.Tool
import dev.mikhailtail.handyagent.kernel.api.ToolContext
import dev.mikhailtail.handyagent.kernel.api.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 权限管线的穷举测试。
 *
 * 这是阶段 3 最该被钉死的一层：功能坏了用户能看出来，护栏漏了没人会发现 ——
 * 直到某次误操作把不该动的东西动了。
 */
class PermissionTest {

    /** 记录被问过几次的假网关。 */
    private class RecordingGate(private val allow: Boolean = true) : ApprovalGate {
        val asked = mutableListOf<String>()
        override suspend fun request(request: ApprovalRequest): ApprovalDecision {
            asked += request.toolName
            return if (allow) ApprovalDecision.Allowed else ApprovalDecision.Denied("不要")
        }
    }

    private fun tool(name: String, readOnly: Boolean) = object : Tool {
        override val name = name
        override val description = ""
        override val inputSchema = JsonObject(emptyMap())
        override val isReadOnly = readOnly
        override suspend fun execute(input: JsonObject, ctx: ToolContext) = ToolResult(JsonPrimitive("ok"))
    }

    private val input = buildJsonObject { put("file_path", JsonPrimitive("/tmp/x")) }

    @Test
    fun `read-only tools never ask`() = runTest {
        val gate = RecordingGate()
        val pipeline = PermissionPipeline(gate, PermissionMode.DEFAULT)

        val decision = pipeline.authorize(tool("Read", readOnly = true), input, "r1")

        assertEquals(ApprovalDecision.Allowed, decision)
        assertTrue(gate.asked.isEmpty(), "只读工具不该弹出审批")
    }

    @Test
    fun `default mode asks for writes`() = runTest {
        val gate = RecordingGate()
        val pipeline = PermissionPipeline(gate, PermissionMode.DEFAULT)

        val decision = pipeline.authorize(tool("Write", readOnly = false), input, "r1")

        assertEquals(ApprovalDecision.Allowed, decision)
        assertEquals(listOf("Write"), gate.asked)
    }

    @Test
    fun `bypass mode never asks`() = runTest {
        val gate = RecordingGate()
        val pipeline = PermissionPipeline(gate, PermissionMode.BYPASS_PERMISSIONS)

        pipeline.authorize(tool("Bash", readOnly = false), input, "r1")

        assertTrue(gate.asked.isEmpty(), "全放行模式下不该弹卡")
    }

    /**
     * 计划模式下写操作**直接拒绝且不弹卡**。
     *
     * 不弹卡是刻意的：计划模式的语义是"只看不做"，给个"是否允许"的选项自相矛盾，
     * 而且会让用户以为点了就能写。
     */
    @Test
    fun `plan mode denies writes without asking`() = runTest {
        val gate = RecordingGate()
        val pipeline = PermissionPipeline(gate, PermissionMode.PLAN)

        val decision = pipeline.authorize(tool("Write", readOnly = false), input, "r1")

        assertTrue(decision is ApprovalDecision.Denied, "计划模式应拒绝写操作")
        assertTrue(gate.asked.isEmpty(), "计划模式不该弹卡")
    }

    /** 计划模式下读操作仍要放行 —— 否则它连"看"都做不到，没法给方案。 */
    @Test
    fun `plan mode still allows reads`() = runTest {
        val pipeline = PermissionPipeline(RecordingGate(), PermissionMode.PLAN)

        assertEquals(
            ApprovalDecision.Allowed,
            pipeline.authorize(tool("Read", readOnly = true), input, "r1"),
        )
    }

    /** acceptEdits：文件编辑放行，跑命令仍要问 —— 这才是"编辑"两个字的边界。 */
    @Test
    fun `accept edits allows file edits but still asks for commands`() = runTest {
        val gate = RecordingGate()
        val pipeline = PermissionPipeline(gate, PermissionMode.ACCEPT_EDITS)

        pipeline.authorize(tool("Write", readOnly = false), input, "r1")
        pipeline.authorize(tool("Edit", readOnly = false), input, "r2")
        pipeline.authorize(tool("Bash", readOnly = false), input, "r3")

        assertEquals(listOf("Bash"), gate.asked, "只该问命令，不该问文件编辑")
    }

    @Test
    fun `user denial propagates`() = runTest {
        val pipeline = PermissionPipeline(RecordingGate(allow = false), PermissionMode.DEFAULT)

        val decision = pipeline.authorize(tool("Bash", readOnly = false), input, "r1")

        assertTrue(decision is ApprovalDecision.Denied)
    }

    /** 未知的模式字符串必须回落到 default —— 解析失败变成"全放行"是最坏的失败方向。 */
    @Test
    fun `unknown mode strings fall back to default not bypass`() {
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromWire("whatever"))
        assertEquals(PermissionMode.DEFAULT, PermissionMode.fromWire(null))
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, PermissionMode.fromWire("bypassPermissions"))
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.fromWire("acceptEdits"))
    }

    /** 审批卡上的文案要能让人一眼看懂在干什么 —— 原始 JSON 会让人条件反射点允许。 */
    @Test
    fun `approval description is human readable`() {
        val bash = describe(
            tool("Bash", readOnly = false),
            buildJsonObject { put("command", JsonPrimitive("rm -rf build/")) },
        )
        assertTrue(bash.contains("rm -rf build/"), bash)

        val write = describe(
            tool("Write", readOnly = false),
            buildJsonObject { put("file_path", JsonPrimitive("/sdcard/a.txt")) },
        )
        assertTrue(write.contains("/sdcard/a.txt"), write)
    }
}
