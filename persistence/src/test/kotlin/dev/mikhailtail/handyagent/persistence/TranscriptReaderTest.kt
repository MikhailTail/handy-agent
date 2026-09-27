package dev.mikhailtail.handyagent.persistence

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用**真实转录**做的对照测试。
 *
 * 数据取自本机 cc-haha 的 `projects/`（那是用户自己的历史，字段形态最真实）。
 * 预期值不是抄代码得来的，是拿活的 cc-haha 服务（`http://127.0.0.1:58104`）
 * 对同一会话的 `/api/sessions/:id/messages` 响应比对出来的。
 *
 * 找不到 fixture 时**跳过而不是失败** —— 这些是外部数据，不该让构建依赖它们的存在。
 */
class TranscriptReaderTest {

    private val projectsDir = File("D:/cc-haha/projects")

    private fun fixture(project: String, sessionId: String): File? =
        File(projectsDir, "$project/$sessionId.jsonl").takeIf { it.isFile }

    /**
     * 最简单的会话：只有文本对话，6 条消息。
     * 这条对照验证了「非消息行被过滤」和「一行一条目」。
     */
    @Test
    fun `plain conversation maps line for line`() {
        val file = fixture("C--Users-29159", "573cac91-9337-461c-9c12-e82cd5ea65f7")
            ?: return

        val messages = TranscriptReader().readMessages(file)

        assertEquals(6, messages.size, "该会话 JSONL 里有 6 条对话行")
        assertEquals(
            listOf("user", "assistant", "assistant", "user", "assistant", "assistant"),
            messages.map { it.type },
        )
    }

    /**
     * 含工具调用的会话 —— 这条才是关键：它验证 `tool_use` / `tool_result` 的归类。
     *
     * 实测结论是**只改名不拆分**：JSONL 里 `assistant` 行（content 为 tool_use block）
     * 在 API 上变成 `type: "tool_use"`，`uuid` 原样成为 `id`。
     */
    @Test
    fun `tool blocks are reclassified not split`() {
        val file = fixture("D--cc-haha-test-project-1", "1c589656-ee30-4f7e-b3b2-f113eef91530")
            ?: return

        val messages = TranscriptReader().readMessages(file)

        assertTrue(messages.size > 6, "该会话含工具调用，条目应更多，实际 ${messages.size}")

        // 前 9 条的形态已与 cc-haha 服务实测对齐。
        assertEquals(
            listOf(
                "user", "assistant", "assistant",
                "tool_use", "tool_use", "tool_use",
                "tool_result", "tool_result", "tool_result",
            ),
            messages.take(9).map { it.type },
        )

        // parentUuid 链必须原样保留 —— 前端靠它把条目接成对话树。
        val toolUse = messages.first { it.type == "tool_use" }
        assertTrue(toolUse.parentUuid != null, "tool_use 条目应带 parentUuid")
        assertTrue(toolUse.content.toString().contains("tool_use"), "content 应原样保留 block")
    }

    /** 坏行不能废掉整个会话：半行写入（进程被杀）在真实数据里很常见。 */
    @Test
    fun `malformed lines are skipped without losing the rest`() {
        val dir = Files.createTempDirectory("transcript").toFile()
        val file = File(dir, "s.jsonl")
        file.writeText(
            """
            {"type":"user","uuid":"a","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}
            {"type":"user","uuid":"broken",,,
            {"type":"assistant","uuid":"b","message":{"role":"assistant","content":[{"type":"text","text":"yo"}]}}
            """.trimIndent(),
        )

        val messages = TranscriptReader().readMessages(file)

        assertEquals(2, messages.size, "坏行应被跳过，前后两条仍要读到")
        assertEquals(listOf("a", "b"), messages.map { it.id })
    }

    /** 会话扫描：字段来自真实转录，条数与标题都应可复现。 */
    @Test
    fun `scanner produces list items from real transcripts`() {
        if (!projectsDir.isDirectory) return

        val sessions = SessionScanner(projectsDir).listSessions()

        assertTrue(sessions.isNotEmpty(), "本机应有会话数据")

        val one = sessions.first { it.id == "573cac91-9337-461c-9c12-e82cd5ea65f7" } as Any
        val item = sessions.first { it.id == "573cac91-9337-461c-9c12-e82cd5ea65f7" }

        assertEquals(6, item.messageCount)
        assertEquals("C--Users-29159", item.projectPath)
        // projectRoot 取自消息行自带的 cwd，而不是反解目录名。
        assertEquals("C:\\Users\\29159", item.projectRoot)
        assertTrue(item.title.isNotBlank(), "标题应来自 ai-title 行")
    }
}
