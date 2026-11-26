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
        val suite = sources[suitePath].orEmpty() + MODULE_INDEX[set]?.let { sources[it] }.orEmpty()
        sources.filter { (path, text) -> path.startsWith("$set/") && isSketchV2TestFile(path) && hasTests(text) && path != suitePath }
            .keys.filter { path -> !suite.contains(path.substringAfterLast('/').removeSuffix(".kt")) }
    }.sorted()

    private fun stripComments(text: String) = text.replace(Regex("""(?s)/\*.*?\*/"""), "").replace(Regex("""//.*|#.*"""), "")

    /**
     * Files the suite names only in a comment (no `Name::class`) and whose target `BUILD.bazel` does not exclude by code line:
     * the comment says "excluded" but Bazel still compiles the file, which breaks the whole target when it needs a fixture
     * (commonTest rig, no Compose plugin) the target lacks. [builds] maps a source set to its BUILD file text.
     */
    fun commentOnlyWithoutBuildExclusion(sources: Map<String, String>, builds: Map<String, String>): List<String> =
        builds.flatMap { (set, build) ->
            val suiteCode = stripComments(sources[suites.getValue(set)].orEmpty())
            val buildCode = stripComments(build)
            val moduleIndex = MODULE_INDEX[set]?.let { sources[it] }.orEmpty()
            sources.filter { (path, text) -> path.startsWith("$set/") && isSketchV2TestFile(path) && hasTests(text) && path != suites.getValue(set) }
                .keys.filter { path ->
                    val name = path.substringAfterLast('/').removeSuffix(".kt")
                    // A moved class is excluded through the generated manifest, which the BUILD file reads by variable.
                    !Regex("""\b$name::class\b""").containsMatchIn(suiteCode) && !buildCode.contains("$name.kt") &&
                        !moduleIndex.contains("\"" + path.removePrefix("$set/").removeSuffix(".kt").replace('/', '.') + "\"")
                }
        }.sorted()

    /**
     * commonTest `sketch/v2` test files absent from the BUILD file text. A class homed in a module suite
     * ([moduleSuites], the generated `kmp/modules/All*ModuleTests.kt`) is deliberately kept out of the umbrella list.
     */
    fun missingFromBuild(sources: Map<String, String>, buildText: String, moduleSuites: String = ""): List<String> =
        sources.filter { (path, text) -> path.startsWith("commonTest/") && isSketchV2TestFile(path) && hasTests(text) }
            .keys.filter { path ->
                val relative = path.removePrefix("commonTest/")
                // Matched by fully qualified import: simple names repeat across packages (NoticeQueueTest).
                val import = "import " + relative.removeSuffix(".kt").replace('/', '.')
                !buildText.contains(relative) && moduleSuites.lineSequence().none { it.trim() == import }
            }.sorted()

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
        val pkg = "import dev.stapler.stelekit"
        assertEquals(emptyList(), missingFromBuild(common, build, "$pkg.ui.sketch.v2.MissingTest"))
        assertEquals(
            listOf("commonTest/dev/stapler/stelekit/ui/sketch/v2/MissingTest.kt"),
            missingFromBuild(common, build, "$pkg.table.MissingTest\nMissingTest::class"),
        )
    }

    @Test
    fun testRegistrationAudit_should_PassAtHead_When_EverySketchV2TestClassIsNamedInItsSuite() {
        val sources = WiringInputs.testSources
        assertTrue(suites.values.all { it in sources }, "a suite file was not loaded: ${suites.values.filter { it !in sources }}")
        val unnamed = unnamedInSuite(sources)
        assertTrue(unnamed.isEmpty(), "name each class in its suite file (register it, or list it in the exclusion comment with the reason):\n  ${unnamed.joinToString("\n  ")}")
    }

    @Test
    fun testRegistrationAudit_should_FlagACommentOnlyName_When_TheBuildFileDoesNotExcludeIt() {
        val suite = "jvmTest/dev/stapler/stelekit/AllJvmTests.kt"
        val planted = mapOf(
            suite to "// ExcludedTest and LostTest run under Gradle only\nSeenTest::class",
            "jvmTest/dev/stapler/stelekit/sketch/v2/SeenTest.kt" to "@Test fun a() {}",
            "jvmTest/dev/stapler/stelekit/sketch/v2/ExcludedTest.kt" to "@Test fun a() {}",
            "jvmTest/dev/stapler/stelekit/sketch/v2/LostTest.kt" to "@Test fun a() {}",
        )
        val build = "# LostTest.kt only in a BUILD comment\nexclude = [\"**/ExcludedTest.kt\"]"
        assertEquals(
            listOf("jvmTest/dev/stapler/stelekit/sketch/v2/LostTest.kt"),
            commentOnlyWithoutBuildExclusion(planted, mapOf("jvmTest" to build)),
        )
    }

    @Test
    fun testRegistrationAudit_should_PassAtHead_When_EveryCommentOnlySketchV2NameIsExcludedInItsBuildFile() {
        Assume.assumeTrue("BUILD.bazel is not bundled into the Bazel resources; the check runs under Gradle", WiringInputs.mode == "gradle-disk")
        val root = DocRepoLocator.repoRoot
        val builds = mapOf(
            "jvmTest" to root.resolve("kmp/src/jvmTest/kotlin/BUILD.bazel").readText(),
            "androidUnitTest" to root.resolve("kmp/src/androidUnitTest/kotlin/BUILD.bazel").readText(),
        )
        val bad = commentOnlyWithoutBuildExclusion(WiringInputs.testSources, builds)
        assertTrue(bad.isEmpty(), "named only in a suite comment, so add a BUILD.bazel exclude (or register the class):\n  ${bad.joinToString("\n  ")}")
    }

    @Test
    fun testRegistrationAudit_should_PassAtHead_When_EverySketchV2CommonTestFileIsInTheBuildFile() {
        Assume.assumeTrue("BUILD.bazel is not bundled into the Bazel resources; the check runs under Gradle", WiringInputs.mode == "gradle-disk")
        val build = DocRepoLocator.repoRoot.resolve("kmp/src/commonTest/kotlin/BUILD.bazel").readText()
        val moduleSuites = DocRepoLocator.repoRoot.resolve("kmp/modules").listFiles { f -> f.name.matches(Regex("""All\w+ModuleTests\.kt""")) }
            .orEmpty().joinToString("\n") { it.readText() }
        val missing = missingFromBuild(WiringInputs.testSources, build, moduleSuites)
        assertTrue(missing.isEmpty(), "add to kmp/src/commonTest/kotlin/BUILD.bazel:\n  ${missing.joinToString("\n  ")}")
    }

    private companion object {
        val SKETCH_V2 = Regex("""/sketch/v2/""")

        /** Generated list of classes a per-module Bazel target runs instead of the set's suite (scripts/kmp-module-tests-sync.py). */
        val MODULE_INDEX = mapOf(
            "businessTest" to "businessTest/dev/stapler/stelekit/ModuleBusinessTests.kt",
            "jvmTest" to "jvmTest/dev/stapler/stelekit/ModuleJvmTests.kt",
        )
    }
}
