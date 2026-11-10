// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR-001: the scheduling port in `jobs.schedule` must not import `git`, `ui`, `db` or `merge`. */
class JobsScheduleImportDirectionTest {
    private val forbidden = Regex("""(?m)^\s*import\s+dev\.stapler\.stelekit\.(git|ui|db|merge)\b""")
    private val scheduleDir = "/dev/stapler/stelekit/jobs/schedule/"

    private fun violations(sources: Map<String, String>): List<String> =
        sources.filter { (path, text) -> scheduleDir in path && forbidden.containsMatchIn(text) }.keys.toList()

    private fun scheduleFiles(sourceSet: String): Map<String, String> =
        WiringInputs.productionSources.filterKeys { it.startsWith("$sourceSet/") && scheduleDir in it }

    @Test
    fun plantedViolationIsReported() {
        val planted = mapOf(
            "/dev/stapler/stelekit/jobs/schedule/Bad.kt" to
                "package x\nimport dev.stapler.stelekit.git.GitSyncService\n",
        )
        assertEquals(1, violations(planted).size)
    }

    @Test
    fun allowedImportsAreNotReported() {
        val ok = mapOf(
            "/dev/stapler/stelekit/jobs/schedule/Ok.kt" to
                "import dev.stapler.stelekit.platform.Settings\nimport dev.stapler.stelekit.jobs.JobKind\n",
        )
        assertEquals(emptyList(), violations(ok))
    }

    @Test
    fun scannerSeesCommonMainSourcesAndSourceSetsAreBundled() {
        assertTrue(scheduleFiles("commonMain").isNotEmpty(), "no commonMain jobs/schedule sources bundled")
        // androidMain gets jobs/schedule files in Phase 3; until then only the source set's presence is provable.
        assertTrue(
            WiringInputs.productionSources.keys.any { it.startsWith("androidMain/") },
            "androidMain sources are not bundled",
        )
    }

    @Test
    fun realSourceTreeHasNoViolations() {
        val all = scheduleFiles("commonMain") + scheduleFiles("androidMain")
        assertEquals(emptyList(), violations(all))
    }
}
