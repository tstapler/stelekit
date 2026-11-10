package dev.stapler.stelekit

import dev.stapler.stelekit.wiring.WiringInputs
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 0.2: the structural private-string patterns (same file the CI script `private-string-guard.py`
 * reads) pass the committed fixtures and FAIL planted strings in a temp copy. Planted strings are invented.
 * The committed-tree scan of the project_plans and docs trees is the CI script step, not a Bazel test
 * (those paths are not in the sandbox).
 */
class PrivateStringGuardTest {
    private class Scanner(patternsText: String, allowText: String, private val denylist: List<String> = emptyList()) {
        private val patterns = patternsText.lines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .map { line ->
                val (name, rx) = line.split(" = ", limit = 2)
                name.trim() to Regex(rx.trim())
            }
        private val allowLines = allowText.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        private val allowExact = allowLines.filterNot { it.startsWith("re:") }.toSet()
        private val allowRegex = allowLines.filter { it.startsWith("re:") }.map { Regex(it.removePrefix("re:")) }

        fun findings(text: String): List<String> = text.lines().flatMap { line ->
            val structural = patterns.flatMap { (name, rx) ->
                rx.findAll(line).filter { m -> m.value !in allowExact && allowRegex.none { it.matches(m.value) } }.map { name }.toList()
            }
            val local = denylist.filter { it in line.lowercase() }.map { "denylist" }
            structural + local
        }
    }

    private val scanner = Scanner(
        WiringInputs.dataFile("private-string-patterns.txt"),
        WiringInputs.dataFile("private-string-allow.txt"),
    )

    private fun fixtureText(path: String): String =
        checkNotNull(PrivateStringGuardTest::class.java.classLoader.getResourceAsStream("home-sketch/closet/$path")) {
            "fixture $path missing from the test classpath"
        }.bufferedReader().use { it.readText() }

    private fun manifestFiles(): List<String> =
        Regex("\"files\"\\s*:\\s*\\[([^\\]]*)]").find(fixtureText("fixture-manifest.json"))!!
            .groupValues[1].split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }

    @Test
    fun `committed classpath fixtures pass the structural patterns`() {
        val files = manifestFiles()
        assertTrue(files.isNotEmpty(), "manifest lists no files")
        for (f in files) assertEquals(emptyList(), scanner.findings(fixtureText(f)), "findings in fixture $f")
    }

    @Test
    fun `dimension keys in the registry match the key pattern when a registry exists`() {
        val registry = PrivateStringGuardTest::class.java.classLoader.getResourceAsStream("home-sketch/closet/dimension-map.json")
            ?.bufferedReader()?.use { it.readText() } ?: return // added by Story 1.5a; nothing to check before then
        val keyPattern = Regex(Regex("\"dimensionKeyPattern\"\\s*:\\s*\"([^\"]+)\"").find(fixtureText("fixture-manifest.json"))!!.groupValues[1].replace("\\\\", "\\"))
        val keys = Regex("\"key\"\\s*:\\s*\"([^\"]*)\"").findAll(registry).map { it.groupValues[1] }.toList()
        assertTrue(keys.all { keyPattern.matches(it) }, "dimension key outside $keyPattern")
    }

    @Test
    fun `planted street address fails in a temp copy while the original passes`() =
        assertPlantedFails("Delivered to 1234 N 56th last week")

    @Test
    fun `planted named street address fails`() = assertPlantedFails("Mail to 4321 Example Oak St today")

    @Test
    fun `planted wiki link fails`() = assertPlantedFails("see [[Some Invented Page]] for details")

    @Test
    fun `planted title-shaped file name fails`() = assertPlantedFails("source: Invented Closet Plans.md")

    @Test
    fun `planted home path and email fail`() {
        assertPlantedFails("/home/someone/notes")
        assertPlantedFails("write to a.b@example.org")
    }

    @Test
    fun `allow-listed placeholders do not fail`() {
        assertEquals(emptyList(), scanner.findings("use [[links]] and [[page]] placeholders; see CLAUDE.md"))
    }

    @Test
    fun `planted title from an injected temporary denylist fails`() {
        val denyFile = Files.createTempFile("denylist", ".txt").toFile().also { it.deleteOnExit() }
        denyFile.writeText("# header\nzorblax gardens\n")
        val phrases = denyFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.lowercase() }
        val withDeny = Scanner(
            WiringInputs.dataFile("private-string-patterns.txt"),
            WiringInputs.dataFile("private-string-allow.txt"),
            phrases,
        )
        assertEquals(emptyList(), withDeny.findings(fixtureText("fixture-manifest.json")))
        assertTrue(withDeny.findings("notes about Zorblax Gardens").isNotEmpty())
    }

    private fun assertPlantedFails(planted: String) {
        val original = fixtureText("fixture-manifest.json")
        val tmp: File = Files.createTempFile("fixture-copy", ".json").toFile().also { it.deleteOnExit() }
        tmp.writeText(original + "\n" + planted + "\n")
        assertTrue(scanner.findings(tmp.readText()).isNotEmpty(), "planted string was not flagged: $planted")
        assertEquals(emptyList(), scanner.findings(original), "original fixture must stay clean")
    }
}
