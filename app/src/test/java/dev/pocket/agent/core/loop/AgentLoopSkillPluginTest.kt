package dev.pocket.agent.core.loop

import dev.pocket.agent.core.json.Json
import dev.pocket.agent.core.permission.PermissionApprover
import dev.pocket.agent.core.permission.PermissionBroker
import dev.pocket.agent.core.permission.PermissionVerdict
import dev.pocket.agent.core.plugin.HookBus
import dev.pocket.agent.core.plugin.HookDecision
import dev.pocket.agent.core.plugin.PluginHost
import dev.pocket.agent.core.plugin.PluginManifest
import dev.pocket.agent.core.sandbox.PathJail
import dev.pocket.agent.core.sandbox.ProcessShell
import dev.pocket.agent.core.skill.Skill
import dev.pocket.agent.core.skill.SkillParser
import dev.pocket.agent.core.skill.SkillRegistry
import dev.pocket.agent.core.skill.SkillTool
import dev.pocket.agent.core.tool.ReadFileTool
import dev.pocket.agent.core.tool.ToolContext
import dev.pocket.agent.core.tool.ToolRegistry
import dev.pocket.agent.core.tool.WriteFileTool
import dev.pocket.agent.core.model.ReasoningEffort
import dev.pocket.agent.core.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Loop 6 端到端：Skills 白名单 + Plugins 钩子，走完整 AgentLoop。
 */
class AgentLoopSkillPluginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var ctx: ToolContext

    @Before
    fun setUp() {
        root = tmp.newFolder("ws")
        val jail = PathJail(root)
        ctx = ToolContext(jail, ProcessShell(root, timeoutMs = 10_000))
    }

    private fun skill(name: String, allowed: List<String> = emptyList()): Skill {
        val gate = if (allowed.isEmpty()) "" else "allowed-tools: ${allowed.joinToString(", ")}\n"
        return SkillParser.parse(
            "---\nname: $name\ndescription: $name skill\n${gate}---\nFOLLOW $name STEPS",
        )
    }

    private fun toolsAgainst(registry: ToolRegistry) = registry.specs().map { it.name }

    // ------------------------------------------------------------- Skills

    @Test
    fun `skill list is injected into the system prompt`() = runTest {
        val skills = SkillRegistry(listOf(skill("review")))
        val registry = ToolRegistry(listOf(ReadFileTool, SkillTool(skills)))
        val provider = ScriptedProvider(ScriptedProvider.text("ok"))
        val agent = loop(provider, registry, skills = skills)

        agent.run("hi").toList()

        val system = provider.requests.single().system
        assertTrue(system.contains("## Skills"))
        assertTrue(system.contains("review"))
    }

    @Test
    fun `activating a skill narrows the advertised tools on the next turn`() = runTest {
        val skills = SkillRegistry(listOf(skill("restrict", allowed = listOf("read"))))
        val registry = ToolRegistry(listOf(ReadFileTool, WriteFileTool, SkillTool(skills)))
        val provider = ScriptedProvider(
            ScriptedProvider.tools("skill" to Json.obj("name" to Json.Str("restrict"))),
            ScriptedProvider.text("done"),
        )
        val agent = loop(provider, registry, skills = skills)

        val events = agent.run("do it").toList()

        assertEquals("no tools should be denied on this happy path", 0, events.count { it is AgentEvent.ToolDenied })
        assertTrue(events.any { it is AgentEvent.ToolFinished })
        assertEquals("restrict", skills.active()!!.name)

        // 第一次请求暴露全部工具；第二次请求（技能已生效）只暴露 read + skill。
        assertEquals(3, toolsAgainst(registry).size)
        assertEquals(listOf("read", "skill"), provider.requests[1].tools.map { it.name }.sorted())
    }

    @Test
    fun `execution side gate denies a tool hidden by the active skill`() = runTest {
        val skills = SkillRegistry(listOf(skill("restrict", allowed = listOf("read"))))
        val registry = ToolRegistry(listOf(ReadFileTool, WriteFileTool, SkillTool(skills)))
        val provider = ScriptedProvider(
            ScriptedProvider.tools("skill" to Json.obj("name" to Json.Str("restrict"))),
            // 模型「凭记忆」调用被裁剪掉的 write —— 审批层永远不该看到这次调用。
            ScriptedProvider.tools("write" to Json.obj("path" to Json.Str("a.txt"), "content" to Json.Str("x"))),
            ScriptedProvider.text("stopped"),
        )
        val agent = loop(provider, registry, skills = skills, broker = PermissionBroker())

        val events = agent.run("go").toList()

        val denied = events.filterIsInstance<AgentEvent.ToolDenied>()
        assertEquals(1, denied.size)
        assertEquals("write", denied.single().name)
        assertTrue(denied.single().reason.contains("not available while skill"))

        // 没有任何写操作真的落盘。
        assertFalse(File(root, "a.txt").exists())
        // 拒绝结果作为错误块回填，历史仍然协议合法。
        assertEquals(null, agent.validatePairing(agent.history()))
    }

    @Test
    fun `clear releases the active skill so the gate lifts`() = runTest {
        val skills = SkillRegistry(listOf(skill("restrict", allowed = listOf("read"))))
        val registry = ToolRegistry(listOf(ReadFileTool, SkillTool(skills)))
        val provider = ScriptedProvider(
            ScriptedProvider.tools("skill" to Json.obj("name" to Json.Str("restrict"))),
            ScriptedProvider.text("one"),
        )
        val agent = loop(provider, registry, skills = skills)

        agent.run("go").toList()
        assertEquals("restrict", skills.active()!!.name)

        agent.clear()
        assertEquals(null, skills.active())
        assertTrue(skills.activeAllowedTools().isEmpty())
    }

    // ------------------------------------------------------------- Plugins / Hooks

    @Test
    fun `pre tool hook can veto a call before it is ever executed`() = runTest {
        val registry = ToolRegistry(listOf(ReadFileTool, WriteFileTool))
        val bus = HookBus()
        bus.onPreToolUse { event ->
            if (event.toolName == "write") HookDecision.deny("frozen workspace") else HookDecision.Allow
        }
        val provider = ScriptedProvider(
            ScriptedProvider.tools("write" to Json.obj("path" to Json.Str("a.txt"), "content" to Json.Str("x"))),
            ScriptedProvider.text("understood"),
        )
        val agent = loop(provider, registry, hookBus = bus)

        val events = agent.run("write it").toList()

        val denied = events.filterIsInstance<AgentEvent.ToolDenied>().single()
        assertTrue(denied.reason.contains("frozen workspace"))
        assertFalse("no ToolStarted may follow a veto", events.any { it is AgentEvent.ToolStarted })
        assertFalse(File(root, "a.txt").exists())
    }

    @Test
    fun `pre tool hook can rewrite the input that actually executes`() = runTest {
        val registry = ToolRegistry(listOf(ReadFileTool, WriteFileTool))
        val bus = HookBus()
        bus.onPreToolUse { event ->
            if (event.toolName == "write") {
                HookDecision.ReplaceInput(
                    Json.obj("path" to Json.Str("b.txt"), "content" to Json.Str("rewritten")),
                )
            } else {
                HookDecision.Allow
            }
        }
        val provider = ScriptedProvider(
            ScriptedProvider.tools("write" to Json.obj("path" to Json.Str("a.txt"), "content" to Json.Str("orig"))),
            ScriptedProvider.text("ok"),
        )
        val broker = PermissionBroker(PermissionApprover { PermissionVerdict.allowOnce() })
        val agent = loop(provider, registry, hookBus = bus, broker = broker)

        val events = agent.run("write it").toList()

        // 必须真的执行了，否则「a.txt 不存在」会因为「被审批拦下」而假绿。
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertFalse(finished.outcome.isError)
        val started = events.filterIsInstance<AgentEvent.ToolStarted>().single()
        assertEquals("重写后的入参被当作已审批对象执行", "b.txt", started.input["path"]?.asStringOrNull())
        assertFalse("original target must not be written", File(root, "a.txt").exists())
        assertEquals("rewritten", File(root, "b.txt").readText())
    }

    @Test
    fun `user prompt hook rewrite is what reaches the model and the history`() = runTest {
        val registry = ToolRegistry(listOf(ReadFileTool))
        val bus = HookBus()
        bus.onUserPromptSubmit { it.text.uppercase() }
        val provider = ScriptedProvider(ScriptedProvider.text("ok"))
        val agent = loop(provider, registry, hookBus = bus)

        agent.run("hello").toList()

        val sentUser = provider.requests.single().messages.last { it.role == Role.USER }
        assertEquals("HELLO", sentUser.text)
        assertEquals("HELLO", agent.history().first { it.role == Role.USER }.text)
    }

    @Test
    fun `session start fires exactly once across turns`() = runTest {
        val registry = ToolRegistry(listOf(ReadFileTool))
        val bus = HookBus()
        var started = 0
        bus.onSessionStart { started++ }
        val provider = ScriptedProvider(ScriptedProvider.text("ok"))
        val agent = loop(provider, registry, hookBus = bus)

        agent.run("one").toList()
        agent.run("two").toList()

        assertEquals(1, started)
    }

    @Test
    fun `plugin host supplies both the hook bus and its prompt section`() = runTest {
        val registry = ToolRegistry(listOf(ReadFileTool, WriteFileTool))
        val host = PluginHost()
        host.installManifest(
            PluginManifest.parse(
                Json.parse("""{"id":"guard","name":"Guard","tools":[{"name":"noop","command":"echo hi"}]}"""),
            )
        )
        host.bus.onPreToolUse { HookDecision.deny("guarded") }

        val provider = ScriptedProvider(
            ScriptedProvider.tools("write" to Json.obj("path" to Json.Str("a.txt"), "content" to Json.Str("x"))),
            ScriptedProvider.text("ok"),
        )
        // 不显式传 hookBus：应自动复用 pluginHost 的那一条。
        val agent = loop(provider, registry, pluginHost = host)

        val events = agent.run("go").toList()

        assertTrue(provider.requests.first().system.contains("## Plugins"))
        assertTrue(provider.requests.first().system.contains("noop"))
        assertEquals(1, events.count { it is AgentEvent.ToolDenied })
    }

    @Test
    fun `a plugin command tool runs end to end through the merged registry`() = runTest {
        val host = PluginHost()
        host.installManifest(
            PluginManifest.parse(
                Json.parse(
                    """{"id":"greeter","name":"Greeter","tools":[{"name":"greet","command":"echo hello {args}","readOnly":true}]}""",
                ),
            )
        )
        val registry = host.combineRegistry(ToolRegistry(listOf(ReadFileTool))).registry
        val provider = ScriptedProvider(
            ScriptedProvider.tools("greet" to Json.obj("args" to Json.Str("world"))),
            ScriptedProvider.text("done"),
        )
        val agent = loop(provider, registry, pluginHost = host)

        val events = agent.run("greet me").toList()

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("greet", finished.name)
        assertFalse(finished.outcome.isError)
        assertTrue(finished.outcome.content, finished.outcome.content.contains("hello world"))
        assertTrue(events.any { it is AgentEvent.Completed })
    }

    @Test
    fun `a throwing hook never breaks the turn`() = runTest {
        val registry = ToolRegistry(listOf(ReadFileTool, WriteFileTool))
        val bus = HookBus()
        bus.onPreToolUse { throw IllegalStateException("broken plugin") }
        val provider = ScriptedProvider(
            ScriptedProvider.tools("write" to Json.obj("path" to Json.Str("a.txt"), "content" to Json.Str("x"))),
            ScriptedProvider.text("ok"),
        )
        val broker = PermissionBroker(PermissionApprover { PermissionVerdict.allowOnce() })
        val agent = loop(provider, registry, hookBus = bus, broker = broker)

        val events = agent.run("go").toList()

        // fail-open：工具照常执行，但错误已被记下，可被 UI 呈现。
        assertTrue(events.any { it is AgentEvent.ToolFinished })
        assertEquals("x", File(root, "a.txt").readText())
        assertEquals(1, bus.peekErrors().size)
    }

    // ------------------------------------------------------------------ helper

    private fun loop(
        provider: ScriptedProvider,
        registry: ToolRegistry,
        broker: PermissionBroker = PermissionBroker(),
        skills: SkillRegistry? = null,
        pluginHost: PluginHost? = null,
        hookBus: HookBus? = null,
    ): AgentLoop = AgentLoop(
        provider = provider,
        registry = registry,
        toolContext = ctx,
        broker = broker,
        systemPrompt = "sys",
        skills = skills,
        pluginHost = pluginHost,
        hookBus = hookBus,
        model = "m",
        effort = ReasoningEffort.OFF,
    )
}
