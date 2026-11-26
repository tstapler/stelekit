// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.audit

import dev.stapler.stelekit.wiring.WiringAudit
import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Production scheduling and worker code must not hold a WorkManager or main thread with `runBlocking`. */
class SchedulingNoRunBlockingAuditTest {
    private val scoped = Regex(
        """(androidMain|commonMain)/.*/(jobs/schedule/.*|git/(GitSyncWorker|WorkManagerSyncScheduler|BackgroundSyncScheduler)[^/]*|platform/ml/DepthModelDownload[^/]*)""",
    )

    private fun violations(sources: Map<String, String>): List<String> = sources
        .filter { (path, text) -> scoped.matches(path) && "runBlocking" in WiringAudit.stripCommentsAndStrings(text) }
        .keys.toList()

    @Test
    fun plantedRunBlockingInAWorkerIsReported() {
        val planted = mapOf(
            "androidMain/x/jobs/schedule/Bad.kt" to "fun f() = runBlocking { }",
            "androidMain/x/git/GitSyncWorker.kt" to "fun f() = runBlocking<Unit> { }",
            "androidMain/x/jobs/schedule/Ok.kt" to "// runBlocking is forbidden here",
            "androidMain/x/db/DriverFactory.android.kt" to "fun f() = runBlocking { }",
        )
        assertEquals(listOf("androidMain/x/jobs/schedule/Bad.kt", "androidMain/x/git/GitSyncWorker.kt"), violations(planted))
    }

    @Test
    fun noProductionSchedulingSourceUsesRunBlocking() {
        val sources = WiringInputs.productionSources
        assertTrue(sources.keys.count(scoped::matches) > 10, "scope matched too few files, so the audit would pass vacuously")
        assertEquals(emptyList(), violations(sources))
    }
}
