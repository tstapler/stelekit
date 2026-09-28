package dev.stapler.stelekit.transfer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Structural enforcement of the QR transfer feature's air-gap guarantee: nothing under
 * `transfer/` may import a networked or cloud-account dependency. Modeled on
 * [dev.stapler.stelekit.db.MigrationRunnerSchemaSyncTest] — a filesystem-backed scan of real
 * source files rather than a runtime assertion, so a forbidden import fails CI even if no
 * code path ever executes it.
 *
 * Forbidden import substrings:
 *  - `io.ktor`                       — any network HTTP client
 *  - `dev.stapler.stelekit.git`      — existing git-sync transport (requires a shared remote)
 *  - `org.eclipse.jgit`              — git-sync's underlying library
 *  - `com.google.api` / `com.google.android.gms` — Google Cloud / Play Services APIs
 */
class AirGapGuardTest {

    private val forbiddenImportSubstrings = listOf(
        "io.ktor",
        "dev.stapler.stelekit.git",
        "org.eclipse.jgit",
        "com.google.api",
        "com.google.android.gms",
    )

    @Test
    fun airGapGuardTest_should_FailWithOffendingFilePath_When_TransferPackageImportsNetworkDependency() {
        val transferDir = findTransferSourceDir()
        val offenders = mutableListOf<String>()

        transferDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines()
                    .filter { it.trimStart().startsWith("import ") }
                    .forEach { line ->
                        if (forbiddenImportSubstrings.any { line.contains(it) }) {
                            offenders += "${file.path}: ${line.trim()}"
                        }
                    }
            }

        assertTrue(
            offenders.isEmpty(),
            "Found forbidden network/cloud import(s) under transfer/ — this breaks the feature's " +
                "air-gap guarantee (no networked transport, no cloud intermediary):\n" +
                offenders.joinToString("\n")
        )
    }

    /**
     * Locates `kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/` by walking up from the
     * JVM process's working directory. Gradle's `Test` task working directory defaults to the
     * module directory (`kmp/`), but this walk tolerates being invoked from the repo root too.
     */
    private fun findTransferSourceDir(): File {
        val relative = "kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer"
        var dir = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            val direct = File(dir, "src/commonMain/kotlin/dev/stapler/stelekit/transfer")
            if (direct.isDirectory) return direct
            dir = dir.parentFile ?: return@repeat
        }
        fail(
            "Could not locate transfer/ source directory by walking up from " +
                "${File(".").absolutePath} — expected to find " +
                "'$relative' or 'src/commonMain/kotlin/dev/stapler/stelekit/transfer'"
        )
    }
}
