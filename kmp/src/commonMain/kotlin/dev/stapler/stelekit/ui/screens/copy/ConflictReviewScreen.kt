// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import dev.stapler.stelekit.ui.components.typedItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.stapler.stelekit.ui.PlatformBackHandler
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.withTimeoutOrNull

internal object ConflictReviewText {
    const val TITLE = "Review conflicts"
    const val LOADING = "Loading conflicts..."
    const val EMPTY = "No conflicts to review."
    const val LOAD_FAILED = "Could not load conflicts."
    const val MARK_RESOLVED = "Mark resolved"
    const val REMOVE = "Remove this block"
    const val OPEN_PAGE = "Open page"
    const val MARK_RESOLVED_INSTEAD = "Mark resolved instead"
    const val CANCEL = "Cancel"
    const val CLOSE = "Close"
    const val RETRY = "Retry"
    const val UNDO = "Undo"
    const val SHOW_MORE = "Show more"
    const val UNDO_VISIBLE_MS = 10_000L

    fun remaining(count: Int, hasMore: Boolean): String = when {
        hasMore -> "$count+ conflicts to review"
        count == 1 -> "1 conflict to review"
        else -> "$count conflicts to review"
    }

    fun rowDescription(row: ConflictRow): String = buildString {
        append("Conflict on page ${row.pageName}: ")
        append(row.originalText?.let { "original '$it'" } ?: "original not found")
        append(", copied '${row.copiedText}'")
        if (row.sourceGraph.isNotBlank()) append(" from ${row.sourceGraph}")
    }
}

/** Stateful host: collects [viewModel] and shows the 10 s Undo snackbar after a removal. */
@Composable
fun ConflictReviewScreen(
    viewModel: ConflictReviewViewModel,
    onOpenPage: (PageUuid) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.undo) {
        val offer = state.undo ?: return@LaunchedEffect
        val result = withTimeoutOrNull(ConflictReviewText.UNDO_VISIBLE_MS) {
            snackbar.showSnackbar(offer.message, ConflictReviewText.UNDO, duration = SnackbarDuration.Indefinite)
        }
        snackbar.currentSnackbarData?.dismiss()
        if (result == SnackbarResult.ActionPerformed) viewModel.undoRemove() else viewModel.dismissUndo()
    }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        ConflictReviewContent(
            state = state,
            onResolve = viewModel::markResolved,
            onRequestRemove = viewModel::requestRemove,
            onConfirmRemove = viewModel::confirmRemove,
            onCancelRemove = viewModel::cancelRemove,
            onOpenPage = onOpenPage,
            onRetryLoad = viewModel::load,
            onRetryAction = viewModel::retryAction,
            onDismissActionError = viewModel::dismissActionError,
            onLoadMore = viewModel::loadMore,
            onClose = onClose,
            modifier = Modifier.padding(padding),
        )
    }
}

