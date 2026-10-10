package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.ApplyFailure
import dev.stapler.stelekit.merge.ClosureSummary
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.DryRunSummary
import dev.stapler.stelekit.merge.MergePlan
import dev.stapler.stelekit.merge.MergeProgress
import dev.stapler.stelekit.merge.MergeResult
import dev.stapler.stelekit.merge.PlanRequest
import dev.stapler.stelekit.merge.PullIndexState
import dev.stapler.stelekit.merge.PullPageSource
import dev.stapler.stelekit.merge.ReadError
import dev.stapler.stelekit.merge.SourceEntry
import dev.stapler.stelekit.merge.SourceGraphReader
import dev.stapler.stelekit.merge.SourceKind
import dev.stapler.stelekit.merge.SourcePlatform
import dev.stapler.stelekit.merge.SourceReadCapabilities
import dev.stapler.stelekit.merge.StagedPage
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.ui.screens.copy.CapabilityDestinationProbe
import dev.stapler.stelekit.ui.screens.copy.CopyFlowGateway
import dev.stapler.stelekit.ui.screens.copy.CopyPagesState
import dev.stapler.stelekit.ui.screens.copy.CopyPagesViewModel
import dev.stapler.stelekit.ui.screens.copy.DestinationActionKind
import dev.stapler.stelekit.ui.screens.copy.DestinationProbe
import dev.stapler.stelekit.ui.screens.copy.DestinationStatus
import dev.stapler.stelekit.ui.screens.copy.DisabledKind
import dev.stapler.stelekit.ui.screens.copy.ReviewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PullCopyViewModelTest {
    private fun graph(id: String, path: String = "/g/$id", encrypted: Boolean = false) =
        GraphInfo(id = GraphId(id), path = path, displayName = id.uppercase(), addedAt = 0, isParanoidMode = encrypted)

    /** Names P001.. in order; [gate] (if set) blocks every listing call after the first until completed. */
    private class FakeReader(val count: Int, var gate: CompletableDeferred<Unit>? = null, var failAfter: Int? = null) : SourceGraphReader {
        var calls = 0
        val names = (1..count).map { "P" + it.toString().padStart(3, '0') }

        override suspend fun listEntries(graph: GraphInfo, afterName: String?, limit: Int): Either<ReadError, List<SourceEntry>> {
            calls++
            if (calls > 1) gate?.await()
            if (failAfter != null && calls > failAfter!!) return ReadError.NoGrant.left()
            return names.filter { afterName == null || it > afterName }.take(limit)
                .map { SourceEntry(it, SourceKind.Page, "pages/$it.md", 2048, 0L) }.right()
        }

        override suspend fun readPage(graph: GraphInfo, entry: SourceEntry): Either<ReadError, StagedPage> =
            StagedPage(name = entry.name).right()
    }

    private class Gateway : CopyFlowGateway {
        var lastRequest: PlanRequest? = null
        override val progress: StateFlow<MergeProgress> = MutableStateFlow(MergeProgress())
        override suspend fun plan(request: PlanRequest): Either<DomainError, MergePlan> {
            lastRequest = request
            return MergePlan(
                "m", request.sourceGraphId.value, request.targetGraphId.value, request.direction,
                DryRunSummary(new = 1), emptyList(), ClosureSummary(), "fp", 0L,
            ).right()
        }
        override suspend fun apply(plan: MergePlan): Either<ApplyFailure, MergeResult> = error("picker must not apply")
        override fun cancel() = Unit
    }

    private val availableProbe = DestinationProbe { _, _ -> DestinationStatus.Available }

    private class Harness(val vm: CopyPagesViewModel, val source: PullPageSource, val reader: FakeReader, val gateway: Gateway)

    private fun TestScope.harness(
        reader: FakeReader = FakeReader(250),
        probe: DestinationProbe = availableProbe,
        graphs: List<GraphInfo> = listOf(graph("a"), graph("src")),
        gate2: Boolean = true,
    ): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = PullPageSource(reader, dispatcher)
        val gateway = Gateway()
        val vm = CopyPagesViewModel(
            source = source,
            activeGraphId = GraphId("a"),
            graphRegistry = MutableStateFlow(GraphRegistry(activeGraphId = GraphId("a"), graphs = graphs)),
            gateway = gateway,
            probe = probe,
            direction = CopyDirection.Pull,
            gate2LinkedPages = gate2,
            dispatcher = dispatcher,
        )
        return Harness(vm, source, reader, gateway)
    }

    private fun Harness.finish() { vm.close(); source.close() }

    @Test
    fun `no rows are listed until a source is chosen then rows carry size and date subtitles`() = runTest {
        val h = harness(); advanceUntilIdle()
        assertTrue(h.vm.state.value.rows.isEmpty())
        assertNull(h.vm.state.value.destinationId)
        assertEquals(0, h.reader.calls)

        h.vm.chooseDestination(GraphId("src")); advanceUntilIdle()
        val s = h.vm.state.value
        assertEquals(250L, s.totalMatching)
        assertEquals(100, s.rows.size)
        assertEquals("2 KB - 1970-01-01", s.rows.first().subtitle)
        assertEquals(PullIndexState.Ready(250), s.indexState)
        h.finish()
    }

    @Test
    fun `review plans from the chosen source into the active graph with direction Pull`() = runTest {
        val h = harness(); advanceUntilIdle()
        h.vm.chooseDestination(GraphId("src")); advanceUntilIdle()
        h.vm.toggleRow(h.vm.state.value.rows.first().uuid)
        h.vm.review(); advanceUntilIdle()
        val req = h.gateway.lastRequest!!
        assertEquals(CopyDirection.Pull, req.direction)
        assertEquals(GraphId("src"), req.sourceGraphId)
        assertEquals(GraphId("a"), req.targetGraphId)
        assertIs<ReviewState.Ready>(h.vm.state.value.review)
        h.finish()
    }

    @Test
    fun `the list is usable while the index is still reading and Stop cancels the listing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness(FakeReader(250, gate)); advanceUntilIdle()
        h.vm.chooseDestination(GraphId("src")); advanceUntilIdle()

        val mid = h.vm.state.value
        assertEquals(PullIndexState.Reading(100), mid.indexState)
        assertTrue(mid.stillReading)
        assertEquals(100, mid.rows.size, "first page is usable before the index completes")
        h.vm.onSearchTextChanged("P05"); advanceTimeBy(1_000); advanceUntilIdle()
        assertEquals(10L, h.vm.state.value.totalMatching)

        val callsBefore = h.reader.calls
        h.vm.clearSource(); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(callsBefore, h.reader.calls, "no listing after Stop")
        assertNull(h.vm.state.value.destinationId)
        assertEquals(PullIndexState.Idle, h.vm.state.value.indexState)
        assertTrue(h.vm.state.value.rows.isEmpty())
        h.finish()
    }

    @Test
    fun `a grant lost mid-listing returns to the chooser with Re-select folder`() = runTest {
        val h = harness(FakeReader(250, failAfter = 1)); advanceUntilIdle()
        h.vm.chooseDestination(GraphId("src")); advanceUntilIdle()
        assertNull(h.vm.state.value.destinationId)
        val row = h.vm.state.value.destinations.first { it.graphId == GraphId("src") }
        val status = assertIs<DestinationStatus.Disabled>(row.status)
        assertEquals(DisabledKind.NoGrant, status.kind)
        assertEquals(CopyPagesState.RESELECT_FOLDER, status.action?.label)
        assertEquals(DestinationActionKind.RegrantAccess, status.action?.kind)
        h.finish()
    }

    @Test
    fun `selection made before a source drops is kept when the same source returns`() = runTest {
        val h = harness(FakeReader(30)); advanceUntilIdle()
        h.vm.chooseDestination(GraphId("src")); advanceUntilIdle()
        h.vm.selectAllMatching(); advanceUntilIdle()
        assertEquals(30, h.vm.state.value.selectedCount)
        h.vm.clearSource(); advanceUntilIdle()
        assertEquals(30, h.vm.state.value.selectedCount, "chooser round trip keeps the selection")
        h.vm.chooseDestination(GraphId("src")); advanceUntilIdle()
        assertEquals(30, h.vm.state.value.selectedCount)
        h.finish()
    }

    @Test
    fun `linked pages and assets cannot be switched on in a pull`() = runTest {
        val h = harness(); advanceUntilIdle()
        h.vm.chooseDestination(GraphId("src")); advanceUntilIdle()
        h.vm.setIncludeLinked(true); h.vm.setIncludeAssets(true)
        assertFalse(h.vm.state.value.includeLinked)
        assertFalse(h.vm.state.value.includeAssets)
        h.finish()
    }

    @Test
    fun `the chooser disables unreadable sources with their reason and the active graph as current graph`() = runTest {
        val caps = SourceReadCapabilities(
            platform = SourcePlatform.Ios,
            iosVerified = true,
            hasGrant = { it.graphId.value != "nogrant" },
            canRegrant = true,
            folderExists = { it != "/g/missing" },
        )
        val probe = CapabilityDestinationProbe(TargetWriterCapabilities(platformSupportsOffGraphWrite = false), caps)
        val h = harness(
            probe = probe,
            graphs = listOf(
                graph("a"), graph("ok"), graph("vault", encrypted = true), graph("nogrant"), graph("missing", path = "/g/missing"),
            ),
        )
        advanceUntilIdle()
        val rows = h.vm.state.value.destinations.associateBy { it.graphId.value }

        assertEquals(DisabledKind.CurrentGraph, (rows.getValue("a").status as DestinationStatus.Disabled).kind)
        assertEquals(DestinationStatus.Available, rows.getValue("ok").status)
        assertEquals(DisabledKind.Encrypted, (rows.getValue("vault").status as DestinationStatus.Disabled).kind)
        val noGrant = rows.getValue("nogrant").status as DestinationStatus.Disabled
        assertEquals(DisabledKind.NoGrant, noGrant.kind)
        assertEquals(CopyPagesState.RESELECT_FOLDER, noGrant.action?.label)
        assertEquals(DisabledKind.FolderMissing, (rows.getValue("missing").status as DestinationStatus.Disabled).kind)
        assertFalse(h.vm.state.value.noSourceAvailable, "one readable source remains")

        // A disabled row can never be chosen.
        h.vm.chooseDestination(GraphId("vault"))
        assertNull(h.vm.state.value.destinationId)
        assertEquals(0, h.reader.calls)
        h.finish()
    }

    @Test
    fun `no source available when every other graph is unreadable`() = runTest {
        val caps = SourceReadCapabilities(platform = SourcePlatform.Web, webVerified = false)
        val probe = CapabilityDestinationProbe(TargetWriterCapabilities(platformSupportsOffGraphWrite = false), caps)
        val h = harness(probe = probe, graphs = listOf(graph("a"), graph("x"), graph("y")))
        advanceUntilIdle()
        assertTrue(h.vm.state.value.noSourceAvailable)
        val reasons = h.vm.state.value.destinations.filterNot { it.isCurrentGraph }
            .map { (it.status as DestinationStatus.Disabled).kind }
        assertEquals(listOf(DisabledKind.PlatformUnsupported, DisabledKind.PlatformUnsupported), reasons)
        assertNotNull(h.vm.state.value.destinations.firstOrNull())
        h.finish()
    }
}
