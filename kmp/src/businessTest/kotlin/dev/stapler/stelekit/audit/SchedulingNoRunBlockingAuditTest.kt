// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.audit

import dev.stapler.stelekit.wiring.WiringAudit
import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals

/** Production scheduling and worker code must not hold a WorkManager or main thread with `runBlocking`. */
class SchedulingNoRunBlockingAuditTest {
    private val scoped = Regex(
        """(androidMain|commonMain|jvmCommonMain)/.*/(jobs/schedule/.*""" +
            """|git/(GitSync|GitCloneWorker|AndroidGitCloneWorkerLauncher|AndroidColdPathRunner|ColdSyncRunner|ScheduledSyncRunner""" +
            """|PeriodicSyncDriver|WorkManagerSyncScheduler|BackgroundSyncScheduler)[^/]*""" +
            """|platform/ml/(DepthModelDownload|ModelDownload)[^/]*|db/GraphMoveQuiesceStrategy[^/]*)""",
    )

    private val mustBeCovered = listOf(
        "TaskWorker.kt", "ScheduleStartup.kt", "WorkManagerSyncScheduler.kt", "GitCloneWorker.kt", "GitSyncTask.kt",
        "ColdSyncRunner.kt", "DepthModelDownloadWorker.kt", "ModelDownloadTask.kt", "GraphMoveQuiesceStrategy.android.kt",
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
        val covered = sources.keys.filter(scoped::matches).map { it.substringAfterLast('/') }.toSet()
        assertEquals(emptyList(), mustBeCovered - covered, "scope misses these files, so the audit would pass vacuously")
        assertEquals(emptyList(), violations(sources))
    }
}
