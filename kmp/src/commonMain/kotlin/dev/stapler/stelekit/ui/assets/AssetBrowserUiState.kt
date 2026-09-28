package dev.stapler.stelekit.ui.assets

import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetSortOrder

sealed class AssetFilter {
    data object ALL : AssetFilter()
    data object IMAGES : AssetFilter()
    data object PDFS : AssetFilter()
    data object AUDIO : AssetFilter()
    data object VIDEO : AssetFilter()
    data object DOCUMENTS : AssetFilter()
    data object FILES : AssetFilter()
    data object ORPHANED : AssetFilter()
    data class TAG(val name: String) : AssetFilter()

    companion object {
        /** Ordered list of all static variants for UI chip rows.
         *  Replaces `AssetFilter.entries` (enum API; unavailable on sealed classes). */
        val all: List<AssetFilter> = listOf(ALL, IMAGES, PDFS, AUDIO, VIDEO, DOCUMENTS, FILES, ORPHANED)
    }
}

fun AssetFilter.displayName(): String = when (this) {
    AssetFilter.ALL -> "All"
    AssetFilter.IMAGES -> "Images"
    AssetFilter.PDFS -> "PDFs"
    AssetFilter.AUDIO -> "Audio"
    AssetFilter.VIDEO -> "Video"
    AssetFilter.DOCUMENTS -> "Documents"
    AssetFilter.FILES -> "Files"
    AssetFilter.ORPHANED -> "Orphaned"
    is AssetFilter.TAG -> name
}

/**
 * Maps an [AssetFilter] to its corresponding [AssetMediaType], or null for filters that are
 * not media-type-specific (ALL, ORPHANED, TAG — handled by dedicated branches in loadAssets()).
 */
fun AssetFilter.toMediaType(): AssetMediaType? = when (this) {
    AssetFilter.IMAGES -> AssetMediaType.IMAGE
    AssetFilter.PDFS -> AssetMediaType.PDF
    AssetFilter.AUDIO -> AssetMediaType.AUDIO
    AssetFilter.VIDEO -> AssetMediaType.VIDEO
    AssetFilter.DOCUMENTS -> AssetMediaType.DOCUMENT
    AssetFilter.FILES -> AssetMediaType.FILE
    else -> null
}

enum class ViewMode { GRID, LIST }

data class AssetBrowserUiState(
    val assets: List<AssetEntry> = emptyList(),
    val selectedFilter: AssetFilter = AssetFilter.ALL,
    val searchQuery: String = "",
    val sortOrder: AssetSortOrder = AssetSortOrder.BY_DATE_ADDED,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val totalCount: Long = 0,
    val viewMode: ViewMode = ViewMode.GRID,
    val availableTags: List<String> = emptyList(),
    // Non-null when the action menu is shown for an asset.
    val actionMenuAsset: AssetEntry? = null,
    // Keyset cursors — all null means start from the first page.
    // BY_DATE_ADDED uses cursorMs; BY_NAME uses cursorName + cursorUuid;
    // BY_SIZE uses cursorSize + cursorUuid.
    val cursorMs: Long? = null,
    val cursorName: String? = null,
    val cursorSize: Long? = null,
    val cursorUuid: String? = null,
)
