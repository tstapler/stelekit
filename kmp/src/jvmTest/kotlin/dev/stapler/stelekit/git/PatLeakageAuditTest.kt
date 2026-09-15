// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Story 3.5.1/Task 3.5.1b: turns the manual PAT-leakage grep audit (`grep -rn "token"
 * kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/`, run once by hand against the finished
 * Epic 3.5 diff) into a CI-enforced structural check, so a future edit that accidentally logs the
 * raw PAT/OAuth token fails the build instead of relying on review catching it.
 *
 * Modeled on [dev.stapler.stelekit.transfer.AirGapGuardTest] (itself modeled on
 * `dev.stapler.stelekit.db.MigrationRunnerSchemaSyncTest`) — a filesystem-backed scan of real
 * source files rather than a runtime assertion, so a leak fails CI even if no code path ever
 * executes it.
 *
 * Every line under `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/` containing the substring
 * `token` (case-insensitive) must be one of:
 *  - a parameter/property declaration (e.g. `token: String`, `private val token = ...`, or a
 *    `const val` whose string literal happens to mention "token" in prose, like
 *    `CREDENTIAL_EXPIRED_MESSAGE`),
 *  - a header-construction call site (`GitHostAdapter.authHeader(hostConfig.type,
 *    hostConfig.token)`, `"Authorization" to "Bearer $token"`, `"PRIVATE-TOKEN" to token`), or
 *  - a comment.
 *
 * Anything else — most importantly a `Logger.*`/`println` call or a `DomainError(...).message`
 * string that interpolates the token variable — fails the test.
 */
class PatLeakageAuditTest {

    private val declarationPattern = Regex(
        """^(private\s+|internal\s+|public\s+|protected\s+)?(const\s+)?(val|var)\s+\w+"""
    )
    private val commentPrefixes = listOf("//", "/*", "*")
    private val headerConstructionMarkers = listOf(
        "authHeader(",
        "\"Authorization\"",
        "\"PRIVATE-TOKEN\"",
    )

    @Test
    fun patLeakageAudit_noTokenVariableAppearsOutsideHeaderConstructionOrComments_underGitPackage() {
        val gitSourceDir = findWasmGitSourceDir()
        val offenders = mutableListOf<String>()

        gitSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, rawLine ->
                    if (rawLine.contains("token", ignoreCase = true) && !isAllowedTokenLine(rawLine)) {
                        offenders += "${file.path}:${index + 1}: ${rawLine.trim()}"
                    }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "Found line(s) mentioning 'token' that are not a parameter/property declaration, a " +
                "header-construction call site, or a comment — this class of line is where a PAT/" +
                "OAuth token could leak into logs (Logger.*/println/DomainError(...).message):\n" +
                offenders.joinToString("\n")
        )
    }

    private fun isAllowedTokenLine(rawLine: String): Boolean {
        val trimmed = rawLine.trim()
        if (commentPrefixes.any { trimmed.startsWith(it) }) return true
        if (headerConstructionMarkers.any { trimmed.contains(it) }) return true
        if (declarationPattern.containsMatchIn(trimmed)) return true
        return false
    }

    /**
     * Locates `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/` by walking up from the JVM
     * process's working directory. Gradle's `Test` task working directory defaults to the module
     * directory (`kmp/`), but this walk tolerates being invoked from the repo root too.
     */
    private fun findWasmGitSourceDir(): File {
        val relative = "kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git"
        var dir = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            val direct = File(dir, "src/wasmJsMain/kotlin/dev/stapler/stelekit/git")
            if (direct.isDirectory) return direct
            dir = dir.parentFile ?: return@repeat
        }
        fail(
            "Could not locate wasmJsMain git/ source directory by walking up from " +
                "${File(".").absolutePath} — expected to find " +
                "'$relative' or 'src/wasmJsMain/kotlin/dev/stapler/stelekit/git'"
        )
    }
}
