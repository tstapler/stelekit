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
import dev.stapler.stelekit.merge.PageSource
import dev.stapler.stelekit.merge.PlanRequest
import dev.stapler.stelekit.merge.SelectionFilter
import dev.stapler.stelekit.merge.SourcePage
import dev.stapler.stelekit.merge.SourcePlatform
import dev.stapler.stelekit.merge.SourceReadCapabilities
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.filteredAndSorted
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.screens.copy.CapabilityDestinationProbe
import dev.stapler.stelekit.ui.screens.copy.CopyDestinationSettings
import dev.stapler.stelekit.ui.screens.copy.CopyFlowGateway
import dev.stapler.stelekit.ui.screens.copy.CopyPagesEvent
import dev.stapler.stelekit.ui.screens.copy.CopyPagesViewModel
import dev.stapler.stelekit.ui.screens.copy.DestinationActionKind
import dev.stapler.stelekit.ui.screens.copy.DestinationProbe
import dev.stapler.stelekit.ui.screens.copy.DestinationStatus
import dev.stapler.stelekit.ui.screens.copy.DisabledKind
import dev.stapler.stelekit.ui.screens.copy.ListLoad
import dev.stapler.stelekit.ui.screens.copy.ReviewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CopyPagesViewModelTest {
    private val t = Instant.fromEpochMilliseconds(0)
    private fun uuid(n: Int) = PageUuid("00000000-0000-0000-0000-" + n.toString().padStart(12, '0'))

    private fun page(n: Int, name: String = "Page $n", journal: LocalDate? = null) = Page(
        uuid = uuid(n), name = name, createdAt = t, updatedAt = t,
        isJournal = journal != null, journalDate = journal,
    )

    /** In-memory source using the filter oracle; records the largest page it was asked for. */
    private class FakeSource(val pages: List<Page>, var failNext: Boolean = false) : PageSource {
        var maxLimit = 0
        override suspend fun listPages(filter: SelectionFilter, search: String?, limit: Int, offset: Int): Either<DomainError, List<Page>> {
            maxLimit = maxOf(maxLimit, limit)
            if (failNext) return DomainError.DatabaseError.ReadFailed("closed").left()
            return matching(filter, search).drop(offset).take(limit).right()
        }

        override suspend fun countPages(filter: SelectionFilter, search: String?): Either<DomainError, Long> =
            if (failNext) DomainError.DatabaseError.ReadFailed("closed").left() else matching(filter, search).size.toLong().right()

        override suspend fun readPages(uuids: List<PageUuid>): Either<DomainError, List<SourcePage>> = emptyList<SourcePage>().right()

        private fun matching(filter: SelectionFilter, search: String?) =
            pages.filteredAndSorted(filter).filter { search == null || it.name.contains(search, ignoreCase = true) }
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

    private class MapSettings : Settings {
        val map = HashMap<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = map[key] ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    private fun graph(id: String, path: String = "/g/$id", encrypted: Boolean = false) =
        GraphInfo(id = GraphId(id), path = path, displayName = id.uppercase(), addedAt = 0, isParanoidMode = encrypted)

    private val plainProbe = DestinationProbe { _, _ -> DestinationStatus.Available }

    private fun TestScope.vm(
        pages: List<Page> = (1..213).map { page(it) },
        graphs: List<GraphInfo> = listOf(graph("a"), graph("plain")),
        probe: DestinationProbe = plainProbe,
        gateway: Gateway = Gateway(),
        source: FakeSource = FakeSource(pages),
        settings: CopyDestinationSettings? = null,
        direction: CopyDirection = CopyDirection.Push,
        preselect: Boolean = false,
        gate2: Boolean = false,
    ) = CopyPagesViewModel(
        source = source,
        activeGraphId = GraphId("a"),
        graphRegistry = MutableStateFlow(GraphRegistry(activeGraphId = GraphId("a"), graphs = graphs)),
        gateway = gateway,
        probe = probe,
        destinationSettings = settings,
        direction = direction,
        gate2LinkedPages = gate2,
        preselectLastDestination = preselect,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `selection survives search and filter changes`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        listOf(1, 2, 3).forEach { vm.toggleRow(uuid(it)) }
        vm.onSearchTextChanged("Page 20")
        advanceUntilIdle()
        assertTrue(vm.state.value.rows.none { it.uuid == uuid(1) })
        assertEquals(3, vm.state.value.selectedCount)
        assertTrue(vm.state.value.countLine.endsWith("3 selected"))
        vm.onSearchTextChanged("")
        advanceUntilIdle()
        assertTrue(uuid(1) in vm.state.value.picked)
        vm.close()
    }

    @Test
    fun `first page is bounded and more rows append`() = runTest {
        val source = FakeSource((1..213).map { page(it) })
        val vm = vm(source = source)
        advanceUntilIdle()
        assertEquals(100, vm.state.value.rows.size)
        assertEquals(213L, vm.state.value.totalMatching)
        assertTrue(vm.state.value.hasMore)
        vm.loadMore(); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        assertEquals(213, vm.state.value.rows.size)
        assertFalse(vm.state.value.hasMore)
        assertTrue(source.maxLimit <= PageSource.MAX_PAGE_SIZE)
        vm.close()
    }

    @Test
    fun `count line reads Counting until the count returns`() = runTest {
        val vm = vm()
        assertTrue(vm.state.value.countLine.startsWith("Counting..."))
        assertEquals(ListLoad.Initial, vm.state.value.listLoad)
        advanceUntilIdle()
        assertEquals("213 results, 0 selected", vm.state.value.countLine)
        vm.close()
    }

    @Test
    fun `select all matching selects exactly the filtered set and leaves the rest`() = runTest {
        val pages = (1..213).map { page(it, name = "Match $it") } + (214..300).map { page(it, name = "Other $it") }
        val vm = vm(pages = pages)
        advanceUntilIdle()
        vm.onSearchTextChanged("Match")
        advanceUntilIdle()
        assertEquals(213L, vm.state.value.totalMatching)
        vm.selectAllMatching(); advanceUntilIdle()
        assertEquals(213, vm.state.value.selectedCount)
        assertTrue((214..300).none { uuid(it) in vm.state.value.picked })
        vm.close()
    }

    @Test
    fun `all pages in graph needs confirmation`() = runTest {
        val vm = vm(pages = (1..250).map { page(it) })
        advanceUntilIdle()
        vm.requestSelectAllInGraph(); advanceUntilIdle()
        assertEquals(250L, vm.state.value.confirmAllPages)
        assertEquals(0, vm.state.value.selectedCount)
        vm.dismissSelectAllInGraph()
        assertEquals(0, vm.state.value.selectedCount)
        vm.requestSelectAllInGraph(); advanceUntilIdle()
        vm.confirmSelectAllInGraph(); advanceUntilIdle()
        assertEquals(250, vm.state.value.selectedCount)
        vm.close()
    }

    @Test
    fun `journals chip with a date range lists only journals in range`() = runTest {
        val pages = listOf(
            page(1, "Notes"),
            page(2, "2026-10-07", LocalDate(2026, 10, 7)),
            page(3, "2026-09-30", LocalDate(2026, 9, 30)),
        )
        val vm = vm(pages = pages)
        advanceUntilIdle()
        vm.toggleShowPages()
        vm.setDateRange(LocalDate(2026, 10, 1), LocalDate(2026, 10, 31))
        advanceUntilIdle()
        assertEquals(listOf("2026-10-07"), vm.state.value.rows.map { it.name })
        vm.close()
    }

    @Test
    fun `the last remaining type chip cannot be turned off`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.toggleShowPages()
        vm.toggleShowJournals()
        assertTrue(vm.state.value.filters.showJournals)
        vm.close()
    }

    @Test
    fun `load failure shows an error and retry recovers`() = runTest {
        val source = FakeSource((1..5).map { page(it) }, failNext = true)
        val vm = vm(source = source)
        advanceUntilIdle()
        assertEquals(ListLoad.Failed, vm.state.value.listLoad)
        source.failNext = false
        vm.retryLoad(); advanceUntilIdle()
        assertEquals(ListLoad.Idle, vm.state.value.listLoad)
        assertEquals(5, vm.state.value.rows.size)
        vm.close()
    }

    @Test
    fun `search flags the 100 hit cap`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.state.value.searchCapped)
        vm.onSearchTextChanged("Page"); advanceUntilIdle()
        assertTrue(vm.state.value.searchCapped)
        vm.close()
    }

    @Test
    fun `close with nothing selected closes immediately`() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.requestClose()
        assertEquals(CopyPagesEvent.Closed, vm.events.first())
        assertFalse(vm.state.value.discardPrompt)
        vm.close()
    }

    @Test
    fun `close with a selection asks, keep editing keeps it, discard clears and closes`() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.toggleRow(uuid(1)); vm.toggleRow(uuid(2))
        vm.requestClose()
        assertTrue(vm.state.value.discardPrompt)
        vm.keepEditing()
        assertFalse(vm.state.value.discardPrompt)
        assertEquals(2, vm.state.value.selectedCount)
        vm.requestClose(); vm.confirmDiscard()
        assertEquals(0, vm.state.value.selectedCount)
        assertEquals(CopyPagesEvent.Closed, vm.events.first())
        vm.close()
    }

    @Test
    fun `current graph is disabled with reason current graph`() = runTest {
        val vm = vm(); advanceUntilIdle()
        val row = vm.state.value.destinations.first { it.graphId == GraphId("a") }
        val status = assertIs<DestinationStatus.Disabled>(row.status)
        assertEquals(DisabledKind.CurrentGraph, status.kind)
        assertEquals("current graph", status.text)
        vm.chooseDestination(GraphId("a"))
        assertNull(vm.state.value.destinationId)
        vm.close()
    }

    @Test
    fun `encrypted and no-grant graphs are listed disabled with reasons and plain is selectable`() = runTest {
        val writer = TargetWriterCapabilities(platformSupportsOffGraphWrite = true, hasVerifiedSafGrant = { false })
        val probe = CapabilityDestinationProbe(writer, SourceReadCapabilities(SourcePlatform.Desktop))
        val vm = vm(
            graphs = listOf(graph("a"), graph("enc", encrypted = true), graph("saf", path = "content://tree/x"), graph("plain")),
            probe = probe,
        )
        advanceUntilIdle()
        val rows = vm.state.value.destinations.associateBy { it.graphId.value }
        val enc = assertIs<DestinationStatus.Disabled>(rows.getValue("enc").status)
        assertEquals(DisabledKind.Encrypted, enc.kind)
        assertTrue(enc.text.startsWith("Can't write here: "))
        val saf = assertIs<DestinationStatus.Disabled>(rows.getValue("saf").status)
        assertEquals(DisabledKind.NoGrant, saf.kind)
        assertEquals(DestinationStatus.Available, rows.getValue("plain").status)

        vm.toggleRow(uuid(1))
        vm.chooseDestination(GraphId("enc"))
        vm.chooseDestination(GraphId("saf"))
        assertNull(vm.state.value.destinationId)
        assertFalse(vm.state.value.canReview)
        vm.chooseDestination(GraphId("plain"))
        assertTrue(vm.state.value.canReview)
        vm.close()
    }

    @Test
    fun `unsupported platform disables every inactive graph with a link to pull`() = runTest {
        val probe = CapabilityDestinationProbe(
            TargetWriterCapabilities(platformSupportsOffGraphWrite = false),
            SourceReadCapabilities(SourcePlatform.Ios),
        )
        val vm = vm(graphs = listOf(graph("a"), graph("work")), probe = probe)
        advanceUntilIdle()
        val status = assertIs<DestinationStatus.Disabled>(vm.state.value.destinations.first { it.graphId.value == "work" }.status)
        assertEquals("Can't copy into WORK from here on this device. Open WORK, then use Copy pages from...", status.text)
        assertEquals(DestinationActionKind.SwitchToPull, status.action?.kind)
        vm.onDestinationAction(GraphId("work"), DestinationActionKind.SwitchToPull)
        assertEquals(CopyPagesEvent.SwitchToPull, vm.events.first())
        vm.close()
    }

    @Test
    fun `probing shows Checking, times out after 3s with a retryable row, and Review waits`() = runTest {
        val hang = CompletableDeferred<DestinationStatus>()
        var calls = 0
        val probe = DestinationProbe { _, _ -> if (calls++ == 0) hang.await() else DestinationStatus.Available }
        val vm = vm(probe = probe)
        advanceTimeBy(100)
        assertEquals(DestinationStatus.Checking, vm.state.value.destinations.first { it.graphId.value == "plain" }.status)
        vm.toggleRow(uuid(1))
        vm.chooseDestination(GraphId("plain"))
        assertNull(vm.state.value.destinationId)
        assertFalse(vm.state.value.canReview)
        advanceTimeBy(3_000)
        val status = assertIs<DestinationStatus.CouldntCheck>(vm.state.value.destinations.first { it.graphId.value == "plain" }.status)
        assertEquals("Couldn't check PLAIN: timed out", status.text)
        vm.retryProbe(GraphId("plain")); advanceUntilIdle()
        assertEquals(DestinationStatus.Available, vm.state.value.destinations.first { it.graphId.value == "plain" }.status)
        vm.close()
    }

    @Test
    fun `no other graph is reported`() = runTest {
        val vm = vm(graphs = listOf(graph("a"))); advanceUntilIdle()
        assertTrue(vm.state.value.noOtherGraph)
        vm.close()
    }

    @Test
    fun `review is helper-gated and plans through the gateway only`() = runTest {
        val gateway = Gateway()
        val vm = vm(gateway = gateway); advanceUntilIdle()
        assertEquals("Select at least one page", vm.state.value.reviewHelper)
        vm.toggleRow(uuid(1))
        assertEquals("Choose a destination", vm.state.value.reviewHelper)
        vm.review(); advanceUntilIdle()
        assertNull(gateway.lastRequest)
        vm.chooseDestination(GraphId("plain"))
        assertNull(vm.state.value.reviewHelper)
        vm.review(); advanceUntilIdle()
        val ready = assertIs<ReviewState.Ready>(vm.state.value.review)
        assertEquals(GraphId("a"), ready.request.sourceGraphId)
        assertEquals(GraphId("plain"), ready.request.targetGraphId)
        assertEquals(setOf(uuid(1)), ready.request.selection.include)
        vm.close()
    }

    @Test
    fun `pull direction targets the active graph`() = runTest {
        val gateway = Gateway()
        val vm = vm(gateway = gateway, direction = CopyDirection.Pull); advanceUntilIdle()
        vm.toggleRow(uuid(1)); vm.chooseDestination(GraphId("plain")); vm.review(); advanceUntilIdle()
        val req = gateway.lastRequest!!
        assertEquals(GraphId("plain"), req.sourceGraphId)
        assertEquals(GraphId("a"), req.targetGraphId)
        vm.close()
    }

    @Test
    fun `last destination is preselected only when valid`() = runTest {
        val dest = CopyDestinationSettings(MapSettings())
        dest.lastDestinationGraphId = GraphId("plain")
        val ok = vm(settings = dest, preselect = true); advanceUntilIdle()
        assertEquals(GraphId("plain"), ok.state.value.destinationId)

        dest.lastDestinationGraphId = GraphId("gone")
        val removed = vm(settings = dest, preselect = true); advanceUntilIdle()
        assertNull(removed.state.value.destinationId)

        dest.lastDestinationGraphId = GraphId("a")
        val equalToSource = vm(settings = dest, preselect = true); advanceUntilIdle()
        assertNull(equalToSource.state.value.destinationId)

        dest.lastDestinationGraphId = GraphId("plain")
        val disabled = vm(
            settings = dest,
            preselect = true,
            probe = DestinationProbe { _, _ -> DestinationStatus.Disabled(DisabledKind.Encrypted, "no") },
        )
        advanceUntilIdle()
        assertNull(disabled.state.value.destinationId)

        val off = vm(settings = dest, preselect = false); advanceUntilIdle()
        assertNull(off.state.value.destinationId)
        listOf(ok, removed, equalToSource, disabled, off).forEach { it.close() }
    }

    @Test
    fun `confirm records last destination and never touches capture keys`() = runTest {
        val s = MapSettings()
        val vm = vm(settings = CopyDestinationSettings(s)); advanceUntilIdle()
        vm.chooseDestination(GraphId("plain"))
        vm.recordDestinationConfirmed()
        assertEquals(mapOf("copy_last_destination_graph_id" to "plain"), s.map)
        vm.close()
    }

    @Test
    fun `linked page options are inert unless gate2 is on`() = runTest {
        val off = vm(); advanceUntilIdle()
        off.setIncludeLinked(true)
        assertFalse(off.state.value.includeLinked)
        val on = vm(gate2 = true); advanceUntilIdle()
        on.setIncludeLinked(true); on.setIncludeAssets(true)
        assertTrue(on.state.value.includeLinked && on.state.value.includeAssets)
        off.close(); on.close()
    }
}
