// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.stapler.stelekit.git.GitSyncWorker
import dev.stapler.stelekit.git.GitSyncWorkerBinding
import dev.stapler.stelekit.jobs.JobKind
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 3.1.2 / Task 3.1.2d: a job enqueued by the pre-upgrade `GitSyncWorker` (no scope tag, input holds only
 * `graph_id` and `interval_minutes`) is converted in place by the next `apply`: same unique name, same work
 * id, still periodic, still `GitSyncWorker`, with the new tags, interval and input. ADR-002 outcomes 2, 4, 5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacyWorkUpgradeTest {
    private lateinit var h: WorkManagerTestHarness
    private lateinit var scheduler: WorkManagerTaskScheduler
    private val key = TaskKey(TaskKind("git-sync"), TaskScope("g1"))
    private val name = "stelekit_git_sync_g1"

    @Before
    fun setUp() {
        h = WorkManagerTestHarness()
        WorkManagerTaskScheduler.clearProcessStatic()
        AppliedConfigLedger.clearProcessStatic()
        val registry = TaskRegistry()
        val c = TaskConfig(true, Schedule.of(60, 0), TaskConstraints())
        registry.register(TaskKind("git-sync"), TaskSpec(JobKind.GitSync, "Git sync", TaskShape.Periodic(c.schedule), c, true, 0)) { _, _ -> TaskRunResult.Success }
        scheduler = WorkManagerTaskScheduler(
            h.context, registry, TaskSettingsStore(ContractSettings(), LegacyConfigSeed.None, registry), AppliedConfigLedger(ContractSettings()),
            workers = GitSyncWorkerBinding.resolver,
        )
    }

    private fun legacyRequest(intervalMinutes: Long) = PeriodicWorkRequestBuilder<GitSyncWorker>(intervalMinutes, TimeUnit.MINUTES)
        .setInitialDelay(1, TimeUnit.HOURS)
        .setInputData(workDataOf(GitSyncWorker.KEY_GRAPH_ID to "g1", GitSyncWorker.KEY_INTERVAL_MINUTES to intervalMinutes))
        .build()

    @Test
    fun apply_should_updateInPlaceKeepingIdPeriodicAndWorkerClass_When_PreUpgradeJobExists() = runBlocking<Unit> {
        val legacy = legacyRequest(15)
        h.wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, legacy).result.get()
        assertTrue(assertNotNull(h.active(name)).tags.none { it.startsWith("stelekit-sched-scope:") }, "precondition: legacy job has no scope tag")

        scheduler.apply(key, TaskConfig(true, Schedule.of(60, 0), TaskConstraints(NetworkPolicy.Unmetered)))

        val live = assertNotNull(h.active(name))
        assertEquals(legacy.id, live.id, "UPDATE keeps the work id")
        assertEquals(1, h.infos(name).count { !it.state.isFinished }, "a single unique-work entry remains")
        assertEquals(60L, TimeUnit.MILLISECONDS.toMinutes(live.periodicityInfo!!.repeatIntervalMillis), "still periodic, now at the stored interval")
        assertTrue(SchedTags.scope(key.scope) in live.tags, "new scope tag applied")
        val (worker, input) = h.spec(live.id)
        assertEquals(GitSyncWorker::class.java.name, worker, "git-sync is enqueued as GitSyncWorker, never TaskWorker")
        assertEquals("g1", input.getString(GitSyncWorker.KEY_GRAPH_ID))
        assertEquals(60L, input.getLong(GitSyncWorker.KEY_INTERVAL_MINUTES, -1))
        assertEquals("unmetered", input.getString(GitSyncWorker.KEY_NETWORK_POLICY))
    }

    @Test
    fun apply_should_replaceStaleInputInterval_When_StoredIntervalDiffers() = runBlocking<Unit> {
        val legacy = legacyRequest(15)
        h.wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, legacy).result.get()
        assertEquals(15L, h.spec(legacy.id).second.getLong(GitSyncWorker.KEY_INTERVAL_MINUTES, -1))

        scheduler.apply(key, TaskConfig(true, Schedule.of(60, 0), TaskConstraints()))

        assertEquals(60L, h.spec(legacy.id).second.getLong(GitSyncWorker.KEY_INTERVAL_MINUTES, -1), "input interval 15 re-enqueued at the stored 60")
    }

    @Test
    fun apply_should_leaveJobEnqueued_When_ConvertingPreUpgradeJob() = runBlocking<Unit> {
        // The RUNNING-UPDATE outcome (ADR-002 outcome 5) is proven by WorkManagerJitterSpikeTest; here an ENQUEUED legacy job stays active.
        h.wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, legacyRequest(15)).result.get()
        scheduler.apply(key, TaskConfig(true, Schedule.of(45, 0), TaskConstraints()))
        assertEquals(WorkInfo.State.ENQUEUED, assertNotNull(h.active(name)).state)
    }
}
