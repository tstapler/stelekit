// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InProcessJobsTest {
    private suspend fun InProcessJobs.snapshot() = jobs.first()

    @Test
    fun track_should_ListJobAsRunningThenSucceeded_When_BlockReturns() = runTest {
        val jobs = InProcessJobs()

        jobs.track(JobKind.GitSync, "Syncing") { handle ->
            handle.progress(40)
            assertEquals(JobState.Running(40), jobs.snapshot().single().state)
        }

        assertEquals(JobState.Succeeded, jobs.snapshot().single().state)
    }

    @Test
    fun track_should_RecordFailureReasonAndRethrow_When_BlockThrows() = runTest {
        val jobs = InProcessJobs()

        assertFailsWith<IllegalStateException> {
            jobs.track(JobKind.StorageMove, "Moving") { error("disk full") }
        }

        assertEquals(JobState.Failed("disk full"), jobs.snapshot().single().state)
    }

    @Test
    fun track_should_MarkCancelledAndRethrow_When_BlockIsCancelled() = runTest {
        val jobs = InProcessJobs()

        assertFailsWith<CancellationException> {
            jobs.track(JobKind.PageCopy, "Copying") { throw CancellationException("stop") }
        }

        assertEquals(JobState.Cancelled, jobs.snapshot().single().state)
    }

    @Test
    fun handle_should_KeepFirstOutcome_When_ReportedTwice() = runTest {
        val jobs = InProcessJobs()
        val handle = jobs.begin(JobKind.GitSync, "Syncing")

        handle.fail("first")
        handle.succeed()

        assertEquals(JobState.Failed("first"), jobs.snapshot().single().state)
    }

    @Test
    fun cancel_should_InvokeHook_OnlyForJobsThatRegisteredOne() = runTest {
        val jobs = InProcessJobs()
        var cancelled = 0
        val withHook = jobs.begin(JobKind.PageCopy, "a") { cancelled++ }
        jobs.begin(JobKind.GitSync, "b")
        val list = jobs.snapshot()

        jobs.cancel(list.first { it.title == "a" }.id)
        jobs.cancel(list.first { it.title == "b" }.id)

        assertEquals(1, cancelled)
        assertTrue(list.first { it.title == "a" }.cancellable)
        assertTrue(!list.first { it.title == "b" }.cancellable)
        withHook.cancelled()
    }

    @Test
    fun clearFinished_should_DropFinishedJobsAndKeepActiveOnes() = runTest {
        val jobs = InProcessJobs()
        jobs.begin(JobKind.GitSync, "done").succeed()
        jobs.begin(JobKind.GitSync, "active")

        jobs.clearFinished()

        assertEquals(listOf("active"), jobs.snapshot().map { it.title })
    }

    @Test
    fun finishedJobs_should_BeCappedAtMaxFinished_DroppingTheOldest() = runTest {
        val jobs = InProcessJobs(maxFinished = 2)
        repeat(4) { jobs.begin(JobKind.GitSync, "j$it").succeed() }

        assertEquals(setOf("j2", "j3"), jobs.snapshot().map { it.title }.toSet())
    }

    @Test
    fun composite_should_MergeSourcesWithActiveFirst() = runTest {
        val a = InProcessJobs()
        val b = InProcessJobs()
        a.begin(JobKind.GitSync, "a-done").succeed()
        b.begin(JobKind.PageCopy, "b-active")

        val merged = CompositeJobRegistry(listOf(a, b)).jobs.first()

        assertEquals(listOf("b-active", "a-done"), merged.map { it.title })
        assertIs<JobState.Running>(merged.first().state)
        assertNull(merged.firstOrNull { it.title == "missing" })
    }
}
