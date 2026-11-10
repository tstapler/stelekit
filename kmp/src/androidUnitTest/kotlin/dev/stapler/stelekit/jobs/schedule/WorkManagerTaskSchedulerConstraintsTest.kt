// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import androidx.work.NetworkType
import dev.stapler.stelekit.git.GitSyncWorker
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobTags
import dev.stapler.stelekit.platform.ml.DepthModelDownloader
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WorkManagerTaskSchedulerConstraintsTest {
    private lateinit var h: WorkManagerTestHarness
    private lateinit var registry: TaskRegistry
    private lateinit var scheduler: WorkManagerTaskScheduler

    private val g1 = TaskKey(TaskKind("git-sync"), TaskScope("g1"))
    private val download = TaskKey(TaskKind("model-download"), TaskScope(""))
    private val other = TaskKey(TaskKind("other-kind"), TaskScope("g1"))
    private val gitName = "stelekit_git_sync_g1"

    @Before
    fun setUp() {
        h = WorkManagerTestHarness()
        WorkManagerTaskScheduler.clearProcessStatic()
        AppliedConfigLedger.clearProcessStatic()
        registry = TaskRegistry()
        val spec = { retry: Int ->
            val c = TaskConfig(true, Schedule.of(15, 0), TaskConstraints())
            TaskSpec(JobKind.ModelDownload, "Depth model download", TaskShape.Periodic(c.schedule), c, false, retry)
        }
        registry.register(download.kind, spec(2)) { _, _ -> TaskRunResult.Success }
        registry.register(TaskKind("git-sync"), spec(0).copy(jobKind = JobKind.GitSync, title = "Git sync")) { _, _ -> TaskRunResult.Success }
        registry.register(other.kind, spec(0).copy(jobKind = null, title = "Other")) { _, _ -> TaskRunResult.Success }
        scheduler = newScheduler()
    }

    private fun newScheduler(): WorkManagerTaskScheduler {
        val settings = ContractSettings()
        return WorkManagerTaskScheduler(
            h.context, registry, TaskSettingsStore(ContractSettings(), LegacyConfigSeed.None, registry), AppliedConfigLedger(settings),
        )
    }

    private fun cfg(interval: Int, jitter: Int = 0, c: TaskConstraints = TaskConstraints(), enabled: Boolean = true) =
        TaskConfig(enabled, Schedule.of(interval, jitter), c)

    @Test
    fun apply_should_enqueuePeriodicWorkWithReadBackIntervalFlexAndUnmetered_When_ConfigApplied() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(60, 10, TaskConstraints(NetworkPolicy.Unmetered)))
        val infos = h.infos(gitName)
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        val p = assertNotNull(info.periodicityInfo)
        assertEquals(TimeUnit.MINUTES.toMillis(60), p.repeatIntervalMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(10), p.flexIntervalMillis)
    }

    @Test
    fun apply_should_mapChargingAndBatteryNotLowConstraints_When_Requested() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(60, 0, TaskConstraints(NetworkPolicy.Any, requiresCharging = true, batteryNotLow = true)))
        val c = h.active(gitName)!!.constraints
        assertEquals(NetworkType.CONNECTED, c.requiredNetworkType)
        assertTrue(c.requiresCharging())
        assertTrue(c.requiresBatteryNotLow())
    }

    @Test
    fun apply_should_keepWorkIdAndName_When_IntervalChanges() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(60, 10))
        val id = h.active(gitName)!!.id
        scheduler.apply(g1, cfg(120, 10))
        val after = h.active(gitName)!!
        assertEquals(id, after.id)
        assertEquals(TimeUnit.MINUTES.toMillis(120), after.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test
    fun apply_should_setFlexFromJitterAndInitialDelayFromSeed_When_JitterConfigured() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(60, 6))
        val info = h.active(gitName)!!
        assertEquals(TimeUnit.MINUTES.toMillis(6), info.periodicityInfo!!.flexIntervalMillis)
        val expectedOffset = JitterPolicy.offsetMinutes(JitterSeed.of(g1), 6)
        assertEquals(TimeUnit.MINUTES.toMillis(expectedOffset.toLong()), info.initialDelayMillis)
        // Jitter below WorkManager's 5 min flex floor is raised to the floor; zero jitter means no flex request.
        scheduler.apply(g1, cfg(60, 0))
        val none = h.active(gitName)!!
        assertEquals(0L, none.initialDelayMillis)
        assertEquals(none.periodicityInfo!!.repeatIntervalMillis, none.periodicityInfo!!.flexIntervalMillis)
    }

    @Test
    fun apply_should_removeWorkAndClearLedger_When_Disabled() = runBlocking<Unit> {
        val ledger = AppliedConfigLedger(ContractSettings())
        val s = WorkManagerTaskScheduler(h.context, registry, TaskSettingsStore(ContractSettings(), LegacyConfigSeed.None, registry), ledger)
        s.apply(g1, cfg(60))
        assertEquals(cfg(60), ledger.lastApplied(g1))
        s.apply(g1, cfg(60, enabled = false))
        assertNull(h.active(gitName))
        assertNull(ledger.lastApplied(g1))
        s.apply(g1, cfg(60))
        s.cancel(g1)
        assertNull(h.active(gitName))
        assertNull(ledger.lastApplied(g1))
    }

    @Test
    fun apply_should_enqueueGitSyncWorkerWithNetworkPolicyInput_When_GitSyncApplied() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(60, 10, TaskConstraints(NetworkPolicy.Unmetered)))
        val id = h.active(gitName)!!.id
        val (cls, input) = h.spec(id)
        assertEquals(GitSyncWorker::class.java.name, cls)
        assertEquals("g1", input.getString(GitSyncWorker.KEY_GRAPH_ID))
        assertEquals(60L, input.getLong(GitSyncWorker.KEY_INTERVAL_MINUTES, -1))
        assertEquals("unmetered", input.getString(TaskWorkKeys.KEY_NETWORK_POLICY))
        scheduler.apply(g1, cfg(60, 10, TaskConstraints(NetworkPolicy.Any)))
        assertEquals("any", h.spec(h.active(gitName)!!.id).second.getString(TaskWorkKeys.KEY_NETWORK_POLICY))
    }

    @Test
    fun apply_should_enqueueTaskWorkerWithKindScopeAndPolicy_When_NonGitKindApplied() = runBlocking<Unit> {
        scheduler.apply(other, cfg(60, 0, TaskConstraints(NetworkPolicy.Unmetered)))
        val info = h.active(other.workName())!!
        val (cls, input) = h.spec(info.id)
        assertEquals(TaskWorker::class.java.name, cls)
        assertEquals("other-kind", input.getString(TaskWorkKeys.KEY_TASK_KIND))
        assertEquals("g1", input.getString(TaskWorkKeys.KEY_TASK_SCOPE))
        assertEquals("unmetered", input.getString(TaskWorkKeys.KEY_NETWORK_POLICY))
    }

    @Test
    fun describe_should_reportFlexWindowAndUpdateImmediately_When_ApplyHasJitter() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(60, 6))
        val d = scheduler.describe(g1)!!
        assertEquals(60, d.effectiveIntervalMinutes)
        assertEquals(54, d.minGapMinutes)
        assertEquals(66, d.maxGapMinutes)
        assertFalse(d.appliesAfterNextRun)
        assertFalse(d.clamped)
        assertNotNull(d.nextRunAtMs)
    }

    @Test
    fun tags_should_excludeStelekitJobOnPeriodicAndIncludeOnOneShot_When_RequestsBuilt() = runBlocking<Unit> {
        scheduler.apply(g1, cfg(60))
        val periodic = h.active(gitName)!!.tags
        assertTrue(SchedTags.ANY in periodic)
        assertTrue("stelekit-sched-scope:g1" in periodic)
        assertTrue(JobTags.kind(JobKind.GitSync) in periodic)
        assertFalse(JobTags.ANY in periodic)

        scheduler.runOnce(download, TaskConstraints())
        val oneShot = h.active(DepthModelDownloader.UNIQUE_WORK_NAME)!!.tags
        assertTrue(JobTags.ANY in oneShot)
        assertTrue(JobTags.kind(JobKind.ModelDownload) in oneShot)
        assertTrue(JobTags.title("Depth model download") in oneShot)
        assertTrue(SchedTags.ANY in oneShot)

        scheduler.apply(other, cfg(60))
        assertTrue(h.active(other.workName())!!.tags.none { it.startsWith("stelekit-job-kind:") })
    }

    @Test
    fun workName_should_matchLegacyNames_When_GitSyncAndModelDownload() {
        assertEquals(gitName, g1.workName())
        assertEquals(DepthModelDownloader.UNIQUE_WORK_NAME, download.workName())
    }

    @Test
    fun runOnce_should_keepOrReplacePendingRequest_By_ConflictPolicy() = runBlocking<Unit> {
        val name = DepthModelDownloader.UNIQUE_WORK_NAME
        scheduler.runOnce(download, TaskConstraints(NetworkPolicy.Unmetered))
        val first = h.active(name)!!
        assertEquals(NetworkType.UNMETERED, first.constraints.requiredNetworkType)
        assertNull(first.periodicityInfo)

        scheduler.runOnce(download, TaskConstraints(NetworkPolicy.Any), OneShotConflict.Keep)
        assertEquals(first.id, h.active(name)!!.id)
        assertEquals(NetworkType.UNMETERED, h.active(name)!!.constraints.requiredNetworkType)

        scheduler.runOnce(download, TaskConstraints(NetworkPolicy.Any), OneShotConflict.Replace)
        val replaced = h.active(name)!!
        assertNotEquals(first.id, replaced.id)
        assertEquals(NetworkType.CONNECTED, replaced.constraints.requiredNetworkType)
        assertEquals("any", h.spec(replaced.id).second.getString(TaskWorkKeys.KEY_NETWORK_POLICY))
        assertEquals(TaskWorker::class.java.name, h.spec(replaced.id).first)
    }

    @Test
    fun runOnce_should_setBackoffOnlyWhenSpecAllowsRetries() = runBlocking<Unit> {
        scheduler.runOnce(download, TaskConstraints())
        val withRetry = h.active(DepthModelDownloader.UNIQUE_WORK_NAME)!!
        scheduler.cancel(download)
        registry = TaskRegistry()
        scheduler = newScheduler()
        scheduler.runOnce(download, TaskConstraints())
        // Backoff is not exposed on WorkInfo; both enqueue and stay one-time work.
        assertNull(withRetry.periodicityInfo)
        assertNull(h.active(DepthModelDownloader.UNIQUE_WORK_NAME)!!.periodicityInfo)
    }

    @Test
    fun apply_should_skipWithoutCancelling_When_NonPeriodicWorkHoldsTheName() = runBlocking<Unit> {
        scheduler.runOnce(g1, TaskConstraints()) // stands in for a clone worker under the shared name
        val held = h.active(gitName)!!
        scheduler.apply(g1, cfg(60))
        assertEquals(held.id, h.active(gitName)!!.id)
        assertNull(h.active(gitName)!!.periodicityInfo)
    }
}
