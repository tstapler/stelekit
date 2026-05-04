package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.DataType
import dev.stapler.stelekit.repository.DateRange
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.SearchRepository
import dev.stapler.stelekit.repository.SearchRequest
import dev.stapler.stelekit.repository.SearchScope
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

private const val MAX_RECENT_QUERIES = 10

// Regexes used by parseQuery — compiled once at file scope
private val MODIFIED_REGEX = Regex("""modified:(today|day|week|month|year)""", RegexOption.IGNORE_CASE)
private val TAG_REGEX = Regex("""#(\S+)""")
private val SCOPE_REGEX = Regex("""/(pages?|blocks?|journal|current)\b""", RegexOption.IGNORE_CASE)
private val PROPERTY_REGEX = Regex("""(\w+)::(\w+)""")

data class ParsedQuery(
    val ftsTerm: String,
    val dateRange: DateRange? = null,
    val tagFilter: String? = null,
    val scopeOverride: SearchScope? = null,
    val propertyFilters: Map<String, String> = emptyMap()
)

enum class ActivePrefixMode { NONE, MODIFIED_DATE, TAG, SCOPE, PROPERTY }

sealed class PreviewPanelContent {
    data class PagePreview(val pageUuid: String, val pageTitle: String) : PreviewPanelContent()
    data class BlockPreview(val blockUuid: String, val pageTitle: String, val blockSnippet: String) : PreviewPanelContent()
    data object Empty : PreviewPanelContent()
}

private fun parseQuery(raw: String): ParsedQuery {
    var remainder = raw

    // Extract modified: date range
    val modifiedMatch = MODIFIED_REGEX.find(remainder)
    var dateRange: DateRange? = null
    if (modifiedMatch != null) {
        val now = Clock.System.now()
        val startDate = when (modifiedMatch.groupValues[1].lowercase()) {
            "today", "day" -> now - 1.days
            "week" -> now - 7.days
            "month" -> now - 30.days
            "year" -> now - 365.days
            else -> now - 1.days
        }
        dateRange = DateRange(startDate = startDate, endDate = now)
        remainder = remainder.replace(modifiedMatch.value, "")
    }

    // Extract #tag filter (first occurrence)
    val tagMatch = TAG_REGEX.find(remainder)
    val tagFilter: String? = tagMatch?.groupValues?.get(1)
    if (tagMatch != null) {
        remainder = remainder.replace(tagMatch.value, "")
    }

    // Extract /scope override
    val scopeMatch = SCOPE_REGEX.find(remainder)
    val scopeOverride: SearchScope? = scopeMatch?.let { m ->
        when (m.groupValues[1].lowercase()) {
            "page", "pages" -> SearchScope.PAGES_ONLY
            "block", "blocks" -> SearchScope.BLOCKS_ONLY
            "journal" -> SearchScope.JOURNAL
            "current" -> SearchScope.CURRENT_PAGE
            else -> null
        }
    }
    if (scopeMatch != null) {
        remainder = remainder.replace(scopeMatch.value, "")
    }

    // Extract property filters (key::value)
    val propertyFilters = mutableMapOf<String, String>()
    for (m in PROPERTY_REGEX.findAll(remainder).toList()) {
        propertyFilters[m.groupValues[1]] = m.groupValues[2]
        remainder = remainder.replace(m.value, "")
    }

    return ParsedQuery(
        ftsTerm = remainder.trim(),
        dateRange = dateRange,
        tagFilter = tagFilter,
        scopeOverride = scopeOverride,
        propertyFilters = propertyFilters
    )
}

private fun activeModeFor(parsed: ParsedQuery): ActivePrefixMode = when {
    parsed.dateRange != null -> ActivePrefixMode.MODIFIED_DATE
    parsed.tagFilter != null -> ActivePrefixMode.TAG
    parsed.scopeOverride != null -> ActivePrefixMode.SCOPE
    parsed.propertyFilters.isNotEmpty() -> ActivePrefixMode.PROPERTY
    else -> ActivePrefixMode.NONE
}

