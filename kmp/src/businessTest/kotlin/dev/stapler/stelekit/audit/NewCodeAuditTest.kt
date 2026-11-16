// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.audit

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U-E1-37 / X-48: code added by the sync-lock-and-image-annotator project reaches no network or `android.util.Log`
 * (it logs through `Logger`) and never reports status or errors through a toast alone. [NEW_FILES] is the list of
 * files this project added; each epic appends its own new main-source files here.
 */
class NewCodeAuditTest {
    companion object {
        private const val K = "dev/stapler/stelekit/"

        /** Main-source files added by Epic 1 (sync single-flight and lock recovery), keyed like [WiringInputs]. */
        val NEW_FILES: List<String> = listOf(
            "androidMain/${K}git/AndroidOwnedOperations.kt",
            "androidMain/${K}git/AndroidProcessLiveness.kt",
            "commonMain/${K}git/ActiveRunRegistry.kt",
            "commonMain/${K}git/GatedOperation.kt",
            "commonMain/${K}git/LivenessPolicy.kt",
            "commonMain/${K}git/LockFiles.kt",
            "commonMain/${K}git/LockOwnerRecord.kt",
            "commonMain/${K}git/LockRecovery.kt",
            "commonMain/${K}git/OwnerHeartbeat.kt",
            "commonMain/${K}git/PausedRetryController.kt",
            "commonMain/${K}git/PausedRetryInfo.kt",
            "commonMain/${K}git/PausedRetryPolicy.kt",
            "commonMain/${K}git/ProcStatParser.kt",
            "commonMain/${K}git/ProcessLiveness.kt",
            "commonMain/${K}git/RepoOpGate.kt",
            "commonMain/${K}git/SyncOrigin.kt",
            "commonMain/${K}git/SyncRunInfo.kt",
            "commonMain/${K}git/SyncSingleFlight.kt",
            "jvmCommonMain/${K}git/JavaIoLockFiles.kt",
            "jvmCommonMain/${K}git/JavaIoPathCanonicalizer.kt",
            "jvmCommonMain/${K}git/OwnedOperationSupport.kt",
            "jvmCommonMain/${K}git/ProcfsProbe.kt",
            "jvmMain/${K}git/JvmOwnedOperations.kt",
            "jvmMain/${K}git/JvmProcessLiveness.kt",
        )

        /** Imports that are in the forbidden set but harmless here, with the reason. */
        private val ALLOWED_IMPORTS = mapOf(
            "jvmMain/${K}git/JvmOwnedOperations.kt" to "java.net.InetAddress",
        ) // InetAddress.getLocalHost().hostName only names this machine in the owner record; no socket is opened

        private val FORBIDDEN_IMPORT = Regex("""^\s*import\s+((?:java\.net\.|okhttp3?\.|android\.util\.Log\b)\S*)""", RegexOption.MULTILINE)
        private val TOAST_ONLY = Regex("""\bToast\.makeText\b|\bshowToast\s*\(|\bSnackbar\b|\bsnackbarHostState\b""")

        fun forbiddenImports(sources: Map<String, String>): List<String> =
            sources.filter { (path, text) ->
                FORBIDDEN_IMPORT.findAll(text).any { m -> m.groupValues[1] != ALLOWED_IMPORTS[path] }
            }.keys.sorted()

        fun toastReporters(sources: Map<String, String>): List<String> =
            sources.filter { (_, text) -> text.lines().any { !it.trimStart().startsWith("//") && TOAST_ONLY.containsMatchIn(it) } }.keys.sorted()
    }

    private fun newSources(): Map<String, String> {
        val all = WiringInputs.productionSources
        val missing = NEW_FILES.filter { it !in all }
        assertEquals(emptyList(), missing, "NEW_FILES lists a file that no longer exists: rename or remove the entry")
        return all.filterKeys { it in NEW_FILES }
    }

    @Test
    fun newSyncCameraAnnotatorFiles_should_NotImportNetworkOrAndroidUtilLog() {
        assertEquals(emptyList(), forbiddenImports(newSources()))
    }

    @Test
    fun newStatusAndErrorText_should_UseLiveRegionOrFocus_AndNeverToastOnly() {
        assertTrue(newSources().isNotEmpty(), "no new files audited, so the audit would pass vacuously")
        assertEquals(emptyList(), toastReporters(newSources()))
    }

    @Test
    fun auditRules_should_FlagPlantedViolations() {
        val planted = mapOf(
            "a/Net.kt" to "import java.net.URL\n",
            "a/Log.kt" to "import android.util.Log\n",
            "a/Toast.kt" to "fun f() { Toast.makeText(c, \"x\", 0) }\n",
            "a/Ok.kt" to "import dev.stapler.stelekit.logging.Logger\n// Snackbar mention in a comment\n",
        )
        assertEquals(listOf("a/Log.kt", "a/Net.kt"), forbiddenImports(planted))
        assertEquals(listOf("a/Toast.kt"), toastReporters(planted))
    }
}
