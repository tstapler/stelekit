package dev.stapler.stelekit.audit

import dev.stapler.stelekit.docs.DocRepoLocator
import dev.stapler.stelekit.wiring.WiringInputs
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 6.2.1a: a sketch v2 test class that no suite names runs under Gradle (auto-discovery) and silently never under Bazel.
 * Per source set, the suite file must either register the class or name it in an exclusion comment (the Bazel targets that
 * cannot hold a Compose or commonTest fixture exclude such classes by name and say why); a commonTest file also needs its
 * `BUILD.bazel` entry. The rules are pure so a planted miss is tested without touching the tree.
 */
class TestRegistrationAuditTest {
    private val suites = mapOf(
        "businessTest" to "businessTest/dev/stapler/stelekit/AllBusinessTests.kt",
        "jvmTest" to "jvmTest/dev/stapler/stelekit/AllJvmTests.kt",
        "androidUnitTest" to "androidUnitTest/dev/stapler/stelekit/AllAndroidUnitTests.kt",
    )

    private fun isSketchV2TestFile(path: String) = path.endsWith("Test.kt") && SKETCH_V2.containsMatchIn(path)

    private fun hasTests(text: String) = Regex("""@Test\b""").containsMatchIn(text)

    /** Test files of [sources] under a `sketch/v2` package whose simple name the set's suite file never mentions. */
    fun unnamedInSuite(sources: Map<String, String>): List<String> = suites.flatMap { (set, suitePath) ->
        val suite = sources[suitePath].orEmpty()
        sources.filter { (path, text) -> path.startsWith("$set/") && isSketchV2TestFile(path) && hasTests(text) && path != suitePath }
            .keys.filter { path -> !suite.contains(path.substringAfterLast('/').removeSuffix(".kt")) }
    }.sorted()

    /** commonTest `sketch/v2` test files absent from the BUILD file text. */
    fun missingFromBuild(sources: Map<String, String>, buildText: String): List<String> =
        sources.filter { (path, text) -> path.startsWith("commonTest/") && isSketchV2TestFile(path) && hasTests(text) }
            .keys.filter { path -> !buildText.contains(path.removePrefix("commonTest/")) }.sorted()

    @Test
    fun testRegistrationAudit_should_NameTheFile_When_ASketchV2TestIsInNoSuite() {
        val planted = mapOf(
            "jvmTest/dev/stapler/stelekit/AllJvmTests.kt" to "SeenTest::class",
            "jvmTest/dev/stapler/stelekit/sketch/v2/SeenTest.kt" to "@Test fun a() {}",
            "jvmTest/dev/stapler/stelekit/sketch/v2/LostTest.kt" to "@Test fun a() {}",
            "jvmTest/dev/stapler/stelekit/other/ElsewhereTest.kt" to "@Test fun a() {}",
        )
        assertEquals(listOf("jvmTest/dev/stapler/stelekit/sketch/v2/LostTest.kt"), unnamedInSuite(planted))
        val build = "\"dev/stapler/stelekit/ui/sketch/v2/KnownTest.kt\""
        val common = mapOf(
            "commonTest/dev/stapler/stelekit/ui/sketch/v2/KnownTest.kt" to "@Test fun a() {}",
            "commonTest/dev/stapler/stelekit/ui/sketch/v2/MissingTest.kt" to "@Test fun a() {}",
        )
        assertEquals(listOf("commonTest/dev/stapler/stelekit/ui/sketch/v2/MissingTest.kt"), missingFromBuild(common, build))
    }

    @Test
    fun testRegistrationAudit_should_PassAtHead_When_EverySketchV2TestClassIsNamedInItsSuite() {
        val sources = WiringInputs.testSources
        assertTrue(suites.values.all { it in sources }, "a suite file was not loaded: ${suites.values.filter { it !in sources }}")
        val unnamed = unnamedInSuite(sources)
        assertTrue(unnamed.isEmpty(), "name each class in its suite file (register it, or list it in the exclusion comment with the reason):\n  ${unnamed.joinToString("\n  ")}")
    }

    @Test
    fun testRegistrationAudit_should_PassAtHead_When_EverySketchV2CommonTestFileIsInTheBuildFile() {
        Assume.assumeTrue("BUILD.bazel is not bundled into the Bazel resources; the check runs under Gradle", WiringInputs.mode == "gradle-disk")
        val build = DocRepoLocator.repoRoot.resolve("kmp/src/commonTest/kotlin/BUILD.bazel").readText()
        val missing = missingFromBuild(WiringInputs.testSources, build)
        assertTrue(missing.isEmpty(), "add to kmp/src/commonTest/kotlin/BUILD.bazel:\n  ${missing.joinToString("\n  ")}")
    }

    private companion object {
        val SKETCH_V2 = Regex("""/sketch/v2/""")
    }
}
