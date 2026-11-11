// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fusion.FusionInput
import dev.stapler.stelekit.fusion.fuseObservations
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.jobs.InProcessJobs
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobState
import dev.stapler.stelekit.merge.WriteOutcome
import dev.stapler.stelekit.merge.WriteRefusedReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The post-sync job's digest step: outcome mapping, and that writing the page never re-triggers the job. */
@OptIn(ExperimentalCoroutinesApi::class)
class DigestWriterJobTest {
    private val settings = BridgeMapSettings().also { enableBridge(it) }
    private val jobs = InProcessJobs()
    private val router = newBridgeTestRouter()
    private val input = DigestInput(
        "proj-sample",
        fuseObservations(FusionInput(ClosetFusion.observations(), ClosetFusion.constraints()), ClosetFusion.registry)
            .fold({ error("$it") }, { it }),
        ClosetFusion.registry,
        listOf(SumCheck("Zones sum to opening", 1, "niche.width", ClosetFusion.zones)),
    )
    private var replaceCalls = 0
    private var replaceResult: Either<DomainError, WriteOutcome> = WriteOutcome.Created("p", "h").right()

    private val fakeWriter = DigestWriter(
        replace = { _, _, _ -> replaceCalls++; replaceResult },
        readBack = { _, key -> DigestWriter.buildPage("g", key.name, input).right() },
        hasWriter = { true },
    )

    private fun job(provide: Boolean = true) = PostSyncProjectJob(
        bridgeDeps(settings, jobs, router = { router }), CountingCollector(), digestWriter = fakeWriter,
        digestInput = { if (provide) input else null },
    )

    private suspend fun entry() = jobs.jobs.first().single { it.kind == JobKind.ProjectDigest }

    @Test
    fun aWrittenDigestCompletesTheJob() = runTest {
        assertIs<PostSyncRunOutcome.Completed>(job().run(BRIDGE_GRAPH) { false })
        assertEquals(1, replaceCalls)
        assertEquals(JobState.Succeeded, entry().state)
    }

    @Test
    fun noFusedInputWritesNothing() = runTest {
        assertIs<PostSyncRunOutcome.Completed>(job(provide = false).run(BRIDGE_GRAPH) { false })
        assertEquals(0, replaceCalls)
    }

    @Test
    fun aRefusalShowsInTheJobEntryAndIsNotRemembered() = runTest {
        replaceResult = DomainError.MergeError.WriteRefused(WriteRefusedReason.EditedByUser("p")).left()
        val outcome = job().run(BRIDGE_GRAPH) { false }
        assertIs<PostSyncRunOutcome.DigestNotWritten>(outcome)
        assertIs<DigestWriteResult.Refused>(outcome.result)
        val failed = assertIs<JobState.Failed>(entry().state)
        assertTrue("edited" in checkNotNull(failed.reason), failed.reason)
    }

    @Test
    fun aDeferredWriteIsRetryableNotFailedForGood() = runTest {
        replaceResult = DomainError.MergeError.Retryable("graph switching").left()
        assertIs<DigestWriteResult.Deferred>(assertIs<PostSyncRunOutcome.DigestNotWritten>(job().run(BRIDGE_GRAPH) { false }).result)
    }

    @Test
    fun aFailedWriteIsReportedAsFailed() = runTest {
        replaceResult = DomainError.DatabaseError.WriteFailed("x").left()
        assertIs<PostSyncRunOutcome.Failed>(job().run(BRIDGE_GRAPH) { false })
    }

    @Test
    fun writingTheDigestDoesNotRetriggerTheJob() = runTest {
        val parent = Job()
        val lastSyncAt = MutableStateFlow<Long?>(100L)
        val deps = bridgeDeps(settings, jobs, router = { router })
        val collector = CountingCollector()
        PostSyncProjectCoordinator(
            deps = deps,
            gitLastSyncAt = lastSyncAt,
            syncStateProvider = { SyncState.Idle },
            activeGraphIdProvider = { BRIDGE_GRAPH },
            parentContext = StandardTestDispatcher(testScheduler) + parent,
            job = PostSyncProjectJob(deps, collector, digestWriter = fakeWriter, digestInput = { input }),
        ).start()
        advanceUntilIdle()
        assertEquals(1, replaceCalls)
        assertEquals(1, collector.calls)

        advanceUntilIdle()
        assertEquals(1, replaceCalls, "only a new sync (lastSyncAt) triggers another run")
        parent.cancel()
    }
}
