// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.Worker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WorkManagerTaskSchedulerPauseResumeTest {
    private lateinit var h: WorkManagerTestHarness
    private lateinit var registry: TaskRegistry
    private lateinit var ledgerSettings: ContractSettings
    private lateinit var store: TaskSettingsStore
    private lateinit var scheduler: WorkManagerTaskScheduler

    private val g1 = TaskKey(TaskKind("git-sync"), TaskScope("g1"))
    private val g2 = TaskKey(TaskKind("git-sync"), TaskScope("g2"))
    private val name1 = "stelekit_git_sync_g1"
    private val name2 = "stelekit_git_sync_g2"

    @Before
    fun setUp() {
        h = WorkManagerTestHarness()
        WorkManagerTaskScheduler.clearProcessStatic()
        AppliedConfigLedger.clearProcessStatic()
        registry = TaskRegistry()
        val c = TaskConfig(true, Schedule.of(15, 0), TaskConstraints())
        registry.register(
            TaskKind("git-sync"),
            TaskSpec(JobKind.GitSync, "Git sync", TaskShape.Periodic(c.schedule), c, false, 0),
        ) { _, _ -> TaskRunResult.Success }
        ledgerSettings = ContractSettings()
        store = TaskSettingsStore(ContractSettings(), LegacyConfigSeed.None, registry)
        scheduler = newScheduler(ledgerSettings)
    }

    private fun newScheduler(settings: ContractSettings) = WorkManagerTaskScheduler(
        h.context, registry, store, AppliedConfigLedger(settings),
        workers = GitSyncWorkerBinding.resolver,
        nowMs = FixedClock()::currentTimeMillis,
    )

    private fun cfg(interval: Int, jitter: Int = 0, c: TaskConstraints = TaskConstraints()) =
        TaskConfig(true, Schedule.of(interval, jitter), c)

    private fun minutes(info: WorkInfo) = TimeUnit.MILLISECONDS.toMinutes(info.periodicityInfo!!.repeatIntervalMillis)

    @Test
    fun resume_should_restoreAppliedInterval_When_PausedAfterApply() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(45, 0, TaskConstraints(NetworkPolicy.Unmetered)))
        scheduler.pause(g1.scope)
        assertNull(h.active(name1), "pause cancels the work")
        scheduler.resume(g1.scope)
        val live = assertNotNull(h.active(name1))
        assertEquals(45L, minutes(live))
        assertEquals(androidx.work.NetworkType.UNMETERED, live.constraints.requiredNetworkType)
        assertEquals(GitSyncWorker::class.java.name, h.spec(live.id).first)
    }

    @Test
    fun pause_should_cancelOnlyNamedGraph() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(45))
        scheduler.apply(g2, cfg(60))
        scheduler.pause(g1.scope)
        assertNull(h.active(name1))
        assertNotNull(h.active(name2))
    }

    @Test
    fun resume_should_reenqueueFromLiveWorkInfo_When_NoLedgerOrStoreEntry_PauseTestScenario() = runBlocking<Unit> {
        h.wm // direct enqueue through the scheduler's seed hook: no ledger, no store entry, no scope tag
        scheduler.seedExternalPeriodic(g1, cfg(15))
        scheduler.pause(g1.scope)
        assertNull(h.active(name1))
        scheduler.resume(g1.scope)
        assertEquals(15L, minutes(assertNotNull(h.active(name1))))
    }

    @Test
    fun resume_should_restorePreUpgradeJobWithOriginalIntervalAndConstraints_When_UnmigratedAndUnrecorded() = runBlocking<Unit> {
        scheduler.seedExternalPeriodic(g1, cfg(90, 0, TaskConstraints(NetworkPolicy.Unmetered, requiresCharging = true)))
        scheduler.pause(g1.scope)
        scheduler.resume(g1.scope)
        val live = assertNotNull(h.active(name1))
        assertEquals(90L, minutes(live))
        assertEquals(androidx.work.NetworkType.UNMETERED, live.constraints.requiredNetworkType)
        assertTrue(live.constraints.requiresCharging())
    }

    @Test
    fun resume_should_restoreDirectlyEnqueuedGitSyncWorkAsEnqueuedGitSyncWorker_UniqueWorkScenario() = runBlocking<Unit> {
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setClock(FixedClock())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == GitSyncWorker::class.java.name) {
                        object : Worker(appContext, workerParameters) { override fun doWork(): Result = Result.success() }
                    } else null
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(h.context, config)
        val request = PeriodicWorkRequestBuilder<GitSyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(GitSyncWorker.KEY_GRAPH_ID to "g1"))
            .build()
        h.wm.enqueueUniquePeriodicWork(name1, ExistingPeriodicWorkPolicy.UPDATE, request).result.get()

        scheduler.pause(g1.scope)
        scheduler.resume(g1.scope)

        val live = assertNotNull(h.active(name1))
        assertEquals(WorkInfo.State.ENQUEUED, live.state)
        assertEquals(GitSyncWorker::class.java.name, h.spec(live.id).first)
        assertTrue(live.periodicityInfo != null)
    }

    @Test
    fun resume_should_enqueueFromStore_When_NoSnapshotAndNoLedger() = runBlocking<Unit> {
        store.write(g1, TaskEntry(true, TaskOverrides(Schedule.of(30, 0), TaskConstraints())))
        scheduler.resume(g1.scope)
        // Nothing was paused: resume only restores what a pause took.
        assertNull(h.active(name1))
    }

    @Test
    fun resume_should_enqueueNothing_When_StoreSaysDisabledEvenWithSnapshot() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(45))
        scheduler.pause(g1.scope)
        store.write(g1, TaskEntry(false, null))
        scheduler.resume(g1.scope)
        assertNull(h.active(name1))
    }

    @Test
    fun resume_should_restoreSnapshot_When_StoreEntryEnabled() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(45))
        store.write(g1, TaskEntry(true, TaskOverrides(Schedule.of(120, 0), TaskConstraints())))
        scheduler.pause(g1.scope)
        scheduler.resume(g1.scope)
        assertEquals(45L, minutes(assertNotNull(h.active(name1))))
    }

    @Test
    fun resume_should_consumeSnapshot_When_Restored() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(45))
        scheduler.pause(g1.scope)
        scheduler.resume(g1.scope)
        assertNull(AppliedConfigLedger(ledgerSettings).pausedSnapshot(g1))
        h.wm.cancelUniqueWork(name1).result.get()
        scheduler.resume(g1.scope)
        assertNull(h.active(name1), "a second resume enqueues nothing")
    }

    @Test
    fun pause_should_notOverwriteSnapshot_When_SecondPauseFindsNonPeriodicWork() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(45))
        scheduler.pause(g1.scope)
        val ledger = AppliedConfigLedger(ledgerSettings)
        val before = ledger.pausedSnapshot(g1)
        // A clone running under the shared name.
        h.wm.enqueueUniqueWork(name1, androidx.work.ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<GitSyncWorker>().build()).result.get()
        scheduler.pause(g1.scope)
        assertEquals(before, ledger.pausedSnapshot(g1))
        assertNull(h.active(name1), "pause always cancels")
        scheduler.resume(g1.scope)
        assertEquals(45L, minutes(assertNotNull(h.active(name1))))
    }

    @Test
    fun pause_should_cancelAndResumeFromMemory_When_SnapshotWriteThrows() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(45))
        ledgerSettings.failWrites = true
        scheduler.pause(g1.scope)
        assertNull(h.active(name1), "a snapshot error never skips the cancel")
        scheduler.resume(g1.scope)
        assertEquals(45L, minutes(assertNotNull(h.active(name1))))
    }

    @Test
    fun apply_should_updateSnapshotAndEnqueueNothing_When_PausedThenResumeRestoresNewConfig() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(45))
        scheduler.pause(g1.scope)
        scheduler.apply(g1, cfg(90))
        assertNull(h.active(name1), "nothing is enqueued during a pause")
        scheduler.resume(g1.scope)
        assertEquals(90L, minutes(assertNotNull(h.active(name1))))
    }

    @Test
    fun apply_should_clearSnapshot_When_DisabledDuringPause() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(45))
        scheduler.pause(g1.scope)
        scheduler.apply(g1, cfg(45).copy(enabled = false))
        assertNull(AppliedConfigLedger(ledgerSettings).pausedSnapshot(g1))
        scheduler.resume(g1.scope)
        assertNull(h.active(name1))
    }

    @Test
    fun resume_should_keepRemainingInitialDelayAndConstraints_When_JitteredWorkWasPaused() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(60, 6, TaskConstraints(NetworkPolicy.Unmetered)))
        val originalDelay = h.active(name1)!!.initialDelayMillis
        assertTrue(originalDelay > 0, "seeded jitter offset gives an initial delay")
        scheduler.pause(g1.scope)
        val snapshot = assertNotNull(AppliedConfigLedger(ledgerSettings).pausedSnapshot(g1))
        assertEquals(originalDelay, snapshot.remainingInitialDelayMs)
        scheduler.resume(g1.scope)
        val live = assertNotNull(h.active(name1))
        assertEquals(originalDelay, live.initialDelayMillis)
        assertEquals(androidx.work.NetworkType.UNMETERED, live.constraints.requiredNetworkType)
    }
}
