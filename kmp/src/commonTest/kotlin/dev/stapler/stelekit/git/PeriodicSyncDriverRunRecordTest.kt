// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.ConstraintState
import dev.stapler.stelekit.jobs.schedule.MapSettings
import dev.stapler.stelekit.jobs.schedule.RunSource
import dev.stapler.stelekit.jobs.schedule.SettingsTaskRunLog
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PeriodicSyncDriverRunRecordTest {
    private val intervalMs = 15 * 60_000L
    private val online = ConstraintState(online = true, isMetered = null, charging = null)

    private class Recorded(val result: TaskRunResult, val state: ConstraintState)

    private fun TestScope.driver(
        recorder: TickRunRecorder,
        outcomes: ArrayDeque<TickOutcome>,
    ): Pair<PeriodicSyncDriver, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return PeriodicSyncDriver(
            tick = { outcomes.removeFirst() }, scope = scope, label = "run-record",
            recorder = recorder, constraintState = { online },
        ) to scope
    }

    @Test
    fun recorder_should_ReceiveOneMappedResultPerTick_ForEachOutcome() = runTest {
        val seen = mutableListOf<Recorded>()
        val outcomes = ArrayDeque(
            listOf(
                TickOutcome.Success, TickOutcome.Fetched,
                TickOutcome.Skip(SkipReason.Policy(ScheduledSkipReason.VaultLocked)), TickOutcome.Failure("boom"),
            ),
        )
        val (d, scope) = driver({ _, r, s -> seen += Recorded(r, s) }, outcomes)
        d.start(15)
        repeat(4) { advanceTimeBy(intervalMs); runCurrent() }
        scope.cancel()

        assertEquals(
            listOf(TaskRunResult.Success, TaskRunResult.Partial("fetched"), TaskRunResult.Skipped("vault-locked"), TaskRunResult.Failed("boom", retryable = false)),
            seen.map { it.result },
        )
        assertTrue(seen.all { it.state == online })
    }

    @Test
    fun runLog_should_HoldForegroundRecordsForTheGitKey_When_UsingTheProductionRecorder() = runTest {
        val settings = MapSettings()
        val key = TaskKey(GitSyncTask.KIND, TaskScope("g1"))
        val (d, scope) = driver(SettingsTaskRunLog(settings).asForegroundRecorder(key), ArrayDeque(listOf(TickOutcome.Success)))
        d.start(15)
        advanceTimeBy(intervalMs); runCurrent()
        scope.cancel()

        val records = SettingsTaskRunLog(settings).recent(key)
        assertEquals(1, records.size)
        assertEquals(RunSource.Foreground, records.single().source)
        assertEquals(TaskRunResult.Success, records.single().result)
    }

    @Test
    fun driver_should_BehaveIdentically_When_RecorderThrows() = runTest {
        val outcomes = ArrayDeque<TickOutcome>(List(3) { TickOutcome.Failure("down") })
        val (d, scope) = driver({ _, _, _ -> error("recorder broke") }, outcomes)
        d.start(15)
        repeat(3) { advanceTimeBy(intervalMs); runCurrent() }
        scope.cancel()

        val status = d.status.value
        assertEquals(3, status.consecutiveFailures)
        assertTrue(status.paused, "pause-after-3-failures is unaffected by a throwing recorder")
        assertEquals("down", status.lastFailure)
    }

    @Test
    fun recorder_should_NotBeCalled_When_TickIsSkippedAsBusy() = runTest {
        var calls = 0
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val d = PeriodicSyncDriver(tick = { TickOutcome.Success }, scope = scope, isBusy = { true }, recorder = { _, _, _ -> calls++ })
        d.start(15)
        advanceTimeBy(intervalMs); runCurrent()
        scope.cancel()
        assertEquals(0, calls)
    }
}
