// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.6b: a `runOnce(...)` for git-sync must never pass `OneShotConflict.Replace`, which would cancel a sync that
 * already holds the repository gate. Today there is no git-sync `runOnce` at all, so this guards the future.
 */
class GitSyncOneShotConflictAuditTest {
    private val runOnceCall = Regex("""runOnce\(([^;]{0,400})""")
    private val gitSyncRef = Regex("""GitSyncTask|GIT_SYNC|git-sync|stelekit_git_sync""")

    private fun violations(sources: Map<String, String>): List<String> =
        sources.filter { (_, text) ->
            runOnceCall.findAll(text).any { call ->
                val args = call.groupValues[1].substringBefore(")\n").take(300)
                gitSyncRef.containsMatchIn(args) && "OneShotConflict.Replace" in args
            }
        }.keys.toList()

    @Test
    fun plantedGitSyncReplaceIsReported() {
        val planted = mapOf("androidMain/x/Bad.kt" to "scheduler.runOnce(GitSyncTask.KEY, c, OneShotConflict.Replace)")
        assertEquals(listOf("androidMain/x/Bad.kt"), violations(planted))
    }

    @Test
    fun otherTasksMayReplaceAndGitSyncMayKeep() {
        val ok = mapOf(
            "androidMain/x/Ok.kt" to "taskScheduler.runOnce(ModelDownloadTask.KEY, c, OneShotConflict.Replace)\n" +
                "taskScheduler.runOnce(GitSyncTask.KEY, c, OneShotConflict.Keep)",
        )
        assertEquals(emptyList(), violations(ok))
    }

    @Test
    fun noProductionRunOnceForGitSyncPassesReplace() {
        val sources = WiringInputs.productionSources
        assertTrue(sources.isNotEmpty(), "source loader returned nothing, so the audit would pass vacuously")
        assertEquals(emptyList(), violations(sources))
    }
}
