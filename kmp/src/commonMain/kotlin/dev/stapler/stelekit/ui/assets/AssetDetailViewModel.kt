package dev.stapler.stelekit.ui.assets

import androidx.compose.runtime.RememberObserver
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.repository.AssetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AssetDetailUiState(
    val asset: AssetEntry? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

class AssetDetailViewModel(
    private val assetRepository: AssetRepository,
    private val assetUuid: AssetUuid,
) : RememberObserver {
    private val logger = Logger("AssetDetailViewModel")
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                logger.error("AssetDetailViewModel uncaught: ${throwable.message}")
            }
    )
    private var loadJob: Job? = null

    private val _uiState = MutableStateFlow(AssetDetailUiState())
    val uiState: StateFlow<AssetDetailUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _uiState.value = AssetDetailUiState(isLoading = true)
            try {
                assetRepository.getAssetByUuid(assetUuid).collect { result ->
                    result.fold(
                        ifLeft = { err ->
                            _uiState.value = AssetDetailUiState(isLoading = false, error = err.message)
                        },
                        ifRight = { asset ->
                            _uiState.value = AssetDetailUiState(asset = asset, isLoading = false)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.value = AssetDetailUiState(isLoading = false, error = e.message ?: "Unknown error")
            }
        }
    }

    override fun onRemembered() {}
    override fun onForgotten() { scope.cancel() }
    override fun onAbandoned() { scope.cancel() }
}
