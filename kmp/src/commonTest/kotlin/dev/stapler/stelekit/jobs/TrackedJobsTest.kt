// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs

import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.ApplyFailure
import dev.stapler.stelekit.merge.CopyRunOutcome
import dev.stapler.stelekit.merge.MergeResult
import dev.stapler.stelekit.merge.PageFailure
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.db.StorageMoveUiState
import dev.stapler.stelekit.ui.reportStorageMove
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackedJobsTest {
    private val ok = MergeResult("m", total = 2, newPages = 2, combinedPages = 0, unchangedPages = 0, failed = emptyList(), conflicts = 0, assetsRenamed = 0)
    private val pageError = DomainError.StorageError.RelocationFailed("/x")

    private suspend fun InProcessJobs.only() = jobs.first().single().state

    @Test
    fun copyTracking_should_SucceedTheJob_When_EveryPageCopied() = runTest {
        val jobs = InProcessJobs()
        val tracking = CopyJobTracking(jobs)
        tracking.begin("Copying pages") {}

        tracking.settle(CopyRunOutcome.Finished(ok.right()))

        assertEquals(JobState.Succeeded, jobs.only())
    }

    @Test
    fun copyTracking_should_FailTheJobWithCount_When_SomePagesFailed() = runTest {
        val jobs = InProcessJobs()
        val tracking = CopyJobTracking(jobs)
        tracking.begin("Copying pages") {}

        tracking.settle(CopyRunOutcome.Finished(ok.copy(failed = listOf(PageFailure(0, "a", pageError))).right()))

        assertEquals(JobState.Failed("1 pages failed"), jobs.only())
    }

    @Test
    fun copyTracking_should_FailTheJob_When_RunReturnsApplyFailureOrCrashes() = runTest {
        val jobs = InProcessJobs()
        val tracking = CopyJobTracking(jobs)
        tracking.begin("Copying pages") {}
        tracking.settle(CopyRunOutcome.Finished(ApplyFailure.Failed(pageError).left()))
        assertEquals(JobState.Failed("The copy did not complete"), jobs.only())

        val crashed = InProcessJobs()
        val second = CopyJobTracking(crashed)
        second.begin("Copying pages") {}
        second.settle(CopyRunOutcome.Crashed("boom"))
        assertEquals(JobState.Failed("boom"), crashed.only())
    }

    @Test
    fun copyTracking_should_CancelTheJob_When_HostDeclinesToStart() = runTest {
        val jobs = InProcessJobs()
        val tracking = CopyJobTracking(jobs)
        tracking.begin("Copying pages") {}

        tracking.declined()
        tracking.settle(CopyRunOutcome.Finished(ok.right()))

        assertEquals(JobState.Cancelled, jobs.only())
    }

    @Test
    fun copyTracking_should_IgnoreOutcome_When_NoJobIsOpen() = runTest {
        val jobs = InProcessJobs()

        CopyJobTracking(jobs).settle(CopyRunOutcome.Crashed("late"))

        assertEquals(emptyList(), jobs.jobs.first())
    }

    @Test
    fun noJobTracker_should_AcceptEveryCallWithoutRecordingAnything() {
        val handle = NoJobTracker.begin(JobKind.GitSync, "x") {}
        handle.progress(1)
        handle.fail("x")
        handle.succeed()
        handle.cancelled()
    }

    @Test
    fun noBackgroundJobs_should_ListNothingAndIgnoreCancelAndClear() = runTest {
        NoBackgroundJobs.cancel("any")
        NoBackgroundJobs.clearFinished()

        assertEquals(emptyList(), NoBackgroundJobs.jobs.first())
        assertNull(CompositeJobRegistry(emptyList()).jobs.first().firstOrNull())
    }

    @Test
    fun storageMoveReporting_should_MapUiStatesOntoJobOutcomes() = runTest {
        fun run(state: StorageMoveUiState): JobState {
            val jobs = InProcessJobs()
            reportStorageMove(jobs.begin(JobKind.StorageMove, "Moving"), state)
            return kotlinx.coroutines.runBlocking { jobs.only() }
        }

        assertEquals(JobState.Running(50), run(StorageMoveUiState.Copying(5, 10)))
        assertEquals(JobState.Running(null), run(StorageMoveUiState.Copying(0, 0)))
        assertEquals(JobState.Succeeded, run(StorageMoveUiState.Summary))
        assertEquals(JobState.Failed(pageError.message), run(StorageMoveUiState.Failed(pageError)))
        val reopen = DomainError.StorageError.ReopenFailed("g")
        assertEquals(JobState.Failed(reopen.message), run(StorageMoveUiState.ReopenFailed(GraphId("g"), reopen)))
        assertEquals(JobState.Running(null), run(StorageMoveUiState.Quiescing))
    }
}
