// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs

import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.merge.ApplyFailure
import dev.stapler.stelekit.merge.ClosureSummary
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.CopyRunHost
import dev.stapler.stelekit.merge.CopyRunOutcome
import dev.stapler.stelekit.merge.DryRunSummary
import dev.stapler.stelekit.merge.InMemoryMergeStorage
import dev.stapler.stelekit.merge.MergePlan
import dev.stapler.stelekit.merge.MergeResult
import dev.stapler.stelekit.merge.PageFailure
import dev.stapler.stelekit.merge.PageMergeService
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrackedCopyRunHostTest {
    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private class FakeHost : CopyRunHost {
        override val running: StateFlow<Boolean> = MutableStateFlow(false)
        var accept = true
        var stopped = 0
        var observer: ((CopyRunOutcome) -> Unit)? = null
        val attachedObservers = mutableListOf<(CopyRunOutcome) -> Unit>()
        val detachedObservers = mutableListOf<(CopyRunOutcome) -> Unit>()

        override fun start(service: PageMergeService, plan: MergePlan, onOutcome: (CopyRunOutcome) -> Unit): Boolean =
            accept.also { if (it) observer = onOutcome }

        override fun retryFailed(service: PageMergeService, onOutcome: (CopyRunOutcome) -> Unit): Boolean =
            accept.also { if (it) observer = onOutcome }

        override fun stop(service: PageMergeService) { stopped++ }
        override fun attach(onOutcome: (CopyRunOutcome) -> Unit) { attachedObservers += onOutcome }
        override fun detach(onOutcome: (CopyRunOutcome) -> Unit) { detachedObservers += onOutcome }
    }

    private val manager: GraphManager = GraphManager(
        MapSettings().also { it.putString("graph_registry", Json.encodeToString(GraphRegistry())) },
        DriverFactory(), StubFileSystem(), defaultBackend = GraphBackend.IN_MEMORY,
    )
    private val service = PageMergeService(
        TargetWriterRouter(
            manager, RegistryGraphLocator(manager.graphRegistry), TargetWriterCapabilities(platformSupportsOffGraphWrite = false),
            activeWriterFor = { error("not used") }, offGraphWriterFor = { error("not used") },
        ),
        InMemoryMergeStorage(),
    )
    private val plan = MergePlan("m", "src", "dst", CopyDirection.Pull, DryRunSummary(new = 1), emptyList(), ClosureSummary(), "fp", 0L)
    private val jobs = InProcessJobs()
    private val delegate = FakeHost()
    private val host = TrackedCopyRunHost(delegate, jobs)

    @AfterTest
    fun cleanup() = manager.shutdown()

    private fun result(failed: List<PageFailure> = emptyList()) =
        MergeResult("m", total = 1, newPages = 1 - failed.size, combinedPages = 0, unchangedPages = 0, failed = failed, conflicts = 0, assetsRenamed = 0)

    private suspend fun state() = jobs.jobs.first().single().state

    @Test
    fun start_should_ListCancellableJobAndSucceed_When_RunFinishesWithoutFailures() = runTest {
        assertTrue(host.start(service, plan) {})
        val job = jobs.jobs.first().single()
        assertEquals(JobKind.PageCopy, job.kind)
        assertEquals("Copying pages", job.title)
        assertTrue(job.cancellable)

        delegate.observer!!(CopyRunOutcome.Finished(result().right()))

        assertEquals(JobState.Succeeded, state())
    }

    @Test
    fun start_should_FailJobWithCount_When_SomePagesFailed() = runTest {
        host.start(service, plan) {}

        delegate.observer!!(CopyRunOutcome.Finished(result(listOf(PageFailure(0, "A", DomainError.MergeError.Retryable("x")))).right()))

        assertEquals(JobState.Failed("1 pages failed"), state())
    }

    @Test
    fun start_should_FailJob_When_ApplyFailsOrRunCrashes() = runTest {
        host.start(service, plan) {}
        delegate.observer!!(CopyRunOutcome.Finished(ApplyFailure.Busy.left()))
        assertEquals(JobState.Failed("The copy did not complete"), state())

        host.retryFailed(service) {}
        delegate.observer!!(CopyRunOutcome.Crashed("boom"))
        assertEquals(JobState.Failed("boom"), jobs.jobs.first().first().state)
    }

    @Test
    fun start_should_CancelJobAndReturnFalse_When_HostDeclines() = runTest {
        delegate.accept = false

        assertFalse(host.start(service, plan) {})

        assertIs<JobState.Cancelled>(state())
    }

    @Test
    fun cancel_should_StopTheRun_When_JobCancelledFromList() = runTest {
        host.start(service, plan) {}

        jobs.cancel(jobs.jobs.first().single().id)

        assertEquals(1, delegate.stopped)
    }

    @Test
    fun attachAndDetach_should_PassTheSameWrappedObserver_When_Rebound() = runTest {
        val observer: (CopyRunOutcome) -> Unit = {}
        host.attach(observer)
        host.detach(observer)

        assertEquals(delegate.attachedObservers.single(), delegate.detachedObservers.single())
    }

    @Test
    fun compositeRegistry_should_ForwardCancelAndClear_When_SourcesAreMany() = runTest {
        val a = InProcessJobs()
        val b = InProcessJobs()
        val composite = CompositeJobRegistry(listOf(a, b))
        val hit = intArrayOf(0)
        val handle = a.begin(JobKind.GitSync, "Syncing") { hit[0]++ }

        composite.cancel(a.jobs.first().single().id)
        handle.succeed()
        composite.clearFinished()

        assertEquals(1, hit[0])
        assertTrue(composite.jobs.first().isEmpty())
    }
}