class SearchViewModel(
    private val searchRepository: SearchRepository,
    // Default scope owns its lifecycle; callers in remember{} must not pass rememberCoroutineScope()
    // which is cancelled when the composable leaves composition. Tests inject a TestCoroutineScope.
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val pageRepository: PageRepository? = null
) {
    private val scope = scope
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var previewJob: Job? = null

    init {
        if (pageRepository != null) {
            loadRecentPages()
        }
    }

    private fun loadRecentPages() {
        val repo = pageRepository ?: return
        scope.launch {
            try {
                repo.getRecentPages(limit = 8).first().getOrNull()?.let { pages ->
                    val recentPageItems = pages.map { page ->
                        SearchResultItem.PageItem(
                            page = page,
                            backlinkCount = 0,
                            tags = parseTagsFromProperties(page.properties),
                            breadcrumb = page.namespace?.replace("/", " / "),
                            visitedAt = page.updatedAt
                        )
                    }
                    _uiState.update { it.copy(recentPages = recentPageItems) }
                }
            } catch (_: Exception) {
                // Ignore errors loading recent pages — non-critical
            }
        }
    }

    fun onQueryChange(query: String) {
        // Parse prefixes immediately (before debounce) and update active mode + skeleton
        val parsed = parseQuery(query)
        _uiState.update { it.copy(
            query = query,
            parsedQuery = parsed,
            activePrefixMode = activeModeFor(parsed),
            isSkeletonVisible = query.isNotBlank()
        ) }

        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isLoading = false, isSkeletonVisible = false) }
            return
        }

        searchJob = scope.launch {
            delay(300) // Debounce — skeleton shown during this window
            _uiState.update { it.copy(isSkeletonVisible = false, isLoading = true) }

            try {
                // Merge tag filter into property filters if present
                val effectivePropertyFilters = if (parsed.tagFilter != null) {
                    parsed.propertyFilters + mapOf("tags" to parsed.tagFilter)
                } else {
                    parsed.propertyFilters
                }

                val request = SearchRequest(
                    query = parsed.ftsTerm.takeIf { it.isNotBlank() } ?: query,
                    scope = parsed.scopeOverride ?: _uiState.value.scope,
                    dataTypes = setOf(DataType.TITLES, DataType.CONTENT),
                    propertyFilters = effectivePropertyFilters,
                    dateRange = parsed.dateRange,
                    limit = 20
                )
                searchRepository.searchWithFilters(request).collect { result ->
                    val searchResult = result.getOrNull()
                    if (searchResult != null) {
                        val items = mutableListOf<SearchResultItem>()

                        // Build a page name map from all pages in the result for block breadcrumbs
                        val pageNameMap: Map<String, String> = searchResult.pages.associate { it.uuid to it.name }

                        // Pages section — prefer searchedPages (with snippets) if available
                        val pagedItems = if (searchResult.searchedPages.isNotEmpty()) {
                            searchResult.searchedPages.map { sp ->
                                SearchResultItem.PageItem(
                                    page = sp.page,
                                    snippet = sp.snippet,
                                    backlinkCount = sp.backlinkCount,
                                    tags = parseTagsFromProperties(sp.page.properties),
                                    breadcrumb = sp.page.namespace?.replace("/", " / ")
                                )
                            }
                        } else {
                            searchResult.pages.map { page ->
                                SearchResultItem.PageItem(
                                    page = page,
                                    backlinkCount = 0,
                                    tags = parseTagsFromProperties(page.properties),
                                    breadcrumb = page.namespace?.replace("/", " / ")
                                )
                            }
                        }
                        if (pagedItems.isNotEmpty()) {
                            items.add(SearchResultItem.Header("Pages"))
                            items.addAll(pagedItems)
                        }

                        // Blocks section — prefer searchedBlocks (with snippets) if available
                        val blockItems = if (searchResult.searchedBlocks.isNotEmpty()) {
                            searchResult.searchedBlocks.map { sb ->
                                SearchResultItem.BlockItem(
                                    block = sb.block,
                                    snippet = sb.snippet,
                                    breadcrumb = pageNameMap[sb.block.pageUuid]
                                )
                            }
                        } else {
                            searchResult.blocks.map { block ->
                                SearchResultItem.BlockItem(
                                    block = block,
                                    breadcrumb = pageNameMap[block.pageUuid]
                                )
                            }
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
                                listOf(SearchResultItem.CreatePageItem(query)) + items
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
                                isSkeletonVisible = false,
                                recentQueries = recentQueries
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = false, isSkeletonVisible = false, error = "Search failed") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, isSkeletonVisible = false, error = e.message) }
            }
        }
    }

    fun onSelectionChange(index: Int) {
        previewJob?.cancel()
        val state = _uiState.value
        val list: List<SearchResultItem> = if (state.query.isBlank()) state.recentPages else state.results
        if (index < 0 || index >= list.size) {
            _uiState.update { it.copy(previewContent = PreviewPanelContent.Empty) }
            return
        }
        previewJob = scope.launch {
            delay(150)
            val preview: PreviewPanelContent = when (val item = list[index]) {
                is SearchResultItem.PageItem -> PreviewPanelContent.PagePreview(
                    pageUuid = item.page.uuid,
                    pageTitle = item.page.name
                )
                is SearchResultItem.BlockItem -> PreviewPanelContent.BlockPreview(
                    blockUuid = item.block.uuid,
                    pageTitle = item.breadcrumb ?: "Unknown",
                    blockSnippet = item.block.content.take(200)
                )
                else -> PreviewPanelContent.Empty
            }
            _uiState.update { it.copy(previewContent = preview) }
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

    private fun parseTagsFromProperties(properties: Map<String, String>): List<String> {
        val tagsValue = properties["tags"] ?: return emptyList()
        return tagsValue.split(Regex("[,\\s]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResultItem> = emptyList(),
    val isLoading: Boolean = false,
    val isSkeletonVisible: Boolean = false,
    val error: String? = null,
    val scope: SearchScope = SearchScope.ALL,
    val recentQueries: List<String> = emptyList(),
    val recentPages: List<SearchResultItem.PageItem> = emptyList(),
    val activePrefixMode: ActivePrefixMode = ActivePrefixMode.NONE,
    val parsedQuery: ParsedQuery? = null,
    val previewContent: PreviewPanelContent = PreviewPanelContent.Empty
)

sealed class SearchResultItem {
    data class Header(val title: String) : SearchResultItem()
    data class PageItem(
        val page: Page,
        val snippet: String? = null,
        val backlinkCount: Int = 0,
        val tags: List<String> = emptyList(),
        val breadcrumb: String? = null,
        val visitedAt: Instant? = null
    ) : SearchResultItem()
    data class AliasItem(val page: Page, val alias: String) : SearchResultItem()
    data class BlockItem(
        val block: Block,
        val snippet: String? = null,
        val breadcrumb: String? = null
    ) : SearchResultItem()
    data class CreatePageItem(val query: String) : SearchResultItem()
}
