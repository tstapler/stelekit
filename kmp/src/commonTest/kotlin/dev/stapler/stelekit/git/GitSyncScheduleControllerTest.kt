// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.AppliedConfigLedger
import dev.stapler.stelekit.jobs.schedule.FakeBackgroundTaskScheduler
import dev.stapler.stelekit.jobs.schedule.MapSettings
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskEntry
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskOverrides
import dev.stapler.stelekit.jobs.schedule.TaskReadResult
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.TaskSettingsStore
import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitSyncScheduleControllerTest {
    private val g1 = TaskKey(GitSyncTask.KIND, TaskScope("g1"))
    private val settings = MapSettings()
    private val source = FakeGitLegacyConfigSource(LegacyGitConfigResult.Found(hasRemote = true, pollIntervalMinutes = 30))

    private val ledger = AppliedConfigLedger(settings)
    private val store = TaskSettingsStore(settings, LegacyGitSyncMigration(settings, source), TaskRegistry())

    private fun scheduler(runsWhenClosed: Boolean) = FakeBackgroundTaskScheduler(
        capabilities = FakeBackgroundTaskScheduler.DEFAULT_CAPABILITIES.copy(runsWhenClosed = runsWhenClosed),
        ledger = ledger,
    )

    private fun controller(fake: FakeBackgroundTaskScheduler) = GitSyncScheduleController(store, fake)

    private val stored = TaskConfig(true, Schedule.of(60, 10), TaskConstraints(NetworkPolicy.Unmetered))

    @Test
    fun reconcile_should_ApplyStoredConfigOnce_When_SchedulerRunsWhenClosed() = runTest {
        store.write(g1, TaskEntry(true, TaskOverrides(stored.schedule, stored.constraints)))
        val fake = scheduler(runsWhenClosed = true)

        assertEquals(stored, controller(fake).reconcile("g1"))
        assertEquals(1, fake.calls.count { it.startsWith("apply:") })
        assertEquals(stored, ledger.lastApplied(g1))
    }

    @Test
    fun reconcile_should_ApplyDisabled_When_StoredDisabled() = runTest {
        store.write(g1, TaskEntry(false, null))
        val fake = scheduler(runsWhenClosed = true)

        assertFalse(controller(fake).reconcile("g1")!!.enabled)
        assertEquals(1, fake.calls.count { it.startsWith("apply:") })
        assertEquals(null, ledger.lastApplied(g1))
    }

    @Test
    fun controller_should_NeverCallApply_When_SchedulerDoesNotRunWhenClosed() = runTest {
        val fake = scheduler(runsWhenClosed = false)
        val c = controller(fake)

        c.reconcile("g1")
        c.setEnabled("g1", true, Schedule.of(15, 0))
        c.setSchedule("g1", Schedule.of(45, 5))
        c.setNetwork("g1", NetworkPolicy.Unmetered)

        assertEquals(emptyList(), fake.calls.filter { it.startsWith("apply:") })
        val config = c.read("g1")
        assertTrue(config.enabled)
        assertEquals(Schedule.of(45, 5), config.schedule)
        assertEquals(NetworkPolicy.Unmetered, config.constraints.network)
    }

    @Test
    fun ensureMigrated_should_SeedFromLegacyFlagRemoteAndInterval_When_NoEntryExists() = runTest {
        settings.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, true)
        val c = controller(scheduler(runsWhenClosed = false))

        assertIs<TaskReadResult.Ok>(c.ensureMigrated("g1"))
        val config = c.read("g1")
        assertTrue(config.enabled)
        assertEquals(30, config.schedule.intervalMinutes)
        assertEquals(0, config.schedule.jitterMinutes)
        assertEquals(1, source.calls, "the second read does not re-read the legacy source")
        assertEquals(true, settings.getBoolean(SCHEDULED_SYNC_ENABLED_KEY, false), "legacy flag untouched")
    }

    @Test
    fun ensureMigrated_should_NotSeed_When_GraphWasTombstoned() = runTest {
        settings.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, true)
        store.tombstone(g1)
        val c = controller(scheduler(runsWhenClosed = false))

        c.ensureMigrated("g1")
        assertFalse(c.read("g1").enabled)
        assertEquals(0, source.calls)
    }

    @Test
    fun reconcile_should_ReturnNullAndApplyNothing_When_LegacyConfigUnavailable() = runTest {
        settings.putBoolean(SCHEDULED_SYNC_ENABLED_KEY, true)
        source.result = LegacyGitConfigResult.Unavailable
        val fake = scheduler(runsWhenClosed = true)

        assertEquals(null, controller(fake).reconcile("g1"))
        assertEquals(emptyList(), fake.calls)
    }

    @Test
    fun setEnabled_should_UseDefaultSchedule_When_EnablingEntryWithNoSchedule() = runTest {
        val c = controller(scheduler(runsWhenClosed = true))

        val config = c.setEnabled("g1", true, Schedule.of(15, 0))!!
        assertTrue(config.enabled)
        assertEquals(15, config.schedule.intervalMinutes)
    }

    @Test
    fun setEnabledFalse_should_PersistAndApplyDisabled_When_PreviouslyEnabled() = runTest {
        val fake = scheduler(runsWhenClosed = true)
        val c = controller(fake)
        c.setEnabled("g1", true, Schedule.of(15, 0))

        assertFalse(c.setEnabled("g1", false)!!.enabled)
        assertFalse(store.read(g1).enabled)
        assertEquals(null, ledger.lastApplied(g1))
    }

    @Test
    fun setSchedule_should_KeepEnabledAndConstraints() = runTest {
        val c = controller(scheduler(runsWhenClosed = false))
        c.setEnabled("g1", true, Schedule.of(15, 0))
        c.setNetwork("g1", NetworkPolicy.Unmetered)

        val config = c.setSchedule("g1", Schedule.of(120, 20))!!
        assertTrue(config.enabled)
        assertEquals(NetworkPolicy.Unmetered, config.constraints.network)
        assertEquals(Schedule.of(120, 20), config.schedule)
    }
}
