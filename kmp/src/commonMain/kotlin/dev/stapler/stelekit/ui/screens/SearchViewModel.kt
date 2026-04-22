package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.SearchRepository
import dev.stapler.stelekit.repository.SearchRequest
import dev.stapler.stelekit.repository.SearchScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val MAX_RECENT_QUERIES = 10

class SearchViewModel(
    private val searchRepository: SearchRepository,
    // Default scope owns its lifecycle; callers in remember{} must not pass rememberCoroutineScope()
    // which is cancelled when the composable leaves composition. Tests inject a TestCoroutineScope.
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val scope = scope
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }

        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isLoading = false) }
            return
        }

        searchJob = scope.launch {
            delay(300) // Debounce
            _uiState.update { it.copy(isLoading = true) }

            try {
                val request = SearchRequest(
                    query = query,
                    scope = _uiState.value.scope,
                    limit = 20
                )
                searchRepository.searchWithFilters(request).collect { result ->
                    val searchResult = result.getOrNull()
                    if (searchResult != null) {
                        val items = mutableListOf<SearchResultItem>()

                        // Pages section — prefer searchedPages (with snippets) if available
                        val pagedItems = if (searchResult.searchedPages.isNotEmpty()) {
                            searchResult.searchedPages.map { SearchResultItem.PageItem(it.page, it.snippet) }
                        } else {
                            searchResult.pages.map { SearchResultItem.PageItem(it) }
                        }
                        if (pagedItems.isNotEmpty()) {
                            items.add(SearchResultItem.Header("Pages"))
                            items.addAll(pagedItems)
                        }

                        // Blocks section — prefer searchedBlocks (with snippets) if available
                        val blockItems = if (searchResult.searchedBlocks.isNotEmpty()) {
                            searchResult.searchedBlocks.map { SearchResultItem.BlockItem(it.block, it.snippet) }
                        } else {
                            searchResult.blocks.map { SearchResultItem.BlockItem(it) }
                        }
                        if (blockItems.isNotEmpty()) {
                            items.add(SearchResultItem.Header("Blocks"))
                            items.addAll(blockItems)
                        }

                        _uiState.update { state ->
                            val exactPageMatch = items.any {
                                it is SearchResultItem.PageItem &&
                                    it.page.name.equals(query, ignoreCase = true)
                            }
                            val withCreate = if (!exactPageMatch && query.isNotBlank()) {
                                items + SearchResultItem.CreatePageItem(query)
                            } else {
                                items
                            }

                            // Record in recent queries when results are non-empty
                            val recentQueries = if (items.isNotEmpty()) {
                                val updated = (listOf(query) + state.recentQueries)
                                    .distinct()
                                    .take(MAX_RECENT_QUERIES)
                                updated
                            } else {
                                state.recentQueries
                            }

                            state.copy(
                                results = withCreate,
                                isLoading = false,
                                recentQueries = recentQueries
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = false, error = "Search failed") }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun onScopeChange(newScope: SearchScope) {
        _uiState.update { it.copy(scope = newScope) }
        val currentQuery = _uiState.value.query
        if (currentQuery.isNotBlank()) {
            onQueryChange(currentQuery)
        }
    }

    fun close() {
        scope.cancel()
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResultItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val scope: SearchScope = SearchScope.ALL,
    val recentQueries: List<String> = emptyList()
)

sealed class SearchResultItem {
    data class Header(val title: String) : SearchResultItem()
    data class PageItem(val page: Page, val snippet: String? = null) : SearchResultItem()
    data class AliasItem(val page: Page, val alias: String) : SearchResultItem()
    data class BlockItem(val block: Block, val snippet: String? = null) : SearchResultItem()
    data class CreatePageItem(val query: String) : SearchResultItem()
}
