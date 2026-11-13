// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.ConstraintState
import dev.stapler.stelekit.jobs.schedule.FakeBackgroundTaskScheduler
import dev.stapler.stelekit.jobs.schedule.clamp
import dev.stapler.stelekit.jobs.schedule.lateness
import dev.stapler.stelekit.jobs.schedule.MapSettings
import dev.stapler.stelekit.jobs.schedule.RunSource
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.ScheduleLateness
import dev.stapler.stelekit.jobs.schedule.SettingsTaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskRunRecord
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitLastSuccessSourceTest {
    private val key = TaskKey(GitSyncTask.KIND, TaskScope("g1"))
    private val now = 10_000_000L
    private val min = 60_000L
    private val state = ConstraintState(online = true, isMetered = false, charging = null)

    private fun record(at: Long, result: TaskRunResult) = TaskRunRecord(key, RunSource.Background, null, at, result, state)

    private class Fixture {
        val settings = MapSettings()
        val runLog = SettingsTaskRunLog(settings)
        val staleness = SyncStalenessStore(settings)
    }

    @Test
    fun lastSuccessAtMs_should_takeMaxOfRunLogAndStaleness_When_ForegroundMergeNewer() = runTest {
        val f = Fixture()
        f.runLog.append(record(now - 300 * min, TaskRunResult.Skipped("vault-locked")))
        f.staleness.recordMerged("g1", now - 10 * min)
        val source = GitLastSuccessSource(f.runLog, f.staleness)

        assertEquals(now - 10 * min, source.lastSuccessAtMs(key))
        assertEquals(ScheduleLateness.OnTime, lateness(key, Schedule.of(60, 0).clamp(FakeBackgroundTaskScheduler.DEFAULT_CAPABILITIES), source, now))

        f.runLog.append(record(now - 5 * min, TaskRunResult.Success))
        assertEquals(now - 5 * min, source.lastSuccessAtMs(key), "a newer background success wins")
    }

    @Test
    fun lastSuccessAtMs_should_countFetchedAndIgnoreErrors_When_StalenessHasBoth() = runTest {
        val f = Fixture()
        f.staleness.recordMerged("g1", 1_000L)
        f.staleness.recordFetched("g1", 5_000L, behindCount = 2)
        f.staleness.recordError("g1", 9_000L, "IllegalStateException")
        assertEquals(5_000L, GitLastSuccessSource(f.runLog, f.staleness).lastSuccessAtMs(key))
    }

    @Test
    fun lastSuccessAtMs_should_returnNull_When_NothingRecorded() = runTest {
        val f = Fixture()
        assertNull(GitLastSuccessSource(f.runLog, f.staleness).lastSuccessAtMs(key))
    }

    @Test
    fun lastSuccessAtMs_should_returnNullNotLate_When_ReadFails() = runTest {
        val settings = MapSettings().apply { failReads = true }
        val source = GitLastSuccessSource(SettingsTaskRunLog(settings), SyncStalenessStore(settings))
        assertNull(source.lastSuccessAtMs(key))
    }

    @Test
    fun lastSuccessAtMs_should_useStaleness_When_RunLogThrows() = runTest {
        val f = Fixture()
        f.staleness.recordMerged("g1", 7_000L)
        val throwingLog = object : TaskRunLog {
            override suspend fun append(record: TaskRunRecord) = Unit
            override suspend fun recent(key: TaskKey) = emptyList<TaskRunRecord>()
            override suspend fun lastSuccessAtMs(key: TaskKey): Long? = throw IllegalStateException("log unreadable")
        }
        assertEquals(7_000L, GitLastSuccessSource(throwingLog, f.staleness).lastSuccessAtMs(key))
    }
}
