package dev.stapler.stelekit.ui.library

import androidx.compose.foundation.layout.Arrangement
import arrow.core.Either
import dev.stapler.stelekit.ui.annotate.OpenForAnnotationError
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.components.typedGridItems
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.i18n.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Below this width, or at large font scales, the chip groups collapse behind one "Filters (n)" button (LD-6). */
private const val COMPACT_WIDTH_DP = 600
private const val COMPACT_FONT_SCALE = 1.5f
private const val MIN_CARD_DP = 160
private const val LOAD_MORE_THRESHOLD = 12
private const val COUNT_ANNOUNCE_DEBOUNCE_MS = 400L

/**
 * The merged Library: one place for photos, annotated photos and attachments (ADR-006).
 * Tap an annotated image to open the editor; any other card opens the asset detail.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenAnnotationEditor: (annotationUuid: String) -> Unit,
    onOpenAsset: (assetUuid: String) -> Unit,
    modifier: Modifier = Modifier,
    onboarding: LibraryOnboardingState = LibraryOnboardingState(mode = LibraryOnboardingMode.UPGRADE, tipDismissed = true, noticeDismissed = true),
    onDismissTip: () -> Unit = {},
    onDismissNotice: () -> Unit = {},
    onShowAnnotations: () -> Unit = {},
    onAddPhoto: (() -> Unit)? = null,
    onCopyLink: (String) -> Unit = {},
    /** Finds or creates the annotation for an existing image; null hides the card menu's Annotate action. */
    onAnnotate: (suspend (LibraryItem) -> Either<OpenForAnnotationError, String>)? = null,
    /** Non-null only while the `homeSketch` flag is on and a sketch screen exists (story 6.8). */
    onOpenSketch: ((assetUuid: String) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    var reason by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = viewModel.scrollIndex)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { viewModel.scrollIndex = it }
    }

    // A reason shown for a dimmed chip goes away when the filters change.
    LaunchedEffect(state.filters) { reason = null }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val compact = maxWidth < COMPACT_WIDTH_DP.dp || fontScale >= COMPACT_FONT_SCALE
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                t(I18n.LIBRARY_TITLE),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }.testTag("library-title"),
            )
            OnboardingBanner(onboarding, onDismissTip, onDismissNotice, onShowAnnotations)
            LibraryFilterBar(
                filters = state.filters,
                sort = state.sort,
                availableTags = state.availableTags,
                searchHint = state.searchHint,
                compact = compact,
                onChip = viewModel::toggleChip,
                onSearch = viewModel::setSearch,
                onSort = viewModel::setSort,
                onClear = viewModel::clearFilters,
                onReason = { reason = it },
            )
            StatusRegions(state, reason)
            when {
                state.isLoading && state.items.isEmpty() -> LoadingState()
                state.error != null && state.items.isEmpty() -> ErrorState(state.error.orEmpty(), viewModel::reload)
                state.items.isEmpty() -> EmptyState(state.filters, state.indexing, onAddPhoto, viewModel::clearFilters)
                else -> ItemGrid(state, gridState, viewModel, onOpenAnnotationEditor, onOpenAsset, onCopyLink, onOpenSketch, onAnnotate)
            }
        }
    }
}

