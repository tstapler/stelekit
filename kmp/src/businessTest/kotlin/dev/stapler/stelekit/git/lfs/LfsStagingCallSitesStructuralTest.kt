// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Structural proof that no new code path can stage or upload a file without the Git LFS guard: a call to
 * `stageSubdir`, `markResolved` or JGit's `add()` must sit inside a function that also references the guard
 * (`LfsGuardCoordinator`, `LfsIndexVerifier` or a helper that wraps them). Adding a fresh staging caller
 * therefore fails the build until it goes through the guard (the conflict-marker guard once missed an entry
 * point, which is why this exists).
 *
 * Source text is read the same way [dev.stapler.stelekit.git.GitShadowWorktreeNoCoroutineScopeTest] reads
 * `GitShadowWorktree.kt`: a classpath resource under Bazel, the real file under Gradle. The web write service
 * is checked only when its file is reachable (Gradle); there `createBlob` cannot be called without a
 * `WasmLfsBlobGate` argument, which the compiler already enforces.
 */
class LfsStagingCallSitesStructuralTest {

    private fun load(resource: String, vararg repoRelative: String): String? {
        javaClass.classLoader.getResourceAsStream(resource)?.bufferedReader()?.use { return it.readText() }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (relative in repoRelative) {
                File(dir, relative).takeIf { it.isFile }?.let { return it.readText() }
                File(dir, "kmp/$relative").takeIf { it.isFile }?.let { return it.readText() }
            }
            dir = dir.parentFile
        }
        return null
    }

    private val service = "src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt"
    private val staging = "src/commonMain/kotlin/dev/stapler/stelekit/git/lfs/LfsGuardedStaging.kt"
    private val jvm = "src/jvmMain/kotlin/dev/stapler/stelekit/git/JvmGitRepository.kt"
    private val android = "src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt"
    private val web = "src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt"

    private val guardMarkers = listOf(
        "lfsGuard.evaluate", "lfsStaging.stage", "lfsStaging.refusalFor", "LfsIndexVerifier", "refuseOnViolation", "lfs.gate", "WasmLfsBlobGate", "requireNotRawAtTrackedPath",
    )

    private class Function(val name: String, val body: String)

    /** Drops comments so prose such as "git.add() below would stage ..." is never mistaken for a call. */
    private fun stripComments(source: String): String =
        source.replace(Regex("""(?s)/\*.*?\*/"""), "").replace(Regex("""(?m)^\s*//.*$"""), "").replace(Regex("""\s//[^\n"]*$""", RegexOption.MULTILINE), "")

    /** Splits [source] into top-level-ish functions: each `fun` declaration runs to the next one. */
    private fun functions(rawSource: String): List<Function> {
        val source = stripComments(rawSource)
        val declaration = Regex("""(?m)^[ \t]*(?:(?:override|private|internal|public|suspend|inline)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
        val matches = declaration.findAll(source).toList()
        return matches.mapIndexed { i, m ->
            Function(m.groupValues[1], source.substring(m.range.first, matches.getOrNull(i + 1)?.range?.first ?: source.length))
        }
    }

    private fun callsOf(token: Regex, fn: Function): Int = token.findAll(fn.body).count()

    private fun assertEveryCallIsGuarded(label: String, source: String, token: Regex, declarationName: String) {
        val offenders = functions(source)
            .filter { it.name != declarationName && callsOf(token, it) > 0 }
            .filter { fn -> guardMarkers.none { it in fn.body } }
            .map { it.name }
        assertTrue(offenders.isEmpty(), "$label: ${token.pattern} is called without an LFS guard in: $offenders")
    }

    @Test
    fun gitSyncService_should_StageAndResolveOnlyThroughTheGuard() {
        val source = load("GitSyncService.kt", service) ?: fail("GitSyncService.kt not found")

        assertTrue(!Regex("""\.stageSubdir\(""").containsMatchIn(stripComments(source)), "GitSyncService must stage via LfsGuardedStaging, never directly")
        assertEveryCallIsGuarded("GitSyncService", source, Regex("""\.markResolved\("""), "markResolved")
    }

    @Test
    fun lfsGuardedStaging_should_BeTheOnlyCallerOfStageSubdir_AndGuardBeforeStaging() {
        val source = load("lfs/LfsGuardedStaging.kt", staging) ?: fail("LfsGuardedStaging.kt not found")
        val callers = functions(source).filter { Regex("""\.stageSubdir\(""").containsMatchIn(it.body) }

        assertTrue(callers.map { it.name } == listOf("stage"), "stageSubdir must be called only from LfsGuardedStaging.stage, found ${callers.map { it.name }}")
        val body = callers.single().body
        assertTrue(body.indexOf("lfsGuard.evaluate") in 0 until body.indexOf(".stageSubdir("), "the guard must run before staging")
        assertTrue(body.indexOf(".stagedLfsViolations(") > body.indexOf(".stageSubdir("), "the index is verified after staging")
    }

    @Test
    fun jvmGitRepository_should_VerifyTheIndexWhereverItStages() {
        val source = load("JvmGitRepository.kt", jvm) ?: fail("JvmGitRepository.kt not found")

        assertEveryCallIsGuarded("JvmGitRepository", source, Regex("""\.add\(\)"""), "add")
        assertTrue(functions(source).single { it.name == "commit" }.body.contains("refuseOnViolation"), "commit() must run the index verifier")
    }

    @Test
    fun androidGitRepository_should_VerifyTheIndexWhereverItStages() {
        val source = load("AndroidGitRepository.kt", android) ?: fail("AndroidGitRepository.kt not found")

        assertEveryCallIsGuarded("AndroidGitRepository", source, Regex("""\.add\(\)"""), "add")
        assertTrue(functions(source).single { it.name == "commit" }.body.contains("refuseOnViolation"), "commit() must run the index verifier")
    }

    @Test
    fun wasmGitWriteService_should_NeverCreateABlobWithoutTheGate_When_TheSourceIsReachable() {
        val source = load("WasmGitWriteService.kt", web) ?: return // Bazel has no wasm package; the compiler enforces the gate parameter.

        assertEveryCallIsGuarded("WasmGitWriteService", source, Regex("""createBlob\("""), "createBlob")
        assertTrue(Regex("""private suspend fun createBlob\([^)]*gate: WasmLfsBlobGate""").containsMatchIn(source), "createBlob must require a gate")
    }

    @Test
    fun theScannerItselfFlagsAnUnguardedCall() {
        val unguarded = "class X {\n  suspend fun sneaky() {\n    repo.stageSubdir(c)\n  }\n  suspend fun fine() {\n    lfsGuard.evaluate()\n    repo.stageSubdir(c)\n  }\n}\n"

        val offenders = functions(unguarded).filter { callsOf(Regex("""\.stageSubdir\("""), it) > 0 }.filter { fn -> guardMarkers.none { it in fn.body } }

        assertTrue(offenders.map { it.name } == listOf("sneaky"), "the scanner must catch the unguarded caller and spare the guarded one: ${offenders.map { it.name }}")
    }
}
