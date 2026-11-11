// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.jobs.schedule.ConstraintState
import dev.stapler.stelekit.jobs.schedule.JitterPreset
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskRunContext
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.TaskShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitSyncTaskMappingTest {
    private val key = TaskKey(GitSyncTask.KIND, TaskScope("g1"))
    private val ctx = TaskRunContext(null, 0L, false, ConstraintState(online = true, isMetered = false, charging = null))

    @Test
    fun map_should_returnPartialSuccessSkippedOrFailed_When_EachTickOutcomeVariant() {
        assertEquals(TaskRunResult.Success, TickOutcome.Success.toTaskRunResult())
        assertEquals(TaskRunResult.Partial("fetched"), TickOutcome.Fetched.toTaskRunResult())
        assertEquals(TaskRunResult.Failed("boom", retryable = false), TickOutcome.Failure("boom").toTaskRunResult())
        assertEquals(TaskRunResult.Skipped("busy"), TickOutcome.Skip(SkipReason.Busy).toTaskRunResult())
        assertEquals(TaskRunResult.Skipped("offline"), TickOutcome.Skip(SkipReason.Offline).toTaskRunResult())
        val expected = mapOf(
            ScheduledSkipReason.EditingInProgress to "editing-in-progress",
            ScheduledSkipReason.Busy to "busy",
            ScheduledSkipReason.VaultLocked to "vault-locked",
            ScheduledSkipReason.ConflictPending to "conflict-pending",
            ScheduledSkipReason.Backoff to "backoff",
            ScheduledSkipReason.Offline to "offline",
            ScheduledSkipReason.RepoState to "repo-state",
            ScheduledSkipReason.FirstSyncUnconfirmed to "first-sync-unconfirmed",
        )
        assertEquals(ScheduledSkipReason.entries.toSet(), expected.keys, "every policy reason has a code")
        for ((reason, code) in expected) {
            assertEquals(TaskRunResult.Skipped(code), TickOutcome.Skip(SkipReason.Policy(reason)).toTaskRunResult())
        }
    }

    @Test
    fun map_should_neverReturnFailed_When_OutcomeIsSkip() {
        val skips = listOf(SkipReason.Busy, SkipReason.Offline) + ScheduledSkipReason.entries.map { SkipReason.Policy(it) }
        for (reason in skips) {
            assertFalse(TickOutcome.Skip(reason).toTaskRunResult() is TaskRunResult.Failed, "$reason")
        }
    }

    @Test
    fun run_should_preferLiveTickAndNotTouchColdPath_When_ServiceRegistered() = runTest {
        var coldCalls = 0
        val task = GitSyncTask(liveTick = { TickOutcome.Success }, cold = { coldCalls++; TickOutcome.Fetched })
        assertEquals(TaskRunResult.Success, task.run(key, ctx))
        assertEquals(0, coldCalls)
    }

    @Test
    fun run_should_runColdPathWithGraphId_When_NoLiveService() = runTest {
        var seen: String? = null
        val task = GitSyncTask(liveTick = { null }, cold = { seen = it; TickOutcome.Fetched })
        assertEquals(TaskRunResult.Partial("fetched"), task.run(key, ctx))
        assertEquals("g1", seen)
    }

    @Test
    fun run_should_skipDeliberately_When_NoLiveServiceAndNoColdRunner() = runTest {
        assertEquals(TaskRunResult.Skipped(GitSyncTask.NO_SERVICE), GitSyncTask(liveTick = { null }).run(key, ctx))
    }

    @Test
    fun run_should_returnNonRetryableFailed_When_TickThrows() = runTest {
        val task = GitSyncTask(liveTick = { throw IllegalStateException("secret-token-value") })
        val result = task.run(key, ctx)
        assertEquals(TaskRunResult.Failed("IllegalStateException", retryable = false), result)
    }

    @Test
    fun run_should_rethrowCancellation_When_TickIsCancelled() = runTest {
        val task = GitSyncTask(liveTick = { throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { task.run(key, ctx) }
    }

    @Test
    fun spec_should_useALittleJitterAndBeDisabledByDefault_When_Inspected() {
        assertEquals(6, JitterPreset.ALittle.minutesFor(60))
        val cfg = GitSyncTask.spec.defaultConfig
        assertFalse(cfg.enabled)
        assertEquals(Schedule.of(60, 6), cfg.schedule)
        assertEquals(NetworkPolicy.Any, cfg.constraints.network)
        assertIs<TaskShape.Periodic>(GitSyncTask.spec.shape)
        assertTrue(GitSyncTask.spec.allowedInColdPath)
    }

    @Test
    fun register_should_beNoOp_When_TwoGitSyncTaskInstancesShareTheSpec() {
        val registry = TaskRegistry()
        registry.register(GitSyncTask.KIND, GitSyncTask.spec, GitSyncTask())
        registry.register(GitSyncTask.KIND, GitSyncTask.spec, GitSyncTask())
        assertEquals(setOf(GitSyncTask.KIND), registry.kinds())
    }
}
