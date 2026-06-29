package dev.stapler.stelekit.ui.assets

import androidx.compose.runtime.RememberObserver
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetSortOrder
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.export.ClipboardProvider
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the asset browser screen.
 *
 * Owns its own [CoroutineScope] with a [CoroutineExceptionHandler] that catches [Throwable].
 * Implements [RememberObserver] so the scope is cancelled when the screen leaves composition.
 * Must be stored via `remember { AssetBrowserViewModel(...) }` — NOT via `rememberCoroutineScope()`.
 *
 * NOTE: [loadAssets] and [loadMore] call repository methods added in Epics 5–7 of the
 * assets-viewer plan ([AssetRepository.getAssetPage], [AssetRepository.getDistinctTags],
 * [AssetRepository.getOrphanedAssets] keyset overload). This file will not compile until
 * the repository layer is updated by the parallel repository agent.
 */
class AssetBrowserViewModel(
    private val assetRepository: AssetRepository,
    private val writeActor: DatabaseWriteActor? = null,
) : RememberObserver {
    private val logger = Logger("AssetBrowserViewModel")
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                logger.error("AssetBrowserViewModel uncaught: ${throwable.message}")
            }
    )

    private val _uiState = MutableStateFlow(AssetBrowserUiState())
    val uiState: StateFlow<AssetBrowserUiState> = _uiState.asStateFlow()

    private var searchDebounceJob: Job? = null
    private var loadJob: Job? = null
    private var tagsJob: Job? = null

    companion object {
        private const val PAGE_SIZE = 50
    }

    init {
        loadAssets()
        loadDistinctTags()
    }

    fun setFilter(filter: AssetFilter) {
        _uiState.update {
            it.copy(
                selectedFilter = filter,
                assets = emptyList(),
                cursorMs = null,
                cursorName = null,
                cursorSize = null,
                cursorUuid = null,
            )
        }
        loadAssets()
    }

    fun setSearch(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                assets = emptyList(),
                cursorMs = null,
                cursorName = null,
                cursorSize = null,
                cursorUuid = null,
            )
        }
        searchDebounceJob?.cancel()
        searchDebounceJob = scope.launch {
            delay(300)
            loadAssets()
        }
    }

    fun setSort(sort: AssetSortOrder) {
        _uiState.update {
            it.copy(
                sortOrder = sort,
                assets = emptyList(),
                cursorMs = null,
                cursorName = null,
                cursorSize = null,
                cursorUuid = null,
            )
        }
        loadAssets()
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun createGroup(name: String) {
        logger.info("createGroup: $name")
    }

    fun refresh() {
        _uiState.update {
            it.copy(
                assets = emptyList(),
                cursorMs = null,
                cursorName = null,
                cursorSize = null,
                cursorUuid = null,
            )
        }
        loadAssets()
    }

    /**
     * Loads the next page of assets and appends it to [AssetBrowserUiState.assets].
     * No-ops if already loading or there are no more pages ([AssetBrowserUiState.hasMore] = false).
     */
    fun loadMore() {
        val s = _uiState.value
        if (s.isLoadingMore || !s.hasMore) return
        _uiState.update { it.copy(isLoadingMore = true) }
        scope.launch {
            try {
                val state = _uiState.value
                val flow = when {
                    state.selectedFilter == AssetFilter.ORPHANED ->
                        assetRepository.getOrphanedAssets(
                            cursorMs = state.cursorMs,
                            limit = PAGE_SIZE,
                        )
                    state.selectedFilter is AssetFilter.TAG ->
                        assetRepository.getAssetPage(
                            mediaType = null,
                            searchQuery = state.selectedFilter.name,
                            sortOrder = state.sortOrder,
                            cursorMs = state.cursorMs,
                            cursorName = state.cursorName,
                            cursorSize = state.cursorSize,
                            cursorUuid = state.cursorUuid,
                            limit = PAGE_SIZE,
                        )
                    else ->
                        assetRepository.getAssetPage(
                            mediaType = state.selectedFilter.toMediaType(),
                            searchQuery = state.searchQuery,
                            sortOrder = state.sortOrder,
                            cursorMs = state.cursorMs,
                            cursorName = state.cursorName,
                            cursorSize = state.cursorSize,
                            cursorUuid = state.cursorUuid,
                            limit = PAGE_SIZE,
                        )
                }
                flow.first().fold(
                    ifLeft = { err ->
                        _uiState.update { it.copy(isLoadingMore = false, error = err.message) }
                    },
                    ifRight = { page ->
                        val last = page.lastOrNull()
                        _uiState.update { st ->
                            st.copy(
                                assets = st.assets + page,
                                isLoadingMore = false,
                                hasMore = page.size == PAGE_SIZE,
                                cursorMs = if (st.sortOrder == AssetSortOrder.BY_DATE_ADDED) last?.importedAtMs else null,
                                cursorName = if (st.sortOrder == AssetSortOrder.BY_NAME) last?.filePath else null,
                                cursorSize = if (st.sortOrder == AssetSortOrder.BY_SIZE) last?.sizeBytes else null,
                                cursorUuid = if (st.sortOrder != AssetSortOrder.BY_DATE_ADDED) last?.uuid?.value else null,
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoadingMore = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    private fun loadDistinctTags() {
        tagsJob?.cancel()
        tagsJob = scope.launch {
            try {
                assetRepository.getDistinctTags().fold(
                    ifLeft = { /* silently ignore tag loading errors */ },
                    ifRight = { tags -> _uiState.update { it.copy(availableTags = tags) } }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("loadDistinctTags error: ${e.message}")
            }
        }
    }

    private fun loadAssets() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val state = _uiState.value
                val flow = when {
                    state.selectedFilter == AssetFilter.ORPHANED ->
                        assetRepository.getOrphanedAssets(
                            cursorMs = state.cursorMs,
                            limit = PAGE_SIZE,
                        )
                    state.selectedFilter is AssetFilter.TAG ->
                        assetRepository.getAssetPage(
                            mediaType = null,
                            searchQuery = state.selectedFilter.name,
                            sortOrder = state.sortOrder,
                            cursorMs = state.cursorMs,
                            cursorName = state.cursorName,
                            cursorSize = state.cursorSize,
                            cursorUuid = state.cursorUuid,
                            limit = PAGE_SIZE,
                        )
                    else ->
                        assetRepository.getAssetPage(
                            mediaType = state.selectedFilter.toMediaType(),
                            searchQuery = state.searchQuery,
                            sortOrder = state.sortOrder,
                            cursorMs = state.cursorMs,
                            cursorName = state.cursorName,
                            cursorSize = state.cursorSize,
                            cursorUuid = state.cursorUuid,
                            limit = PAGE_SIZE,
                        )
                }
                flow.collect { result ->
                    result.fold(
                        ifLeft = { err ->
                            _uiState.update { it.copy(isLoading = false, error = err.message) }
                        },
                        ifRight = { page ->
                            val last = page.lastOrNull()
                            _uiState.update { st ->
                                st.copy(
                                    isLoading = false,
                                    assets = page,
                                    hasMore = page.size == PAGE_SIZE,
                                    cursorMs = if (st.sortOrder == AssetSortOrder.BY_DATE_ADDED) last?.importedAtMs else null,
                                    cursorName = if (st.sortOrder == AssetSortOrder.BY_NAME) last?.filePath else null,
                                    cursorSize = if (st.sortOrder == AssetSortOrder.BY_SIZE) last?.sizeBytes else null,
                                    cursorUuid = if (st.sortOrder != AssetSortOrder.BY_DATE_ADDED) last?.uuid?.value else null,
                                )
                            }
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun showActionMenu(asset: AssetEntry) {
        _uiState.update { it.copy(actionMenuAsset = asset) }
    }

    fun dismissActionMenu() {
        _uiState.update { it.copy(actionMenuAsset = null) }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun deleteAsset(asset: AssetEntry) {
        dismissActionMenu()
        scope.launch {
            val result = if (writeActor != null) {
                writeActor.execute { assetRepository.deleteAsset(asset.uuid) }
            } else {
                assetRepository.deleteAsset(asset.uuid)
            }
            result.onLeft { err ->
                _uiState.update { it.copy(error = err.message) }
            }
            // Optimistic update: remove from the visible list immediately.
            _uiState.update { state -> state.copy(assets = state.assets.filter { it.uuid != asset.uuid }) }
        }
    }

    fun copyMarkdownLink(asset: AssetEntry, clipboardProvider: ClipboardProvider) {
        dismissActionMenu()
        val fileName = asset.filePath.substringAfterLast('/')
        val link = when (asset.mediaType) {
            AssetMediaType.IMAGE -> "![${fileName}](${asset.relativePath})"
            else -> "[${fileName}](${asset.relativePath})"
        }
        clipboardProvider.writeText(link)
    }

    override fun onRemembered() { /* scope already started */ }
    override fun onForgotten() { scope.cancel() }
    override fun onAbandoned() { scope.cancel() }
}