/** Stateless body, driven entirely by [state]; Robolectric tests exercise this directly. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConflictReviewContent(
    state: ConflictReviewState,
    onResolve: (String) -> Unit,
    onRequestRemove: (String) -> Unit,
    onConfirmRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onOpenPage: (PageUuid) -> Unit,
    onRetryLoad: () -> Unit,
    onRetryAction: () -> Unit,
    onDismissActionError: () -> Unit,
    onLoadMore: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlatformBackHandler(enabled = state.confirmRemove == null) { onClose() }
    val listState = rememberLazyListState()
    val emptyFocus = remember { FocusRequester() }
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    val rootFocus = remember { FocusRequester() }
    // Esc needs a focused descendant to reach onPreviewKeyEvent; the root holds focus until a row takes it.
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    LaunchedEffect(state.focusToken) {
        if (state.focusToken == 0) return@LaunchedEffect
        try {
            if (state.rows.isEmpty()) {
                withFrameNanos { }
                emptyFocus.requestFocus()
            } else {
                val idx = state.focusIndex.coerceIn(0, state.rows.lastIndex)
                listState.scrollToItem(idx)
                withFrameNanos { }
                rowFocus[state.rows[idx].key]?.requestFocus()
            }
        } catch (_: IllegalStateException) {
            // Target not attached yet (list still loading more); skip rather than crash.
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && state.confirmRemove == null) {
                    onClose()
                    true
                } else {
                    false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            ConflictReviewText.TITLE,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
        )
        state.actionError?.let { err ->
            Banner(err.message, ConflictReviewText.RETRY, onRetryAction, "Dismiss", onDismissActionError)
        }
        when {
            state.loading -> {
                Text(
                    ConflictReviewText.LOADING,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                repeat(3) { SkeletonRow() }
            }
            state.loadError != null -> Banner(
                "${ConflictReviewText.LOAD_FAILED} ${state.loadError}",
                ConflictReviewText.RETRY, onRetryLoad, ConflictReviewText.CLOSE, onClose,
            )
            state.rows.isEmpty() -> {
                Text(
                    ConflictReviewText.EMPTY,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.focusRequester(emptyFocus).focusable().semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(ConflictReviewText.CLOSE) }
            }
            else -> {
                Text(
                    ConflictReviewText.remaining(state.rows.size, state.hasMore),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                    typedItems(state.rows, key = { it.key }) { row ->
                        val requester = rowFocus.getOrPut(row.key) { FocusRequester() }
                        ConflictRowItem(
                            row = row,
                            focusRequester = requester,
                            onResolve = { onResolve(row.key) },
                            onRemove = { onRequestRemove(row.key) },
                            onOpen = { onOpenPage(row.pageUuid) },
                        )
                    }
                    if (state.hasMore) {
                        item(key = "show-more") {
                            OutlinedButton(onClick = onLoadMore, modifier = Modifier.heightIn(min = 48.dp)) { Text(ConflictReviewText.SHOW_MORE) }
                        }
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(ConflictReviewText.CLOSE) }
            }
        }
    }

    state.confirmRemove?.let { row ->
        AlertDialog(
            onDismissRequest = onCancelRemove,
            title = { Text(ConflictReviewText.REMOVE) },
            text = { Text(ConflictReviewViewModel.removeConfirmation(row.sourceGraph)) },
            confirmButton = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancelRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text(ConflictReviewText.CANCEL) }
                    TextButton(
                        onClick = {
                            onCancelRemove()
                            onResolve(row.key)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(ConflictReviewText.MARK_RESOLVED_INSTEAD) }
                    TextButton(onClick = onConfirmRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text(ConflictReviewText.REMOVE) }
                }
            },
        )
    }
}

@Composable
private fun Banner(message: String, primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPrimary, modifier = Modifier.heightIn(min = 48.dp)) { Text(primary) }
                TextButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 48.dp)) { Text(secondary) }
            }
        }
    }
}

@Composable
private fun SkeletonRow() {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), MaterialTheme.shapes.medium)
            .semantics { contentDescription = "Loading placeholder" },
    ) {}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictRowItem(
    row: ConflictRow,
    focusRequester: FocusRequester,
    onResolve: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val page = row.pageName
    Card(
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = "${ConflictReviewText.OPEN_PAGE} $page", onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = ConflictReviewText.rowDescription(row)
                customActions = listOf(
                    CustomAccessibilityAction("${ConflictReviewText.MARK_RESOLVED} on page $page") { onResolve(); true },
                    CustomAccessibilityAction("${ConflictReviewText.REMOVE} on page $page") { onRemove(); true },
                    CustomAccessibilityAction("${ConflictReviewText.OPEN_PAGE} $page") { onOpen(); true },
                )
            },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Conflict", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            Text(page, style = MaterialTheme.typography.titleMedium)
            Text("Original: ${row.originalText ?: "not found"}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Copied${if (row.sourceGraph.isNotBlank()) " from ${row.sourceGraph}" else ""}: ${row.copiedText}",
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onResolve, modifier = Modifier.heightIn(min = 48.dp)) { Text(ConflictReviewText.MARK_RESOLVED) }
                OutlinedButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text(ConflictReviewText.REMOVE) }
                OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text(ConflictReviewText.OPEN_PAGE) }
            }
        }
    }
}
