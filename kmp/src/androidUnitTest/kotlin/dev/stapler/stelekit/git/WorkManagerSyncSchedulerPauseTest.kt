// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 3.2.1c: a relocate must cancel a graph's scheduled `WorkManager` periodic sync job before
 * its copy step starts, and re-schedule it once `switchGraph()` completes — otherwise a
 * concurrent background fetch can race the foreground copy of `.git` (REQ-8, `research/pitfalls.md`
 * §3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WorkManagerSyncSchedulerPauseTest {

    private lateinit var context: Context
    private val graphId = "g1"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // The merged manifest's default androidx.startup WorkManagerInitializer content
        // provider never runs under Robolectric, so WorkManager.getInstance() throws unless
        // initialized manually here — a synchronous inline Executor keeps enqueue/cancel calls
        // (and this test's assertions on their result) deterministic without needing the
        // work-testing artifact's WorkManagerTestInitHelper.
        val config = Configuration.Builder()
            .setExecutor(Executor { it.run() })
            .build()
        try {
            WorkManager.initialize(context, config)
        } catch (_: IllegalStateException) {
            // Already initialized by an earlier test in this class — WorkManagerImpl's instance
            // is a JVM-static singleton that outlives Robolectric's per-test Application, so only
            // the first test method in a run actually needs to call initialize().
        }
    }

    private fun enqueuedWorkExists(): Boolean =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("stelekit_git_sync_$graphId")
            .get()
            .any { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun pauseFor_should_CancelPeriodicJobBeforeCopyBegins_When_RelocateStarts() {
        WorkManagerSyncScheduler(context, graphId).schedule(intervalMinutes = 15)
        assertTrue(enqueuedWorkExists(), "sanity check: job should be enqueued before pauseFor runs")

        WorkManagerSyncScheduler.pauseFor(context, graphId)

        assertFalse(enqueuedWorkExists(), "pauseFor must cancel the periodic job before the copy step starts")
    }

    @Test
    fun resumeFor_should_RescheduleThePeriodicJob_When_SwitchGraphCompletes() {
        WorkManagerSyncScheduler(context, graphId).schedule(intervalMinutes = 15)
        WorkManagerSyncScheduler.pauseFor(context, graphId)
        assertFalse(enqueuedWorkExists(), "sanity check: job should be paused before resumeFor runs")

        WorkManagerSyncScheduler.resumeFor(context, graphId)

        assertTrue(enqueuedWorkExists(), "resumeFor must re-schedule the periodic job after switchGraph() completes")
    }

    @Test
    fun pauseFor_should_NotAffectAnotherGraphsScheduledJob_When_OnlyOneGraphIsRelocating() {
        val otherGraphId = "g2"
        WorkManagerSyncScheduler(context, graphId).schedule(intervalMinutes = 15)
        WorkManagerSyncScheduler(context, otherGraphId).schedule(intervalMinutes = 15)

        WorkManagerSyncScheduler.pauseFor(context, graphId)

        val otherStillEnqueued = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("stelekit_git_sync_$otherGraphId")
            .get()
            .any { it.state == WorkInfo.State.ENQUEUED }
        assertTrue(otherStillEnqueued, "pauseFor must only cancel the targeted graph's job, not every graph's")
    }
}
