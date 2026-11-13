// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.git.GitSyncWorkerBinding
import dev.stapler.stelekit.jobs.JobKind
import kotlinx.coroutines.runBlocking
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import dev.stapler.stelekit.git.GitCloneWorker
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScheduleStartupTest {
    private lateinit var h: WorkManagerTestHarness
    private lateinit var registry: TaskRegistry
    private lateinit var settings: ContractSettings
    private val seededKeys = mutableListOf<TaskKey>()
    private var legacyEnabled = true

    private val kind = TaskKind("git-sync")
    private val g1 = TaskKey(kind, TaskScope("g1"))
    private val g2 = TaskKey(kind, TaskScope("g2"))
    private val cfg = TaskConfig(true, Schedule.of(60, 0), TaskConstraints())

    @Before
    fun setUp() {
        h = WorkManagerTestHarness()
        WorkManagerTaskScheduler.clearProcessStatic()
        AppliedConfigLedger.clearProcessStatic()
        registry = TaskRegistry()
        registry.register(
            kind,
            TaskSpec(JobKind.GitSync, "Git sync", TaskShape.Periodic(cfg.schedule), cfg, false, 0),
        ) { _, _ -> TaskRunResult.Success }
        registry.register(
            TaskKind("model-download"),
            TaskSpec(null, "Model", TaskShape.OneShot, cfg, false, 0),
        ) { _, _ -> TaskRunResult.Success }
        settings = ContractSettings()
        seededKeys.clear()
        legacyEnabled = true
    }

    private fun store(settings: dev.stapler.stelekit.platform.Settings = this.settings) = TaskSettingsStore(
        settings,
        LegacyConfigSeed { key -> seededKeys += key; if (legacyEnabled) cfg else null },
        registry,
    )

    private fun scheduler(settings: dev.stapler.stelekit.platform.Settings = this.settings) = WorkManagerTaskScheduler(
        h.context, registry, store(settings), AppliedConfigLedger(settings), workers = GitSyncWorkerBinding.resolver,
    )

    private fun startup(
        known: KnownScopesResult = KnownScopesResult.Known(setOf(TaskScope("g1"), TaskScope("g2"))),
        active: String? = "g1",
        settings: dev.stapler.stelekit.platform.Settings = this.settings,
        reconcile: suspend () -> Set<TaskScope> = { emptySet() },
        ready: Boolean = true,
        onUnreadable: (Long) -> Unit = {},
    ) = ScheduleStartup(
        store(settings), scheduler(settings), registry, AppliedConfigLedger(settings), { known }, { active },
        reconcile, { ready }, { key -> isCloneWorkLive(h.context, key) }, onUnreadable,
    )

    private fun live(key: TaskKey) = h.active(key.workName())

    @Test
    fun run_should_migrateActiveGraphOnlyAndChangeNothingOnSecondStart_When_LegacyFlagTrue() = runBlocking<Unit> {
        startup().run()
        assertEquals(listOf(g1), seededKeys, "only the active graph, only periodic kinds, are seeded")
        assertTrue(store().read(g1).enabled)
        val entryAfterFirst = settings.getString(TaskSettingsKeys.entry(g1), "")
        assertTrue(entryAfterFirst.isNotEmpty())
        startup().run()
        assertEquals(listOf(g1), seededKeys, "second start never re-seeds")
        assertEquals(entryAfterFirst, settings.getString(TaskSettingsKeys.entry(g1), ""))
        assertEquals("", settings.getString(TaskSettingsKeys.entry(g2), ""), "non-active graph untouched")
    }

    /** A queued (delayed) one-time request under the shared work name, the way a live clone sits there. */
    private fun enqueueLiveClone(key: TaskKey) {
        val request = OneTimeWorkRequestBuilder<GitCloneWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        h.wm.enqueueUniqueWork(key.workName(), ExistingWorkPolicy.REPLACE, request).result.get()
    }

    @Test
    fun run_should_resumeStalePausedSnapshot_When_NoCloneWorkIsLive() = runBlocking<Unit> {
        val ledger = AppliedConfigLedger(settings)
        ledger.recordApplied(g1, cfg)
        ledger.snapshotPaused(g1, cfg)
        assertNull(live(g1))
        val report = startup().run()
        assertNull(ledger.pausedSnapshot(g1), "stale snapshot cleared")
        assertNotNull(live(g1)?.periodicityInfo, "periodic work re-enqueued")
        assertEquals(setOf(g1), report.resumed)
    }

    @Test
    fun run_should_stayPaused_When_CloneWorkIsLive() = runBlocking<Unit> {
        val ledger = AppliedConfigLedger(settings)
        ledger.recordApplied(g1, cfg)
        ledger.snapshotPaused(g1, cfg)
        enqueueLiveClone(g1)
        val report = startup().run()
        assertNotNull(ledger.pausedSnapshot(g1), "snapshot kept while the clone runs")
        assertNull(live(g1)?.periodicityInfo, "only the clone request is live")
        assertEquals(emptySet(), report.resumed)
    }

    @Test
    fun reapplyAll_should_enqueueFromLedger_When_ForceStopWipedWorkManager() = runBlocking<Unit> {
        AppliedConfigLedger(settings).recordApplied(g1, cfg)
        assertNull(live(g1))
        val report = startup().run()
        assertNotNull(live(g1))
        assertEquals(setOf(g1), report.reapplied)
        assertNull(live(g2), "no ledger entry, nothing enqueued")
    }

    @Test
    fun reapplyAll_should_leaveLiveWorkUntouched_When_AlreadyScheduled() = runBlocking<Unit> {
        scheduler().apply(g1, cfg)
        val before = live(g1)!!.id
        val report = startup().run()
        assertEquals(emptySet(), report.reapplied)
        assertEquals(before, live(g1)!!.id)
    }

    @Test
    fun reapplyAll_should_neverCallSeedOrOpenGraphDb_When_LedgerHasEntries() = runBlocking<Unit> {
        AppliedConfigLedger(settings).recordApplied(g2, cfg)
        startup(active = null).run()
        assertNotNull(live(g2))
        assertEquals(emptyList(), seededKeys)
    }

    @Test
    fun run_should_enqueueNothingAndNotCrash_When_KnownScopesUnknownOrSettingsThrow() = runBlocking<Unit> {
        AppliedConfigLedger(settings).recordApplied(g1, cfg)
        startup(known = KnownScopesResult.Unknown).run()
        assertNull(live(g1))

        val throwing = object : dev.stapler.stelekit.platform.Settings by settings {
            override fun getString(key: String, defaultValue: String): String = throw IllegalStateException("keystore")
            override fun containsKey(key: String): Boolean = throw IllegalStateException("keystore")
        }
        var failureAt: Long? = null
        val report = startup(settings = throwing, onUnreadable = { failureAt = it }).run()
        assertNull(live(g1))
        assertEquals(emptySet(), report.reapplied)
        assertNotNull(failureAt, "unreadable settings feed the health signal")
    }

    @Test
    fun run_should_runReconcileFailClosed_When_ReconcileThrows() = runBlocking<Unit> {
        AppliedConfigLedger(settings).recordApplied(g1, cfg)
        val report = startup(reconcile = { error("boom") }).run()
        assertEquals(emptySet(), report.cancelledScopes)
        assertNotNull(live(g1), "a failing step never stops the next one")
    }

    @Test
    fun run_should_cancelOrphansViaReconciler_When_KnownScopesKnown() = runBlocking<Unit> {
        val s = scheduler()
        s.apply(g1, cfg)
        s.apply(TaskKey(kind, TaskScope("ghost")), cfg)
        val reconciler = ScheduledTaskReconciler(h.context) { KnownScopesResult.Known(setOf(TaskScope("g1"))) }
        val report = startup(reconcile = reconciler::reconcile).run()
        assertEquals(setOf(TaskScope("ghost")), report.cancelledScopes)
        assertNotNull(live(g1))
        assertNull(h.active(TaskKey(kind, TaskScope("ghost")).workName()))
    }

    @Test
    fun run_should_doNothing_When_WorkManagerNotReady() = runBlocking<Unit> {
        AppliedConfigLedger(settings).recordApplied(g1, cfg)
        val report = startup(ready = false).run()
        assertEquals(ScheduleStartupReport(), report)
        assertNull(live(g1))
        assertEquals(emptyList(), seededKeys)
    }

    @Test
    fun run_should_openNoNonActiveGraphDbAndMakeNoNetworkCall_When_StartupRuns() = runBlocking<Unit> {
        // Startup only touches Settings, the ledger and WorkManager; its constructor takes no graph or network port.
        val report = startup(active = "g1").run()
        assertEquals(setOf(g1), report.migrated)
        assertTrue(seededKeys.none { it.scope != TaskScope("g1") })
    }
}
