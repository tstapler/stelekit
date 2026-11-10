package dev.stapler.stelekit.testing

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoldenFileGuardTest {
    private val dir = Files.createTempDirectory("golden-guard").toFile().also { it.deleteOnExit() }

    private fun golden(props: Map<String, String>, env: Map<String, String>) =
        GoldenFile(
            resourceDir = "home-sketch/closet",
            diskDir = { dir },
            property = { props[it] },
            env = { env[it] },
        )

    @Test
    fun `update together with CI fails with the forbidden message and writes nothing`() {
        val g = golden(mapOf("golden.update" to "true"), mapOf("CI" to "true"))
        val e = assertFailsWith<AssertionError> { g.assertMatches("g.txt", "x") }
        assertTrue("golden update forbidden in CI" in e.message.orEmpty(), e.message)
        assertFalse(dir.resolve("g.txt").exists())
    }

    @Test
    fun `update without CI writes the golden to disk`() {
        golden(mapOf("golden.update" to "true"), emptyMap()).assertMatches("g.txt", "new content")
        assertEquals("new content", dir.resolve("g.txt").readText())
    }

    @Test
    fun `without update a missing golden fails`() {
        assertFailsWith<AssertionError> { golden(emptyMap(), emptyMap()).assertMatches("absent-golden.txt", "x") }
    }

    @Test
    fun `without update a stored golden is compared`() {
        val g = golden(emptyMap(), emptyMap())
        g.assertMatches("fixture-manifest.json", HomeSketchFixtureVisibilityTestSupport.manifestText())
        assertFailsWith<AssertionError> { g.assertMatches("fixture-manifest.json", "different") }
    }
}

/** Reads the committed manifest from the classpath, the same bytes the golden comparison sees. */
object HomeSketchFixtureVisibilityTestSupport {
    fun manifestText(): String =
        checkNotNull(HomeSketchFixtureVisibilityTestSupport::class.java.classLoader
            .getResourceAsStream("home-sketch/closet/fixture-manifest.json")) { "manifest missing" }
            .bufferedReader().use { it.readText() }
}
