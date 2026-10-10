// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.LinkClosurePolicy
import dev.stapler.stelekit.merge.PageSelection
import dev.stapler.stelekit.merge.PageSource
import dev.stapler.stelekit.merge.PlanRequest
import dev.stapler.stelekit.merge.PullIndexState
import dev.stapler.stelekit.merge.PullPageSource
import dev.stapler.stelekit.merge.ReadCapabilityReason
import dev.stapler.stelekit.merge.ReadError
import dev.stapler.stelekit.merge.SelectionFilter
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate

/**
 * State holder for the copy page picker (stories 3.1.1 / 3.2.1). Owns its scope; never takes a
 * composition scope. Talks to the merge engine only through [CopyFlowGateway] and lists pages
 * through [source] in bounded [PageSource.MAX_PAGE_SIZE] pages. No path here writes to `ShareInbox`.
 *
 * @param activeGraphId the open graph: the source in Push, the destination in Pull
 * @param graphRegistry registered graphs (the flow `RegistryGraphLocator` reads; `GraphLocator`
 *   itself can only look one graph up, not list them)
 * @param gate2LinkedPages renders the "Include linked pages" options (Gate 2)
 * @param preselectLastDestination preselect `copy_last_destination_graph_id` when still valid (Gate 2)
 */
@Suppress("TooManyFunctions") // one function per user intent; splitting would only move them
class CopyPagesViewModel(
    private val source: PageSource,
    private val activeGraphId: GraphId,
    private val graphRegistry: StateFlow<GraphRegistry>,
    private val gateway: CopyFlowGateway,
    private val probe: DestinationProbe,
    private val destinationSettings: CopyDestinationSettings? = null,
    private val direction: CopyDirection = CopyDirection.Push,
    private val gate2LinkedPages: Boolean = false,
    private val preselectLastDestination: Boolean = false,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val searchDebounceMs: Long = SEARCH_DEBOUNCE_MS,
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher +
            CoroutineExceptionHandler { _, e ->
                if (e !is CancellationException) {
                    _state.update { it.copy(listLoad = ListLoad.Failed, loadError = e.message ?: "unknown error", selecting = false) }
                }
            },
    )

    private val _state = MutableStateFlow(
        CopyPagesState(
            direction = direction,
            activeGraphName = graphRegistry.value.graphs.firstOrNull { it.id == activeGraphId }?.displayName
                ?: activeGraphId.value,
            gate2LinkedPages = gate2LinkedPages,
        ),
    )
    val state: StateFlow<CopyPagesState> = _state.asStateFlow()

    private val eventChannel = Channel<CopyPagesEvent>(Channel.BUFFERED)
    val events: Flow<CopyPagesEvent> = eventChannel.receiveAsFlow()

    private var listJob: Job? = null
    private var searchJob: Job? = null
    private var selectAllJob: Job? = null
    private var reviewJob: Job? = null
    private var linkedJob: Job? = null
    private val probeJobs = HashMap<GraphId, Job>()
    private var userChoseDestination = false

    /** Pull: the file-backed source being browsed; null in Push. */
    private val pull: PullPageSource? = if (direction == CopyDirection.Pull) source as? PullPageSource else null
    private var lastSourceId: GraphId? = null

    init {
        refreshList()
        scope.launch { graphRegistry.collect { syncDestinations(it) } }
        pull?.let { p -> scope.launch { p.indexState.collect { onIndexState(it) } } }
    }

    fun close() {
        pull?.stop()
        eventChannel.close()
        scope.cancel()
    }

    // ---- search, filters, paging -------------------------------------------------------------

    fun onSearchTextChanged(text: String) {
        _state.update { it.copy(searchText = text) }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(searchDebounceMs)
            refreshList()
        }
    }

    fun toggleShowPages() = setFilters { it.copy(showPages = !it.showPages).takeIf { f -> f.showPages || f.showJournals } }

    fun toggleShowJournals() = setFilters { it.copy(showJournals = !it.showJournals).takeIf { f -> f.showPages || f.showJournals } }

    fun setDateRange(from: LocalDate?, to: LocalDate?) = setFilters { it.copy(dateFrom = from, dateTo = to) }

    fun setNamespace(namespace: String) = setFilters { it.copy(namespace = namespace) }

    fun setTag(tag: String) = setFilters { it.copy(tag = tag) }

    fun clearFilters() {
        searchJob?.cancel()
        _state.update { it.copy(searchText = "", filters = CopyFilters()) }
        refreshList()
    }

    private fun setFilters(change: (CopyFilters) -> CopyFilters?) {
        val next = change(_state.value.filters) ?: return
        if (next == _state.value.filters) return
        _state.update { it.copy(filters = next) }
        refreshList()
    }

    fun retryLoad() = refreshList()

    /** Appends the next page when the user nears the end of the list. */
    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.listLoad != ListLoad.Idle) return
        _state.update { it.copy(listLoad = ListLoad.AppendingMore) }
        val offset = s.rows.size
        val generation = listJob
        scope.launch {
            val result = source.listPages(s.filters.toSelectionFilter(), s.searchText.ifBlank { null }, PageSource.MAX_PAGE_SIZE, offset)
            if (listJob !== generation) return@launch
            result.fold(
                { e -> _state.update { it.copy(listLoad = ListLoad.Failed, loadError = e.message) } },
                { pages ->
                    val counts = blockCountsFor(pages)
                    _state.update { cur ->
                        val rows = cur.rows + pages.map { toRow(it, counts) }
                        cur.copy(rows = rows, hasMore = pages.isNotEmpty() && rows.size < (cur.totalMatching ?: Long.MAX_VALUE), listLoad = ListLoad.Idle)
                    }
                },
            )
        }
    }

    private fun refreshList() {
        listJob?.cancel()
        val snapshot = _state.value
        val filter = snapshot.filters.toSelectionFilter()
        val search = snapshot.searchText.trim().ifEmpty { null }
        _state.update {
            it.copy(
                listLoad = if (it.rows.isEmpty()) ListLoad.Initial else ListLoad.Updating,
                totalMatching = null,
                loadError = null,
            )
        }
        val job = scope.launch {
            val count = async { source.countPages(filter, search) }
            val first = source.listPages(filter, search, PageSource.MAX_PAGE_SIZE, 0)
            val total = count.await()
            val failure = first.leftOrNull() ?: total.leftOrNull()
            if (failure != null) {
                _state.update { it.copy(listLoad = ListLoad.Failed, loadError = failure.message) }
                return@launch
            }
            val pages = first.getOrNull().orEmpty()
            val counts = blockCountsFor(pages)
            val n = total.getOrNull() ?: pages.size.toLong()
            _state.update {
                it.copy(
                    rows = pages.map { toRow(it, counts) },
                    totalMatching = n,
                    hasMore = pages.size < n,
                    listLoad = ListLoad.Idle,
                )
            }
        }
        listJob = job
    }

    /** Best effort: a failed count just leaves the row without a block count. */
    private suspend fun blockCountsFor(pages: List<Page>): Map<PageUuid, Int> =
        if (pages.isEmpty()) emptyMap() else source.blockCounts(pages.map { it.uuid }).getOrNull().orEmpty()

    private fun toRow(p: Page, counts: Map<PageUuid, Int>) = PageRowState(
        p.uuid, p.name, p.isJournal,
        blockCount = if (counts.isEmpty()) null else counts[p.uuid] ?: 0,
        subtitle = pull?.subtitleFor(p.uuid),
    )
    // ---- pull: source index ------------------------------------------------------------------

    private suspend fun onIndexState(st: PullIndexState) {
        _state.update { it.copy(indexState = st) }
        when (st) {
            is PullIndexState.Reading, is PullIndexState.Ready -> {
                refreshList()
                delay(INDEX_REFRESH_MS)
            }
            is PullIndexState.Failed -> if (st.error == ReadError.NoGrant) markSourceNoGrant()
            PullIndexState.Idle -> Unit
        }
    }

    /** Grant lost mid-listing: back to the chooser with that source disabled; the selection is kept. */
    private fun markSourceNoGrant() {
        val id = lastSourceId ?: return
        val name = _state.value.destinations.firstOrNull { it.graphId == id }?.name ?: id.value
        val reason = ReadCapabilityReason.NoGrant(canRegrant = true)
        setStatus(
            id,
            DestinationStatus.Disabled(
                DisabledKind.NoGrant,
                "Can't read $name: ${reason.userText}",
                DestinationAction(DestinationActionKind.RegrantAccess, CopyPagesState.RESELECT_FOLDER),
            ),
        )
        pull?.select(null)
        refreshList()
    }

    /** Stop / "Change source": cancels any listing and returns to the source chooser (selection kept). */
    fun clearSource() {
        pull?.select(null)
        _state.update { it.copy(destinationId = null, indexState = PullIndexState.Idle, rows = emptyList(), totalMatching = null, hasMore = false) }
        refreshList()
    }

    fun retryIndex() {
        pull?.retry()
    }

    // ---- selection ---------------------------------------------------------------------------

    fun toggleRow(uuid: PageUuid) {
        _state.update { it.copy(picked = it.picked.toggle(uuid)) }
        refreshLinkedDelta()
    }

    fun clearSelection() {
        selectAllJob?.cancel()
        _state.update { it.copy(picked = it.picked.clear(), selecting = false) }
        refreshLinkedDelta()
    }

    /** Replaces the selection with exactly the pages matching the current search and filters. */
    fun selectAllMatching() {
        val s = _state.value
        selectAllInto(s.filters.toSelectionFilter(), s.searchText.trim().ifEmpty { null })
    }

    /** Opens the confirmation that precedes selecting the entire graph. */
    fun requestSelectAllInGraph() {
        scope.launch {
            source.countPages(SelectionFilter(), null).fold(
                { e -> _state.update { it.copy(loadError = e.message, listLoad = ListLoad.Failed) } },
                { n -> _state.update { it.copy(confirmAllPages = n) } },
            )
        }
    }

    fun confirmSelectAllInGraph() {
        _state.update { it.copy(confirmAllPages = null) }
        selectAllInto(SelectionFilter(), null)
    }

    fun dismissSelectAllInGraph() = _state.update { it.copy(confirmAllPages = null) }

    private fun selectAllInto(filter: SelectionFilter, search: String?) {
        selectAllJob?.cancel()
        _state.update { it.copy(selecting = true) }
        selectAllJob = scope.launch {
            collectUuids(filter, search).fold(
                { e -> _state.update { it.copy(selecting = false, listLoad = ListLoad.Failed, loadError = e.message) } },
                { found ->
                    _state.update { it.copy(picked = it.picked.replaceWith(found), selecting = false) }
                    refreshLinkedDelta()
                },
            )
        }
    }

    /** Pages through the source in bounded chunks; never one unbounded query. */
    private suspend fun collectUuids(filter: SelectionFilter, search: String?): Either<DomainError, Set<PageUuid>> = either {
        val found = LinkedHashSet<PageUuid>()
        var offset = 0
        while (true) {
            val pages = source.listPages(filter, search, PageSource.MAX_PAGE_SIZE, offset).bind()
            if (pages.isEmpty()) break
            pages.forEach { found += it.uuid }
            offset += pages.size
        }
        found
    }

    // ---- close / discard ---------------------------------------------------------------------

    /** Esc / Back / Close: immediate with nothing selected, else asks first. */
    fun requestClose() {
        if (_state.value.picked.isEmpty()) {
            pull?.stop()
            eventChannel.trySend(CopyPagesEvent.Closed)
        } else {
            _state.update { it.copy(discardPrompt = true) }
        }
    }

    fun confirmDiscard() {
        selectAllJob?.cancel()
        pull?.stop()
        _state.update { it.copy(picked = it.picked.clear(), discardPrompt = false, selecting = false) }
        eventChannel.trySend(CopyPagesEvent.Closed)
    }

    fun keepEditing() = _state.update { it.copy(discardPrompt = false) }

    // ---- destinations ------------------------------------------------------------------------

    private fun syncDestinations(registry: GraphRegistry) {
        val known = _state.value.destinations.associateBy { it.graphId }
        val rows = registry.graphs.map { g ->
            when {
                g.id == activeGraphId -> DestinationRow(
                    g.id,
                    g.displayName,
                    DestinationStatus.Disabled(DisabledKind.CurrentGraph, CopyPagesState.CURRENT_GRAPH_TEXT),
                )
                else -> known[g.id]?.copy(name = g.displayName) ?: DestinationRow(g.id, g.displayName, DestinationStatus.Checking)
            }
        }
        val removed = known.keys - rows.map { it.graphId }.toSet()
        removed.forEach { probeJobs.remove(it)?.cancel() }
        _state.update { s ->
            s.copy(
                destinations = rows,
                destinationId = s.destinationId?.takeIf { id -> rows.any { it.graphId == id } },
            )
        }
        registry.graphs.filter { it.id != activeGraphId && known[it.id] == null }.forEach(::startProbe)
    }

    private fun startProbe(graph: GraphInfo) {
        probeJobs.remove(graph.id)?.cancel()
        setStatus(graph.id, DestinationStatus.Checking)
        probeJobs[graph.id] = scope.launch {
            val status = try {
                withTimeoutOrNull(probeTimeoutMs) { probe.probe(graph, direction) }
                    ?: DestinationStatus.CouldntCheck("Couldn't check ${graph.displayName}: timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DestinationStatus.CouldntCheck("Couldn't check ${graph.displayName}: ${e.message ?: "unknown error"}")
            }
            setStatus(graph.id, status)
            maybePreselect()
        }
    }

    private fun setStatus(id: GraphId, status: DestinationStatus) {
        _state.update { s ->
            val rows = s.destinations.map { if (it.graphId == id) it.copy(status = status) else it }
            // A chosen row that stopped being definitive-available can no longer be chosen.
            val keep = s.destinationId?.takeIf { dest -> rows.firstOrNull { it.graphId == dest }?.status == DestinationStatus.Available }
            s.copy(destinations = rows, destinationId = keep)
        }
    }

    fun retryProbe(graphId: GraphId) {
        graphRegistry.value.graphs.firstOrNull { it.id == graphId && it.id != activeGraphId }?.let(::startProbe)
    }

    /** Selects [graphId] only when its row is definitively available; anything else is ignored. */
    fun chooseDestination(graphId: GraphId) {
        val row = _state.value.destinations.firstOrNull { it.graphId == graphId } ?: return
        if (row.status != DestinationStatus.Available) return
        userChoseDestination = true
        _state.update { it.copy(destinationId = graphId) }
        if (pull != null) startPullSource(graphId)
        refreshLinkedDelta()
    }

    private fun startPullSource(graphId: GraphId) {
        val info = graphRegistry.value.graphs.firstOrNull { it.id == graphId } ?: return
        if (lastSourceId != graphId) _state.update { it.copy(picked = it.picked.clear()) }
        lastSourceId = graphId
        pull?.select(info)
    }

    private fun maybePreselect() {
        if (!preselectLastDestination || userChoseDestination || _state.value.destinationId != null) return
        val last = destinationSettings?.lastDestinationGraphId ?: return
        if (last == activeGraphId) return
        val row = _state.value.destinations.firstOrNull { it.graphId == last } ?: return
        if (row.status == DestinationStatus.Available) _state.update { it.copy(destinationId = last) }
    }

    fun onDestinationAction(graphId: GraphId, kind: DestinationActionKind) {
        eventChannel.trySend(
            when (kind) {
                DestinationActionKind.SwitchToPull -> CopyPagesEvent.SwitchToPull
                DestinationActionKind.RegrantAccess -> CopyPagesEvent.RegrantAccess(graphId)
                DestinationActionKind.OpenGraph -> CopyPagesEvent.OpenGraph(graphId)
            },
        )
    }

    fun addGraph() {
        eventChannel.trySend(CopyPagesEvent.AddGraph)
    }

    /** Call when the user confirms the dry run (starts the copy); remembers the destination (Gate 2). */
    fun recordDestinationConfirmed() {
        _state.value.destinationId?.let { destinationSettings?.lastDestinationGraphId = it }
    }

    // ---- linked pages (Gate 2) ---------------------------------------------------------------

    fun setIncludeLinked(on: Boolean) {
        if (!gate2LinkedPages || direction == CopyDirection.Pull) return
        _state.update { it.copy(includeLinked = on, includeAssets = it.includeAssets && on, linkedDelta = null) }
        refreshLinkedDelta()
    }

    fun setIncludeAssets(on: Boolean) {
        if (!gate2LinkedPages || direction == CopyDirection.Pull) return
        _state.update { it.copy(includeAssets = on && it.includeLinked) }
    }

    private fun refreshLinkedDelta() {
        val s = _state.value
        linkedJob?.cancel()
        if (!gate2LinkedPages || !s.includeLinked || s.picked.isEmpty()) {
            _state.update { it.copy(linkedDelta = if (s.includeLinked) 0 else null) }
            return
        }
        linkedJob = scope.launch {
            val delta = gateway.linkedPageDelta(buildRequest(_state.value, s.destinationId ?: activeGraphId))
            _state.update { it.copy(linkedDelta = delta) }
        }
    }

    // ---- review ------------------------------------------------------------------------------

    /** Runs the dry run; `review` becomes [ReviewState.Ready] for the dry-run dialog to render. */
    fun review() {
        val s = _state.value
        val other = s.destinationId ?: return
        if (!s.canReview) return
        val request = buildRequest(s, other)
        _state.update { it.copy(review = ReviewState.Planning) }
        reviewJob?.cancel()
        reviewJob = scope.launch {
            gateway.plan(request).fold(
                { e -> _state.update { it.copy(review = ReviewState.Failed(e.message)) } },
                { plan -> _state.update { it.copy(review = ReviewState.Ready(request, plan)) } },
            )
        }
    }

    /** Dry-run dialog took over (or was dismissed): back to the picker with selection intact. */
    fun consumeReview() {
        reviewJob?.cancel()
        _state.update { it.copy(review = ReviewState.Idle) }
    }

    /** [other] is the chosen graph: the target in Push, the source in Pull. */
    private fun buildRequest(s: CopyPagesState, other: GraphId): PlanRequest {
        val activeName = s.activeGraphName
        val otherName = s.destinations.firstOrNull { it.graphId == other }?.name ?: other.value
        val selection = PageSelection(
            filter = s.filters.toSelectionFilter(),
            search = s.searchText.trim().ifEmpty { null },
            include = s.picked.uuids,
        )
        val closure = if (gate2LinkedPages && s.includeLinked) LinkClosurePolicy.Depth1() else LinkClosurePolicy.Off
        return when (direction) {
            CopyDirection.Push -> PlanRequest(selection, activeGraphId, other, activeName, closure, direction)
            CopyDirection.Pull -> PlanRequest(selection, other, activeGraphId, otherName, closure, direction)
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val PROBE_TIMEOUT_MS = 3_000L
        const val INDEX_REFRESH_MS = 100L
    }
}
