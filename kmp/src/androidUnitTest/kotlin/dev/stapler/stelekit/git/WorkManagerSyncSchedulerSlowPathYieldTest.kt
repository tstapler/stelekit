// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import androidx.work.WorkInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [shouldSlowPathYieldToGitCloneWorker] (git-sync-resilience Story 5.1.3) — the pure
 * yield-decision behind `GitSyncWorker`'s slow-path defense-in-depth check. Deliberately not a
 * Robolectric/WorkManager integration test, mirroring `WorkManagerSyncSchedulerWatchdogTest`'s
 * precedent for [isGitCloneWorkerStuck]: a real end-to-end proof that the slow path's standalone
 * fetch actually gets skipped requires driving `GitSyncWorker.doWork()`'s slow path through a
 * fresh `DriverFactory`/`SteleDatabase`, which hits the same pre-existing Robolectric `fts5`
 * blocker documented in `WorkManagerSyncSchedulerRetryOwnerTest`'s slow-path note — so this test
 * covers the decision function directly, which is where the actual yield-vs-proceed logic lives.
 */
class WorkManagerSyncSchedulerSlowPathYieldTest {

    @Test
    fun `slow path yields when a GitCloneWorker WorkInfo is RUNNING for graphId`() {
        assertTrue(shouldSlowPathYieldToGitCloneWorker(WorkInfo.State.RUNNING))
    }

    @Test
    fun `slow path proceeds with its standalone fetch when no GitCloneWorker is RUNNING for graphId`() {
        for (state in WorkInfo.State.entries.filter { it != WorkInfo.State.RUNNING }) {
            assertFalse(shouldSlowPathYieldToGitCloneWorker(state), "state=$state must not suppress the slow path")
        }
        assertFalse(shouldSlowPathYieldToGitCloneWorker(null), "no tracked WorkInfo at all must not suppress the slow path")
    }
}
