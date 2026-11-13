// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScheduledTaskReconcilerTest {
    private lateinit var h: WorkManagerTestHarness
    private val git = TaskKind("git-sync")
    private val settings = ContractSettings()

    @Before
    fun setUp() {
        h = WorkManagerTestHarness()
        WorkManagerTaskScheduler.clearProcessStatic()
        AppliedConfigLedger.clearProcessStatic()
    }

    private fun scheduler(): WorkManagerTaskScheduler {
        val registry = TaskRegistry()
        return WorkManagerTaskScheduler(
            h.context, registry, TaskSettingsStore(settings, LegacyConfigSeed.None, registry), AppliedConfigLedger(settings),
            workers = dev.stapler.stelekit.git.GitSyncWorkerBinding.resolver,
        )
    }

    private suspend fun seed(vararg scopes: String) {
        val s = scheduler()
        for (scope in scopes) s.apply(TaskKey(git, TaskScope(scope)), TaskConfig(true, Schedule.of(60, 0), TaskConstraints()))
    }

    private fun live(scope: String) = h.active(TaskKey(git, TaskScope(scope)).workName())

    @Test
    fun reconcile_should_cancelOnlyAbsentScopes_When_KnownNonEmpty() = runBlocking<Unit> {
        seed("g1", "ghost")
        val cancelled = ScheduledTaskReconciler(h.context) { KnownScopesResult.Known(setOf(TaskScope("g1"))) }.reconcile()
        assertEquals(setOf(TaskScope("ghost")), cancelled)
        assertNotNull(live("g1"))
        assertNull(live("ghost"))
    }

    @Test
    fun reconcile_should_cancelNothingAndWriteNoTombstone_When_Unknown() = runBlocking<Unit> {
        seed("g1", "ghost")
        val cancelled = ScheduledTaskReconciler(h.context) { KnownScopesResult.Unknown }.reconcile()
        assertEquals(emptySet(), cancelled)
        assertNotNull(live("g1"))
        assertNotNull(live("ghost"))
        assertEquals(emptyMap(), settings.values.filterKeys { it.startsWith("task_entry") })
    }

    @Test
    fun reconcile_should_cancelNothing_When_KnownIsEmptyOrSourceThrows() = runBlocking<Unit> {
        seed("ghost")
        assertEquals(emptySet(), ScheduledTaskReconciler(h.context) { KnownScopesResult.Known(emptySet()) }.reconcile())
        assertEquals(emptySet(), ScheduledTaskReconciler(h.context) { error("unreadable") }.reconcile())
        assertNotNull(live("ghost"))
    }

    @Test
    fun reconcile_should_neverCancelAppWideScope_When_ScopeEmpty() = runBlocking<Unit> {
        scheduler().apply(TaskKey(TaskKind("model-download"), TaskScope("")), TaskConfig(true, Schedule.of(60, 0), TaskConstraints()))
        seed("g1")
        ScheduledTaskReconciler(h.context) { KnownScopesResult.Known(setOf(TaskScope("g1"))) }.reconcile()
        assertNotNull(h.active(TaskKey(TaskKind("model-download"), TaskScope("")).workName()))
    }
}
