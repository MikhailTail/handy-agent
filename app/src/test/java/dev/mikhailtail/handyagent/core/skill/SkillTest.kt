package dev.mikhailtail.handyagent.core.skill

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.sandbox.PathJail
import dev.mikhailtail.handyagent.core.sandbox.ProcessShell
import dev.mikhailtail.handyagent.core.tool.ToolContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx(): ToolContext = ToolContext(PathJail(tmp.root), ProcessShell(tmp.root))

    private fun doc(name: String = "review", body: String = "Do the review."): String = """
        |---
        |name: $name
        |description: Reviews a diff carefully.
        |---
        |$body
    """.trimMargin()

    // ------------------------------------------------------------------ 解析

    @Test
    fun `parses frontmatter and body`() {
        val skill = SkillParser.parse(doc(), source = "x.md")
        assertEquals("review", skill.name)
        assertEquals("Reviews a diff carefully.", skill.description)
        assertEquals("Do the review.", skill.instructions)
        assertEquals("x.md", skill.source)
        assertTrue(skill.allowedTools.isEmpty())
    }

    @Test
    fun `falls back to the file name when name is absent`() {
        val text = "---\ndescription: something\n---\nbody"
        assertEquals("mytool", SkillParser.parse(text, fallbackName = "mytool").name)
    }

    @Test
    fun `tolerates CRLF line endings`() {
        val text = "---\r\nname: a\r\ndescription: d\r\n---\r\nbody\r\n"
        assertEquals("a", SkillParser.parse(text).name)
    }

    @Test
    fun `ignores unknown frontmatter keys and comments`() {
        val text = """
            |---
            |# a comment
            |name: a
            |description: d
            |license: MIT
            |version: 2
            |---
            |body
        """.trimMargin()
        val skill = SkillParser.parse(text)
        assertEquals("a", skill.name)
        assertEquals("d", skill.description)
    }

    @Test
    fun `parses allowed-tools list in bracket and comma forms`() {
        val bracket = SkillParser.parse(
            "---\nname: a\ndescription: d\nallowed-tools: [read, grep, \"bash\"]\n---\nbody"
        )
        assertEquals(listOf("read", "grep", "bash"), bracket.allowedTools)

        val comma = SkillParser.parse(
            "---\nname: a\ndescription: d\nallowedTools: read,grep\n---\nbody"
        )
        assertEquals(listOf("read", "grep"), comma.allowedTools)
    }

    @Test
    fun `gatedTools always keeps the skill tool`() {
        val skill = SkillParser.parse("---\nname: a\ndescription: d\nallowed-tools: read\n---\nbody")
        assertEquals(setOf("read", "skill"), skill.gatedTools())
    }

    @Test
    fun `unrestricted skill reports an empty gate`() {
        val skill = SkillParser.parse(doc())
        assertTrue(skill.gatedTools().isEmpty())
    }

    @Test
    fun `rejects missing opener`() {
        val e = assertThrowsSkillFormat { SkillParser.parse("name: a\ndescription: d\nbody") }
        assertTrue(e.message!!.contains("frontmatter opener"))
    }

    @Test
    fun `rejects unclosed frontmatter`() {
        val e = assertThrowsSkillFormat { SkillParser.parse("---\nname: a\ndescription: d\nbody") }
        assertTrue(e.message!!.contains("never closed"))
    }

    @Test
    fun `rejects missing description`() {
        val e = assertThrowsSkillFormat { SkillParser.parse("---\nname: a\n---\nbody") }
        assertTrue(e.message!!.contains("no 'description'"))
    }

    @Test
    fun `rejects empty body`() {
        val e = assertThrowsSkillFormat { SkillParser.parse("---\nname: a\ndescription: d\n---\n   \n") }
        assertTrue(e.message!!.contains("empty body"))
    }

    @Test
    fun `rejects invalid names`() {
        val e = assertThrowsSkillFormat {
            SkillParser.parse("---\nname: has space\ndescription: d\n---\nbody")
        }
        assertTrue(e.message!!.contains("invalid skill name"))
    }

    // ------------------------------------------------------------------ 注册表

    @Test
    fun `registry tracks the single active skill`() {
        val a = SkillParser.parse(doc("a"))
        val b = SkillParser.parse(doc("b"))
        val reg = SkillRegistry(listOf(a, b))
        assertEquals(2, reg.size)
        assertNull(reg.active())

        assertTrue(reg.activate("b"))
        assertEquals("b", reg.active()!!.name)
        assertEquals(setOf<String>(), reg.activeAllowedTools())

        assertTrue(reg.activate("a"))
        assertEquals("a", reg.active()!!.name)

        reg.deactivate()
        assertNull(reg.active())
    }

    @Test
    fun `registry rejects duplicate names and unknown activation`() {
        val dup = listOf(SkillParser.parse(doc("a")), SkillParser.parse(doc("a")))
        try {
            SkillRegistry(dup)
            throw AssertionError("expected require() to reject duplicates")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Duplicate skill names"))
        }
        val reg = SkillRegistry(listOf(SkillParser.parse(doc("a"))))
        assertFalse(reg.activate("nope"))
    }

    @Test
    fun `promptSection is empty without skills and lists them otherwise`() {
        assertEquals("", SkillRegistry().promptSection())
        val reg = SkillRegistry(listOf(SkillParser.parse(doc("review"))))
        val section = reg.promptSection()
        assertTrue(section.startsWith("## Skills"))
        assertTrue(section.contains("review"))
        assertTrue(section.contains("Reviews a diff carefully."))
    }

    // ------------------------------------------------------------------ 工具

    @Test
    fun `skill tool loads instructions and activates the skill`() = runBlocking {
        val reg = SkillRegistry(listOf(SkillParser.parse(doc(), fallbackName = "review")))
        val tool = SkillTool(reg)
        assertEquals("skill", tool.name)
        assertTrue(tool.readOnly)

        val out = tool.execute(Json.obj("name" to Json.Str("review")), ctx())
        assertFalse(out.isError)
        assertTrue(out.content.contains("Do the review."))
        assertEquals("review", reg.active()!!.name)
    }

    @Test
    fun `skill tool reports the restricted toolset`() = runBlocking {
        val reg = SkillRegistry(
            listOf(SkillParser.parse("---\nname: a\ndescription: d\nallowed-tools: read, grep\n---\nbody"))
        )
        val out = SkillTool(reg).execute(Json.obj("name" to Json.Str("a")), ctx())
        assertTrue(out.content.contains("restricted to: read, grep, skill"))
        assertEquals(setOf("read", "grep", "skill"), reg.activeAllowedTools())
    }

    @Test
    fun `skill tool rejects unknown and missing names without activating`() = runBlocking {
        val reg = SkillRegistry(listOf(SkillParser.parse(doc("known"))))
        val tool = SkillTool(reg)

        val unknown = tool.execute(Json.obj("name" to Json.Str("ghost")), ctx())
        assertTrue(unknown.isError)
        assertTrue(unknown.content.contains("unknown skill 'ghost'"))
        assertTrue(unknown.content.contains("known"))
        assertNull(reg.active())

        val missing = tool.execute(Json.obj(), ctx())
        assertTrue(missing.isError)
        assertTrue(missing.content.contains("missing required field 'name'"))
        assertNull(reg.active())
    }

    @Test
    fun `skill tool names the empty set when nothing is installed`() = runBlocking {
        val out = SkillTool(SkillRegistry()).execute(Json.obj("name" to Json.Str("x")), ctx())
        assertTrue(out.content.contains("(none installed)"))
    }

    // ------------------------------------------------------------------ 发现

    private fun writeSkill(dirName: String, text: String): File {
        val dir = File(tmp.root, "${SkillLoader.SKILLS_DIR}/$dirName")
        dir.mkdirs()
        return File(dir, "SKILL.md").apply { writeText(text) }
    }

    @Test
    fun `discovery finds directory and flat markdown skills`() {
        writeSkill("review", doc("review"))
        File(tmp.root, "${SkillLoader.SKILLS_DIR}/notes.md").writeText(doc("notes"))

        val result = SkillLoader.discover(tmp.root)
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(listOf("notes", "review"), result.skills.map { it.name }.sorted())
    }

    @Test
    fun `discovery collects bad skills as errors without failing the good ones`() {
        writeSkill("good", doc("good"))
        writeSkill("bad", "no frontmatter here")
        File(tmp.root, "${SkillLoader.SKILLS_DIR}/empty").mkdirs() // 缺 SKILL.md

        val result = SkillLoader.discover(tmp.root)
        assertEquals(listOf("good"), result.skills.map { it.name })
        assertEquals(2, result.errors.size)
        assertTrue(result.errors.any { it.contains("missing SKILL.md") })
    }

    @Test
    fun `discovery keeps the first of duplicate names and reports it`() {
        writeSkill("one", doc("dup"))
        writeSkill("two", doc("dup"))
        val result = SkillLoader.discover(tmp.root)
        assertEquals(1, result.skills.size)
        assertEquals("dup", result.skills.single().name)
        assertTrue(result.errors.single().contains("duplicate skill name"))
    }

    @Test
    fun `missing skills directory is not an error`() {
        val result = SkillLoader.discover(tmp.root)
        assertTrue(result.skills.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `activated skill gate flows through the registry`() {
        val reg = SkillRegistry(
            listOf(SkillParser.parse("---\nname: a\ndescription: d\nallowed-tools: read\n---\nbody"))
        )
        reg.activate("a")
        assertEquals(setOf("read", "skill"), reg.activeAllowedTools())
    }

    // ------------------------------------------------------------------ helpers

    private fun assertThrowsSkillFormat(block: () -> Unit): SkillFormatException {
        try {
            block()
        } catch (e: SkillFormatException) {
            return e
        }
        throw AssertionError("expected SkillFormatException")
    }
}
