// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import androidx.work.WorkInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [isGitCloneWorkerStuck] (git-sync-resilience Story 3.1.7) — the pure watchdog-decision
 * function behind the stuck-clone battery-optimization detection (pre-mortem.md P1 #1: a
 * `GitCloneWorker` process killed outright by an OEM battery manager throws no exception at all,
 * so `classifyGitFailure`/`GitTransportRetryState` have nothing to route). Deliberately not a
 * Robolectric/WorkManager integration test — validation.md lists both Story 3.1.7 rows as Unit,
 * no Integration row — since [isGitCloneWorkerStuck] takes plain [WorkInfo.State]/timestamp
 * inputs and has no Android-framework dependency of its own.
 */
class WorkManagerSyncSchedulerWatchdogTest {

    private val thresholdMs = STUCK_CLONE_WATCHDOG_THRESHOLD_MINUTES * 60_000L

    @Test
    fun `isGitCloneWorkerStuck fires for a RUNNING WorkInfo whose start time is more than the threshold before now`() {
        val now = 1_000_000_000L
        val startTime = now - thresholdMs - 1L

        assertTrue(isGitCloneWorkerStuck(WorkInfo.State.RUNNING, startTime, now))
    }

    @Test
    fun `isGitCloneWorkerStuck does not fire for a RUNNING WorkInfo still within the threshold`() {
        val now = 1_000_000_000L
        val startTime = now - thresholdMs + 1L

        assertFalse(isGitCloneWorkerStuck(WorkInfo.State.RUNNING, startTime, now))
    }

    @Test
    fun `isGitCloneWorkerStuck does not fire for a non-RUNNING state regardless of elapsed time`() {
        val now = 1_000_000_000L
        val startTime = now - thresholdMs - 1L

        for (state in WorkInfo.State.entries.filter { it != WorkInfo.State.RUNNING }) {
            assertFalse(isGitCloneWorkerStuck(state, startTime, now), "state=$state must never be reported stuck")
        }
    }

    @Test
    fun `isGitCloneWorkerStuck does not fire when no start time was tracked`() {
        assertFalse(isGitCloneWorkerStuck(WorkInfo.State.RUNNING, null, 1_000_000_000L))
    }
}
