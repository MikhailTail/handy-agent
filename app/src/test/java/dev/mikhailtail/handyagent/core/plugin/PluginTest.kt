package dev.mikhailtail.handyagent.core.plugin

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import dev.mikhailtail.handyagent.core.sandbox.ProcessShell
import dev.mikhailtail.handyagent.core.tool.ToolContext
import dev.mikhailtail.handyagent.core.tool.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx(): ToolContext = ToolContext(PathJail(tmp.root), ProcessShell(tmp.root, timeoutMs = 10_000))

    private fun manifestJson(id: String = "demo", name: String = "Demo", tools: String = "[]"): String = """
        |{"id": "$id", "name": "$name", "version": "1.2.3", "description": "d", "tools": $tools}
    """.trimMargin()

    // ------------------------------------------------------------------ 清单解析

    @Test
    fun `parses a valid manifest`() {
        val json = Json.parse(manifestJson(tools = """[{"name":"say","command":"echo {args}","description":"echo"}]"""))
        val m = PluginManifest.parse(json, source = "plugin.json")
        assertEquals("demo", m.id)
        assertEquals("Demo", m.name)
        assertEquals("1.2.3", m.version)
        assertEquals(1, m.tools.size)
        val tool = m.tools.single()
        assertEquals("say", tool.name)
        assertEquals("echo {args}", tool.command)
        assertEquals("echo", tool.description)
        assertFalse(tool.readOnly)
        assertNull(tool.timeoutMs)
    }

    @Test
    fun `name and id fall back to each other`() {
        assertEquals("solo", PluginManifest.parse(Json.parse("""{"id":"solo"}""")).name)
        assertEquals("solo", PluginManifest.parse(Json.parse("""{"name":"solo"}""")).id)
    }

    @Test
    fun `version defaults when omitted`() {
        assertEquals("0.0.0", PluginManifest.parse(Json.parse("""{"id":"a"}""")).version)
    }

    @Test
    fun `rejects a non object manifest`() {
        val e = assertPluginFormat { PluginManifest.parse(Json.parse("[]")) }
        assertTrue(e.message!!.contains("must be a JSON object"))
    }

    @Test
    fun `rejects a manifest with neither id nor name`() {
        val e = assertPluginFormat { PluginManifest.parse(Json.parse("""{"version":"1"}""")) }
        assertTrue(e.message!!.contains("needs 'id' or 'name'"))
    }

    @Test
    fun `rejects an invalid plugin id`() {
        val e = assertPluginFormat { PluginManifest.parse(Json.parse("""{"id":"bad id!"}""")) }
        assertTrue(e.message!!.contains("invalid plugin id"))
    }

    @Test
    fun `rejects a tool without a name or command`() {
        val noName = assertPluginFormat {
            PluginManifest.parse(Json.parse(manifestJson(tools = """[{"command":"echo"}]""")))
        }
        assertTrue(noName.message!!.contains("has no 'name'"))

        val noCommand = assertPluginFormat {
            PluginManifest.parse(Json.parse(manifestJson(tools = """[{"name":"say"}]""")))
        }
        assertTrue(noCommand.message!!.contains("has no 'command'"))
    }

    @Test
    fun `rejects a non positive timeout`() {
        val e = assertPluginFormat {
            PluginManifest.parse(Json.parse(manifestJson(tools = """[{"name":"say","command":"echo","timeoutMs":0}]""")))
        }
        assertTrue(e.message!!.contains("non-positive timeoutMs"))
    }

    @Test
    fun `rejects duplicate tool names within one plugin`() {
        val e = assertPluginFormat {
            PluginManifest.parse(
                Json.parse(manifestJson(tools = """[{"name":"say","command":"a"},{"name":"say","command":"b"}]"""))
            )
        }
        assertTrue(e.message!!.contains("duplicate tool(s): say"))
    }

    // ------------------------------------------------------------------ 命令工具

    @Test
    fun `command tool substitutes args and named fields`() {
        val decl = PluginToolDecl(
            name = "say",
            description = "",
            command = "echo {args} from {who}",
        )
        val tool = CommandTool("demo", decl)
        assertEquals("say", tool.name)
        assertFalse(tool.readOnly)

        var seen = ""
        val ctx = ctx()
        runBlocking {
            // 用真实 shell 验证替换确实落到了命令行里。
            seen = tool.execute(Json.obj("args" to Json.Str("hello"), "who" to Json.Str("alice")), ctx).content
        }
        assertTrue(seen, seen.contains("hello from alice"))
    }

    @Test
    fun `command tool renders a blank template as an error`() = runBlocking {
        val tool = CommandTool("demo", PluginToolDecl("noop", "", "   "))
        val out = tool.execute(Json.obj(), ctx())
        assertTrue(out.isError)
        assertTrue(out.content.contains("empty command"))
    }

    @Test
    fun `command tool surfaces a non zero exit as an error`() = runBlocking {
        val tool = CommandTool("demo", PluginToolDecl("boom", "", "exit 3"))
        val out = tool.execute(Json.obj(), ctx())
        assertTrue(out.isError)
        assertTrue(out.content.contains("exit=3"))
    }

    @Test
    fun `command plugin maps every declaration to a tool`() {
        val m = PluginManifest.parse(
            Json.parse(manifestJson(tools = """[{"name":"a","command":"echo a"},{"name":"b","command":"echo b"}]"""))
        )
        assertEquals(listOf("a", "b"), CommandPlugin(m).tools().map { it.name })
    }

    // ------------------------------------------------------------------ 宿主

    @Test
    fun `host installs, rejects duplicates, and uninstalls`() {
        val host = PluginHost()
        val plugin = CommandPlugin(PluginManifest.parse(Json.parse(manifestJson())))
        assertTrue(host.install(plugin))
        assertEquals(1, host.size)
        assertFalse("same id must not overwrite a live plugin", host.install(plugin))
        assertEquals(1, host.size)

        assertTrue(host.uninstall("demo"))
        assertEquals(0, host.size)
        assertFalse(host.uninstall("demo"))
    }

    @Test
    fun `host prompt section is empty until something is installed`() {
        val host = PluginHost()
        assertEquals("", host.promptSection())
        host.installManifest(
            PluginManifest.parse(Json.parse(manifestJson(tools = """[{"name":"say","command":"echo"}]""")))
        )
        val section = host.promptSection()
        assertTrue(section.startsWith("## Plugins"))
        assertTrue(section.contains("Demo (demo v1.2.3)"))
        assertTrue(section.contains("[tools: say]"))
    }

    @Test
    fun `install hooks are wired into the shared bus`() = runBlocking {
        val bus = HookBus()
        val host = PluginHost(bus)
        val plugin = object : Plugin {
            override val manifest = PluginManifest.parse(Json.parse(manifestJson(id = "hooks")))
            override fun install(bus: HookBus) {
                bus.onPreToolUse { HookDecision.deny("vetoed by plugin") }
            }
        }
        host.install(plugin)
        assertTrue(bus.hasPreToolUse)
        val result = bus.preToolUse("bash", Json.obj())
        assertTrue(result.denied)
        assertEquals("vetoed by plugin", result.reason)
    }

    @Test
    fun `combining with the base registry skips name conflicts`() {
        val host = PluginHost()
        host.installManifest(
            PluginManifest.parse(Json.parse(manifestJson(tools = """[{"name":"read","command":"echo x"},{"name":"fresh","command":"echo y"}]""")))
        )
        val base = ToolRegistry.builtin()
        val merge = host.combineRegistry(base)

        assertEquals(listOf("read"), merge.skipped)
        assertTrue(merge.registry.names().contains("fresh"))
        assertEquals(9, merge.registry.size)
        assertFalse(merge.registry.get("read") is CommandTool)
    }

    // ------------------------------------------------------------------ 发现

    private fun writePlugin(dir: String, body: String?) {
        val d = File(tmp.root, "${PluginLoader.PLUGINS_DIR}/$dir").apply { mkdirs() }
        if (body != null) File(d, PluginLoader.MANIFEST_NAME).writeText(body)
    }

    @Test
    fun `discovery loads valid plugins and reports each failure kind`() {
        writePlugin("good", manifestJson(id = "good", name = "Good"))
        writePlugin("badjson", "{ not json")
        writePlugin("badmanifest", """{"id":"bad id"}""")
        writePlugin("nofile", null)

        val result = PluginLoader.discover(tmp.root)
        assertEquals(listOf("good"), result.manifests.map { it.id })
        assertEquals(3, result.errors.size)
        assertTrue(result.errors.any { it.contains("invalid JSON") })
        assertTrue(result.errors.any { it.contains("missing plugin.json") })
    }

    @Test
    fun `discovery keeps the first of duplicate ids`() {
        writePlugin("one", manifestJson(id = "dup", name = "One"))
        writePlugin("two", manifestJson(id = "dup", name = "Two"))
        val result = PluginLoader.discover(tmp.root)
        assertEquals(1, result.manifests.size)
        assertEquals("One", result.manifests.single().name)
        assertTrue(result.errors.single().contains("duplicate plugin id"))
    }

    @Test
    fun `discovery flags a tool declared by two plugins but keeps both`() {
        writePlugin("a", manifestJson(id = "a", tools = """[{"name":"shared","command":"echo a"}]"""))
        writePlugin("b", manifestJson(id = "b", tools = """[{"name":"shared","command":"echo b"}]"""))
        val result = PluginLoader.discover(tmp.root)
        assertEquals(2, result.manifests.size)
        assertTrue(result.errors.single().contains("declared by both"))
    }

    @Test
    fun `missing plugins directory is not an error`() {
        val result = PluginLoader.discover(tmp.root)
        assertTrue(result.manifests.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `host loadFrom installs discovered manifests`() {
        writePlugin("good", manifestJson(id = "good", tools = """[{"name":"say","command":"echo hi"}]"""))
        val host = PluginHost()
        val errors = host.loadFrom(tmp.root)
        assertTrue(errors.isEmpty())
        assertEquals(listOf("good"), host.manifests().map { it.id })
        assertEquals(listOf("say"), host.tools().map { it.name })
    }

    // ------------------------------------------------------------------ helpers

    private fun assertPluginFormat(block: () -> Unit): PluginFormatException {
        try {
            block()
        } catch (e: PluginFormatException) {
            return e
        }
        throw AssertionError("expected PluginFormatException")
    }
}
