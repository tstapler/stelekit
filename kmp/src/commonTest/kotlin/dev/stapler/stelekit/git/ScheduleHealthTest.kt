// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.ConstraintState
import dev.stapler.stelekit.jobs.schedule.LateCause
import dev.stapler.stelekit.jobs.schedule.MapSettings
import dev.stapler.stelekit.jobs.schedule.RunSource
import dev.stapler.stelekit.jobs.schedule.ScheduleLateness
import dev.stapler.stelekit.jobs.schedule.SchedulerCapabilities
import dev.stapler.stelekit.jobs.schedule.SettingsReadHealthSource
import dev.stapler.stelekit.jobs.schedule.SettingsTaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskRunRecord
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 4.1.2: the health inputs the status line reads, over the real git last-success source. */
class ScheduleHealthTest {
    private val key = TaskKey(GitSyncTask.KIND, TaskScope("g1"))
    private val now = 10_000_000_000L
    private val hour = 3_600_000L
    private val state = ConstraintState(online = true, isMetered = false, charging = null)
    private val android = SchedulerCapabilities(15, 5, true, true, true, runsWhenClosed = true, schedulesWork = true)
    private val desktop = SchedulerCapabilities(1, 0, false, false, true, runsWhenClosed = false, schedulesWork = true)

    private class Health(var failure: Long?) : SettingsReadHealthSource {
        override fun lastFailureMs() = failure
        override fun clear() { failure = null }
    }

    private class Fixture {
        val settings = MapSettings()
        val runLog = SettingsTaskRunLog(settings)
        val staleness = SyncStalenessStore(settings)
        val source = GitLastSuccessSource(runLog, staleness)
    }

    private fun record(at: Long, result: TaskRunResult) = TaskRunRecord(key, RunSource.Background, null, at, result, state)

    private suspend fun health(f: Fixture, caps: SchedulerCapabilities = android, readHealth: SettingsReadHealthSource? = null, enabled: Boolean = true) =
        computeScheduleHealth(key, enabled, 60, caps, f.source, f.runLog, readHealth, now)

    @Test
    fun health_should_beLate_When_Last3hAgoAndAndroid() = runTest {
        val f = Fixture()
        f.runLog.append(record(now - 3 * hour, TaskRunResult.Success))
        assertEquals(ScheduleLateness.Late(3 * hour, LateCause.Unattributed), health(f).lateness)
    }

    @Test
    fun health_should_neverBeLate_When_DesktopOrDisabled() = runTest {
        val f = Fixture()
        f.runLog.append(record(now - 9 * hour, TaskRunResult.Success))
        assertEquals(ScheduleHealth.Healthy, health(f, desktop))
        assertEquals(ScheduleHealth.Healthy, health(f, enabled = false))
    }

    @Test
    fun health_should_beOnTime_When_ForegroundMergeIs10MinAgoAndRunLogHasOnlyOldSkips() = runTest {
        val f = Fixture()
        f.runLog.append(record(now - 5 * hour, TaskRunResult.Skipped("metered")))
        f.staleness.recordMerged("g1", now - 10 * 60_000L)
        assertEquals(ScheduleLateness.OnTime, health(f).lateness)
    }

    @Test
    fun health_should_attributeDeliberateSkip_When_NewestRunsAreVaultLocked() = runTest {
        val f = Fixture()
        f.runLog.append(record(now - 5 * hour, TaskRunResult.Success))
        // Attribution looks at the three newest runs: all must be the same deliberate skip.
        listOf(3L, 2L, 1L).forEach { f.runLog.append(record(now - it * hour, TaskRunResult.Skipped("vault-locked"))) }
        assertEquals(ScheduleLateness.Late(5 * hour, LateCause.DeliberateSkip("vault-locked")), health(f).lateness)
    }

    @Test
    fun health_should_flagUnreadableSettings_When_FailureNewerThanSuccessAndLastRunIsUnreadableOrAbsent() = runTest {
        val absent = Fixture()
        assertTrue(health(absent, readHealth = Health(now - hour)).settingsUnreadable)
        val skipped = Fixture().apply {
            runLog.append(record(now - 5 * hour, TaskRunResult.Success))
            runLog.append(record(now - 2 * hour, TaskRunResult.Skipped("settings-unreadable")))
        }
        assertTrue(health(skipped, readHealth = Health(now - hour)).settingsUnreadable)
        // A success newer than the failure clears it.
        val recovered = Fixture().apply { runLog.append(record(now - 10 * 60_000L, TaskRunResult.Success)) }
        assertFalse(health(recovered, readHealth = Health(now - hour)).settingsUnreadable)
        assertFalse(health(recovered, readHealth = null).settingsUnreadable)
    }

    @Test
    fun health_should_failClosed_When_RunLogOrHealthSourceThrows() = runTest {
        val f = Fixture()
        val throwingLog = object : TaskRunLog {
            override suspend fun append(record: TaskRunRecord) = Unit
            override suspend fun recent(key: TaskKey): List<TaskRunRecord> = error("boom")
            override suspend fun lastSuccessAtMs(key: TaskKey): Long? = error("boom")
        }
        assertEquals(ScheduleHealth.Healthy, computeScheduleHealth(key, true, 60, android, f.source, throwingLog, null, now))
        val throwingHealth = object : SettingsReadHealthSource {
            override fun lastFailureMs(): Long? = error("boom")
            override fun clear() = Unit
        }
        assertEquals(ScheduleHealth.Healthy, health(f, readHealth = throwingHealth))
    }
}
