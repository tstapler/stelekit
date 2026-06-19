package dev.stapler.stelekit.ui.assets

import dev.stapler.stelekit.asset.AssetEntry

data class AssetBrowserUiState(
    val assets: List<AssetEntry> = emptyList(),
    val selectedFilter: AssetFilter = AssetFilter.ALL,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalCount: Long = 0,
    val viewMode: ViewMode = ViewMode.GRID,
)

enum class AssetFilter {
    ALL, IMAGES, PDFS, AUDIO, VIDEO, DOCUMENTS, FILES, ORPHANED
}

enum class ViewMode { GRID, LIST }
