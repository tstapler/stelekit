// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.jobs.InProcessJobs
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobState
import dev.stapler.stelekit.jobs.JobStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PostSyncProjectCoordinatorTest {
    private val parent = Job()
    private val settings = BridgeMapSettings()
    private val jobs = InProcessJobs()
    private val lastSyncAt = MutableStateFlow<Long?>(100L)
    private val syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    private val ready = MutableStateFlow<String?>(BRIDGE_GRAPH)
    private var activeGraph: String? = BRIDGE_GRAPH

    @AfterTest
    fun cleanup() = parent.cancel()

    private suspend fun entries(): List<JobStatus> = jobs.jobs.first().filter { it.kind == JobKind.ProjectDigest }

    private fun TestScope.coordinator(
        collector: ProjectSourceCollector = CountingCollector(),
        router: Boolean = true,
        hasProject: Boolean = true,
        probe: PageSettleProbe = PageSettleProbe { PageSettleState.Settled },
    ): PostSyncProjectCoordinator {
        val testRouter = if (router) newBridgeTestRouter() else null
        val deps = bridgeDeps(
            settings, jobs, router = { testRouter }, readyGraphIds = ready, hasConfiguredProject = hasProject, probe = probe,
        )
        return PostSyncProjectCoordinator(
            deps = deps,
            gitLastSyncAt = lastSyncAt,
            syncStateProvider = { syncState.value },
            activeGraphIdProvider = { activeGraph },
            parentContext = StandardTestDispatcher(testScheduler) + parent,
            job = PostSyncProjectJob(deps, collector),
        ).also { it.start() }
    }

    @Test
    fun isInert_When_TheFlagIsOff() = runTest {
        ProjectBridgeSettings(settings).setDigestEnabled(BRIDGE_GRAPH, true)
        val collector = CountingCollector()

        coordinator(collector)
        advanceUntilIdle()

        assertEquals(0, collector.calls, "nothing is read with the flag off")
        assertEquals(emptyList(), entries())
    }

    @Test
    fun isInert_When_TheGraphHasNotOptedIn() = runTest {
        ProjectBridgeSettings(settings).homeSketchEnabled = true
        val collector = CountingCollector()

        coordinator(collector)
        advanceUntilIdle()

        assertEquals(0, collector.calls)
        assertEquals(emptyList(), entries())
    }

    @Test
    fun isInert_When_NoProjectIsConfigured() = runTest {
        enableBridge(settings)
        val collector = CountingCollector()

        coordinator(collector, hasProject = false)
        advanceUntilIdle()

        assertEquals(0, collector.calls)
        assertEquals(emptyList(), entries())
    }

    @Test
    fun runsOnceAtGraphOpen_AndOncePerNewLastSyncAt() = runTest {
        enableBridge(settings)
        val collector = CountingCollector()
        coordinator(collector)

        advanceUntilIdle()
        assertEquals(1, collector.calls, "the graph-open run")

        lastSyncAt.value = 200L
        advanceUntilIdle()
        assertEquals(2, collector.calls)

        lastSyncAt.value = 200L
        advanceUntilIdle()
        assertEquals(2, collector.calls, "the same lastSyncAt never triggers twice")
        assertEquals(listOf(JobState.Succeeded, JobState.Succeeded), entries().map { it.state })
    }

    @Test
    fun runsForAnLfsSync_AndNotesTheHeldFiles() = runTest {
        enableBridge(settings)
        val collector = CountingCollector()
        coordinator(collector)
        advanceUntilIdle()

        syncState.value = SyncState.LfsActionRequired(emptyList())
        lastSyncAt.value = 300L
        advanceUntilIdle()

        assertEquals(2, collector.calls)
        val latest = entries().first { it.title.contains("LFS") }
        assertEquals("Project digest (sync left LFS files held)", latest.title)
        assertEquals(JobState.Succeeded, latest.state)
    }

    @Test
    fun recordsADigestUnavailableEntry_WhenThereIsNoRouter_AndStillRunsTheLocalSteps() = runTest {
        enableBridge(settings)
        val collector = CountingCollector()

        coordinator(collector, router = false)
        advanceUntilIdle()

        assertEquals(1, collector.calls)
        val entry = entries().single()
        assertEquals("Project digest", entry.title)
        val failed = assertIs<JobState.Failed>(entry.state)
        assertEquals("digest unavailable: no writer on this platform", failed.reason)
    }

    @Test
    fun aGraphSwitchCancelsTheInFlightRun() = runTest {
        enableBridge(settings)
        enableBridge(settings, "graph-b")
        val gate = CompletableDeferred<Unit>()
        val collector = CountingCollector(gate = gate)
        coordinator(collector)
        runCurrent()
        assertEquals(1, collector.calls, "the run for graph-a is parked")

        ready.value = null
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList(), entries(), "a cancelled run reports nothing")

        activeGraph = "graph-b"
        ready.value = "graph-b"
        advanceUntilIdle()
        assertEquals(2, collector.calls)
        assertEquals(1, entries().size)
    }

    @Test
    fun ignoresAReadyGraph_ThatIsNotTheActiveOne() = runTest {
        enableBridge(settings)
        activeGraph = "graph-b"
        val collector = CountingCollector()

        coordinator(collector)
        advanceUntilIdle()

        assertEquals(0, collector.calls)
    }

    @Test
    fun writesNothingUntilTheDatabaseCatchesUp_ThenReportsOnce() = runTest {
        enableBridge(settings)
        var imported = false
        val page = BoundPageRef("Closet", "/g/pages/Closet.md")
        val collector = CountingCollector(bound = listOf(page))
        // The sync published its new lastSyncAt (above) before the fake import below completes.
        coordinator(collector, probe = { if (imported) PageSettleState.Settled else PageSettleState.Stale })

        runCurrent()
        advanceTimeBy(SettleBarrier.DEFAULT_INTERVAL_MILLIS * 3)
        assertEquals(emptyList(), entries(), "no report while the barrier waits")

        imported = true
        advanceUntilIdle()

        val entry = entries().single()
        assertEquals(JobState.Succeeded, entry.state)
        assertEquals(1, collector.calls, "one collection pass; the barrier polled, it did not rerun the job")
    }

    @Test
    fun endsAsUnsettled_ThenRetriesOnTheNextSync() = runTest {
        enableBridge(settings)
        var imported = false
        val page = BoundPageRef("Closet", "/g/pages/Closet.md")
        coordinator(CountingCollector(bound = listOf(page)), probe = { if (imported) PageSettleState.Settled else PageSettleState.Stale })
        advanceUntilIdle()

        val first = entries().single()
        val failed = assertIs<JobState.Failed>(first.state)
        assertTrue(failed.reason!!.startsWith("unsettled=1"), failed.reason)

        imported = true
        lastSyncAt.value = 400L
        advanceUntilIdle()

        assertEquals(2, entries().size)
        assertEquals(1, entries().count { it.state == JobState.Succeeded })
    }
}
