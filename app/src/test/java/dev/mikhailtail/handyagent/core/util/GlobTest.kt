package dev.mikhailtail.handyagent.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobTest {

    @Test
    fun `single star does not cross directory boundaries`() {
        assertTrue(Glob.matches("src/*.kt", "src/A.kt"))
        assertFalse(Glob.matches("src/*.kt", "src/sub/A.kt"))
    }

    @Test
    fun `double star crosses directories and may match zero`() {
        assertTrue(Glob.matches("**/*.kt", "a/b/c.kt"))
        assertTrue(Glob.matches("**/*.kt", "c.kt"))
        assertFalse(Glob.matches("**/*.kt", "c.md"))
    }

    @Test
    fun `trailing double star matches the whole subtree`() {
        assertTrue(Glob.matches("src/**", "src/a/b.kt"))
        assertTrue(Glob.matches("src/**", "src/a"))
    }

    @Test
    fun `pattern without slash also matches the basename`() {
        assertTrue(Glob.matches("*.kt", "a/b/c.kt"))
        assertFalse(Glob.matches("*.kt", "a/b/c.md"))
    }

    @Test
    fun `question mark matches exactly one non-slash char`() {
        assertTrue(Glob.matches("a?.txt", "ab.txt"))
        assertFalse(Glob.matches("a?.txt", "abc.txt"))
        assertFalse(Glob.matches("a?c.txt", "a/c.txt"))
    }

    @Test
    fun `regex metacharacters are escaped`() {
        assertTrue(Glob.matches("a.txt", "a.txt"))
        assertFalse(Glob.matches("a.txt", "axtxt"))
        assertTrue(Glob.matches("a+b", "a+b"))
    }

    @Test
    fun `leading dot-slash is tolerated`() {
        assertTrue(Glob.matches("*.md", "./README.md"))
    }

    @Test
    fun `mid-pattern double star spans directories`() {
        assertTrue(Glob.matches("src/**/Test.kt", "src/a/b/Test.kt"))
        assertTrue(Glob.matches("src/**/Test.kt", "src/Test.kt"))
    }
}
