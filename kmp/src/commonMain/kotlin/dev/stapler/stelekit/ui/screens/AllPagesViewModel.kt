// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException

data class PageRow(val page: Page, val backlinkCount: Int = 0)

enum class SortColumn { NAME, BACKLINKS, LAST_MODIFIED, CREATED }

enum class PageTypeFilter { ALL, JOURNALS, PAGES }

@OptIn(FlowPreview::class)
class AllPagesViewModel(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    // Default scope owns its lifecycle; callers in remember{} must not pass rememberCoroutineScope()
    // which is cancelled when the composable leaves composition. Tests inject a TestCoroutineScope.
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val scope = scope
    private val _allRows = MutableStateFlow<List<PageRow>>(emptyList())
    private val _filterQuery = MutableStateFlow("")
    private val _sortColumn = MutableStateFlow(SortColumn.NAME)
    private val _sortAscending = MutableStateFlow(true)
    private val _pageTypeFilter = MutableStateFlow(PageTypeFilter.ALL)
    private val _isLoading = MutableStateFlow(true)
    private val _selectedUuids = MutableStateFlow<Set<String>>(emptySet())

    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    val selectedUuids: StateFlow<Set<String>> = _selectedUuids.asStateFlow()
    val isInSelectionMode: StateFlow<Boolean> = _selectedUuids
        .map { it.isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val pages: StateFlow<List<PageRow>> = combine(
        combine(_allRows, _filterQuery.debounce(300)) { rows, query -> Pair(rows, query) },
        combine(_sortColumn, _sortAscending, _pageTypeFilter) { col, asc, typeFilter -> Triple(col, asc, typeFilter) }
    ) { (rows, query), (col, asc, typeFilter) ->
        val filtered = rows
            .filter { row ->
                when (typeFilter) {
                    PageTypeFilter.JOURNALS -> row.page.isJournal
                    PageTypeFilter.PAGES -> !row.page.isJournal
                    PageTypeFilter.ALL -> true
                }
            }
            .filter { row -> query.isEmpty() || row.page.name.contains(query, ignoreCase = true) }

        val comparator: Comparator<PageRow> = when (col) {
            SortColumn.NAME -> compareBy { it.page.name.lowercase() }
            SortColumn.BACKLINKS -> compareBy { it.backlinkCount }
            SortColumn.LAST_MODIFIED -> compareBy { it.page.updatedAt }
            SortColumn.CREATED -> compareBy { it.page.createdAt }
        }
        if (asc) filtered.sortedWith(comparator) else filtered.sortedWith(comparator.reversed())
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun onFilterChange(q: String) { _filterQuery.value = q }

    fun toggleSort(col: SortColumn) {
        if (_sortColumn.value == col) {
            _sortAscending.update { !it }
        } else {
            _sortColumn.value = col
            _sortAscending.value = true
        }
    }

    fun setPageTypeFilter(f: PageTypeFilter) { _pageTypeFilter.value = f }

    fun toggleSelection(uuid: String) {
        _selectedUuids.update { if (uuid in it) it - uuid else it + uuid }
    }

    fun selectAll() {
        _selectedUuids.update { pages.value.map { it.page.uuid }.toSet() }
    }

    fun clearSelection() {
        _selectedUuids.update { emptySet() }
    }

    fun refresh() {
        scope.launch { loadAllPages() }
    }

    val sortColumn: StateFlow<SortColumn> = _sortColumn.asStateFlow()
    val sortAscending: StateFlow<Boolean> = _sortAscending.asStateFlow()
    val filterQuery: StateFlow<String> = _filterQuery.asStateFlow()
    val pageTypeFilter: StateFlow<PageTypeFilter> = _pageTypeFilter.asStateFlow()

    init {
        scope.launch { loadAllPages() }
    }

    private suspend fun loadAllPages() {
        _isLoading.value = true
        try {
            pageRepository.getAllPages()
                .distinctUntilChanged { old, new ->
                    // Only re-trigger backlink loading when the set of page UUIDs changes,
                    // not on every metadata update (e.g. updatedAt tick during indexing).
                    old.getOrNull()?.map { it.uuid } == new.getOrNull()?.map { it.uuid }
                }
                .collect { result ->
                    val pageList = result.getOrNull()
                    if (pageList != null) {
                        // Preserve existing backlink counts for pages already loaded.
                        val existingCounts = _allRows.value.associate { it.page.uuid to it.backlinkCount }
                        _allRows.value = pageList.map { PageRow(it, existingCounts[it.uuid] ?: 0) }
                        _isLoading.value = false

                        // Only fetch backlink counts for pages we haven't counted yet.
                        val uncounted = pageList.filter { existingCounts[it.uuid] == null }
                        if (uncounted.isNotEmpty()) {
                            val semaphore = Semaphore(8)
                            uncounted.forEach { page ->
                                scope.launch {
                                    semaphore.withPermit {
                                        val count = blockRepository.countLinkedReferences(page.name)
                                            .first().getOrNull() ?: 0L
                                        _allRows.update { rows ->
                                            rows.map { row ->
                                                if (row.page.uuid == page.uuid) row.copy(backlinkCount = count.toInt()) else row
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        _isLoading.value = false
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _isLoading.value = false
        }
    }

    fun close() {
        scope.cancel()
    }
}