@Composable
private fun StatusRegions(state: LibraryState, reason: String?) {
    // Separate polite live regions for the settled count and for reasons; page loads announce nothing.
    val line = countLine(state)
    var announced by remember { mutableStateOf(line) }
    LaunchedEffect(line) {
        delay(COUNT_ANNOUNCE_DEBOUNCE_MS)
        announced = line
    }
    Column {
        Text(
            text = announced,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("library-count").semantics { liveRegion = LiveRegionMode.Polite }.padding(vertical = 4.dp),
        )
        Text(
            text = reason.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("library-reason").semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** "7 items", "1 item", "10,000+ items"; empty until the first count lands. */
fun countLine(state: LibraryState): String {
    val n = state.totalCount ?: return ""
    val capped = n > LibraryReference.COUNT_CAP
    val shown = if (capped) "10,000+" else formatCount(n)
    return if (n == 1L) "1 item" else "$shown items"
}

private fun formatCount(n: Long): String {
    val s = n.toString()
    return s.reversed().chunked(3).joinToString(",").reversed()
}

@Composable
private fun ItemGrid(
    state: LibraryState,
    gridState: LazyGridState,
    viewModel: LibraryViewModel,
    onOpenAnnotationEditor: (String) -> Unit,
    onOpenAsset: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    onOpenSketch: ((String) -> Unit)?,
    onAnnotate: (suspend (LibraryItem) -> Either<OpenForAnnotationError, String>)?,
) {
    var deleteTarget by remember { mutableStateOf<LibraryItem?>(null) }
    Column(Modifier.fillMaxSize()) {
    deleteTarget?.let { target ->
        DeleteDialog(
            onConfirm = { viewModel.deleteItem(target.id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
    state.actionError?.let { message ->
        Row(Modifier.fillMaxWidth().testTag("library-action-error"), verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::dismissActionError) { Text(t(I18n.LIBRARY_DELETE_CANCEL)) }
        }
    }
    LaunchedEffect(gridState, state.items.size, state.hasMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .map { it >= state.items.size - LOAD_MORE_THRESHOLD }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) viewModel.loadMore() }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(MIN_CARD_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.weight(1f).fillMaxWidth().testTag("library-grid"),
    ) {
        typedGridItems(state.items, key = { it.id }) { item ->
            val open: () -> Unit = {
                when (val target = LibraryOpenTarget.of(item, homeSketchEnabled = onOpenSketch != null)) {
                    is LibraryOpenTarget.Editor -> onOpenAnnotationEditor(target.annotationUuid)
                    is LibraryOpenTarget.Sketch -> onOpenSketch?.invoke(target.assetUuid)
                    is LibraryOpenTarget.Detail -> onOpenAsset(target.assetUuid)
                }
            }
            LibraryItemCard(
                item = item,
                onClick = open,
                onLongClick = { onOpenAsset(item.id) },
                missing = item.id in state.missingIds,
                canAnnotate = onAnnotate != null,
                onAction = { action ->
                    when (action) {
                        LibraryAssetAction.Annotate -> onAnnotate?.let { viewModel.annotateItem(item, it, onOpenAnnotationEditor) }
                        LibraryAssetAction.Details -> onOpenAsset(item.id)
                        LibraryAssetAction.CopyLink -> onCopyLink(LibraryItemActions.markdownLink(item))
                        LibraryAssetAction.Delete -> deleteTarget = item
                    }
                },
            )
        }
        if (state.isLoadingMore || state.loadMoreError != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (state.loadMoreError != null) {
                        TextButton(onClick = viewModel::loadMore, modifier = Modifier.testTag("library-load-more-retry")) {
                            Text(t(I18n.LIBRARY_ERROR_RETRY))
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun DeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t(I18n.LIBRARY_DELETE_TITLE)) },
        text = { Text(t(I18n.LIBRARY_DELETE_BODY)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("library-delete-confirm")) { Text(t(I18n.LIBRARY_DELETE_CONFIRM)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t(I18n.LIBRARY_DELETE_CANCEL)) } },
    )
}

@Composable
private fun OnboardingBanner(
    onboarding: LibraryOnboardingState,
    onDismissTip: () -> Unit,
    onDismissNotice: () -> Unit,
    onShowAnnotations: () -> Unit,
) {
    if (!onboarding.showTip && !onboarding.showNotice) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("library-banner"),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(t(if (onboarding.showNotice) I18n.LIBRARY_NOTICE else I18n.LIBRARY_TIP), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = if (onboarding.showNotice) onDismissNotice else onDismissTip) { Text(t(I18n.LIBRARY_NOTICE_DISMISS)) }
                if (onboarding.showNotice) {
                    TextButton(onClick = { onShowAnnotations(); onDismissNotice() }) { Text(t(I18n.LIBRARY_NOTICE_SHOW_ANNOTATED)) }
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(Modifier.fillMaxSize().testTag("library-loading"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().testTag("library-error"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(t(I18n.LIBRARY_ERROR_TITLE), style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp).testTag("library-retry")) { Text(t(I18n.LIBRARY_ERROR_RETRY)) }
    }
}

@Composable
private fun EmptyState(filters: LibraryFilterState, indexing: Boolean, onAddPhoto: (() -> Unit)?, onClear: () -> Unit) {
    val filtered = !filters.isDefault
    Column(
        Modifier.fillMaxSize().testTag("library-empty"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val (titleKey, bodyKey) = when {
            filters.annotated && filters == LibraryFilterState(annotated = true) -> I18n.LIBRARY_EMPTY_FILTERED to I18n.LIBRARY_EMPTY_ANNOTATED
            filtered -> I18n.LIBRARY_EMPTY_FILTERED to null
            indexing -> I18n.LIBRARY_INDEXING to null
            else -> I18n.LIBRARY_EMPTY_TITLE to I18n.LIBRARY_EMPTY_BODY
        }
        Text(t(titleKey), style = MaterialTheme.typography.titleMedium)
        if (bodyKey != null) Text(t(bodyKey), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (filtered) {
            OutlinedButton(onClick = onClear, modifier = Modifier.padding(top = 12.dp)) { Text(t(I18n.LIBRARY_FILTER_CLEAR)) }
        } else if (onAddPhoto != null) {
            Button(onClick = onAddPhoto, modifier = Modifier.padding(top = 12.dp).testTag("library-add")) { Text("Capture photo") }
        }
    }
}
