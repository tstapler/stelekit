package dev.stapler.stelekit.ui.assets

import androidx.compose.runtime.RememberObserver
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.repository.AssetRepository
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the asset browser screen.
 *
 * Owns its own [CoroutineScope] with a [CoroutineExceptionHandler] that catches [Throwable].
 * Implements [RememberObserver] so the scope is cancelled when the screen leaves composition.
 * Must be stored via `remember { AssetBrowserViewModel(...) }` — NOT via `rememberCoroutineScope()`.
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

    init {
        loadAssets()
    }

    fun setFilter(filter: AssetFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
        loadAssets()
    }

    fun setSearch(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchDebounceJob?.cancel()
        searchDebounceJob = scope.launch {
            delay(300)
            loadAssets()
        }
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun createGroup(name: String) {
        logger.info("createGroup: $name")
    }

    fun refresh() {
        loadAssets()
    }

    private fun loadAssets() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val state = _uiState.value
                val flow = when {
                    state.searchQuery.isNotBlank() ->
                        assetRepository.searchAssets(state.searchQuery, limit = 50, offset = 0)
                    state.selectedFilter == AssetFilter.ALL ->
                        assetRepository.getAssets(limit = 50, offset = 0)
                    state.selectedFilter == AssetFilter.IMAGES ->
                        assetRepository.getAssetsByMediaType(AssetMediaType.IMAGE, limit = 50, offset = 0)
                    state.selectedFilter == AssetFilter.PDFS ->
                        assetRepository.getAssetsByMediaType(AssetMediaType.PDF, limit = 50, offset = 0)
                    state.selectedFilter == AssetFilter.AUDIO ->
                        assetRepository.getAssetsByMediaType(AssetMediaType.AUDIO, limit = 50, offset = 0)
                    state.selectedFilter == AssetFilter.VIDEO ->
                        assetRepository.getAssetsByMediaType(AssetMediaType.VIDEO, limit = 50, offset = 0)
                    state.selectedFilter == AssetFilter.DOCUMENTS ->
                        assetRepository.getAssetsByMediaType(AssetMediaType.DOCUMENT, limit = 50, offset = 0)
                    else ->
                        assetRepository.getAssets(limit = 50, offset = 0)
                }
                flow.collect { result ->
                    result.fold(
                        ifLeft = { err ->
                            _uiState.update { it.copy(isLoading = false, error = err.message) }
                        },
                        ifRight = { assets ->
                            _uiState.update { it.copy(isLoading = false, assets = assets) }
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

    override fun onRemembered() { /* scope already started */ }
    override fun onForgotten() { scope.cancel() }
    override fun onAbandoned() { scope.cancel() }
}
