package dev.pocket.agent.core.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PathJailTest {

    private lateinit var tmp: File
    private lateinit var root: File
    private lateinit var jail: PathJail

    @Before fun setUp() {
        tmp = Files.createTempDirectory("jail-test").toFile()
        root = File(tmp, "workspace").apply { mkdirs() }
        jail = PathJail(root)
    }

    @After fun tearDown() { tmp.deleteRecursively() }

    private fun expectViolation(path: String) {
        try {
            jail.resolve(path)
            fail("expected SandboxViolationException for <$path>")
        } catch (e: SandboxViolationException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test fun resolvesRelativePath() {
        assertEquals(File(root, "a/b.txt").path, jail.resolve("a/b.txt").path)
    }

    @Test fun leadingSlashMeansSandboxRoot() {
        assertEquals(File(root, "etc/passwd").path, jail.resolve("/etc/passwd").path)
        assertTrue(jail.resolve("/etc/passwd").path.startsWith(root.path))
    }

    @Test fun dotResolvesToRoot() {
        assertEquals(jail.root.path, jail.resolve(".").path)
        assertEquals(jail.root.path, jail.resolve("/").path)
    }

    @Test fun normalizesInnerDotDot() {
        assertEquals(File(root, "b.txt").path, jail.resolve("a/../b.txt").path)
        assertEquals(File(root, "b.txt").path, jail.resolve("./a/./../b.txt").path)
    }

    @Test fun rejectsEscapes() {
        expectViolation("../etc/passwd")
        expectViolation("a/../../x")
        expectViolation("..")
        expectViolation("a/b/../../../..")
        expectViolation("./../../root")
    }

    @Test fun rejectsEmptyAndNul() {
        expectViolation("")
        expectViolation("   ")
        expectViolation("a${0.toChar()}b")
    }

    @Test fun rejectsSymlinkEscape() {
        val outside = File(tmp, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("top secret")
        val link = File(root, "link")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            return // 平台不支持符号链接，跳过
        }
        expectViolation("link/secret.txt")
        expectViolation("link")
    }

    @Test fun allowsNonexistentNestedWriteTarget() {
        val f = jail.resolve("deep/nested/new.txt")
        assertFalse(f.exists())
        assertTrue(f.path.startsWith(root.path))
    }

    @Test fun containsAndRelative() {
        assertTrue(jail.contains(root))
        assertTrue(jail.contains(File(root, "x")))
        assertFalse(jail.contains(tmp))
        assertEquals("x/y", jail.relativeOf(jail.resolve("x/y")))
        assertEquals(".", jail.relativeOf(root))
    }

    @Test fun normalizePathCollapsesSegments() {
        assertEquals("/a/b", PathJail.normalizePath("/a/./b"))
        assertEquals("/b", PathJail.normalizePath("/a/../b"))
        assertEquals("/a/b", PathJail.normalizePath("a/b"))
        assertEquals("/../x", PathJail.normalizePath("/../x"))
    }
}
