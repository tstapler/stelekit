// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.merge.CopyDirection
import dev.stapler.stelekit.merge.PullIndexState
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.PlatformBackHandler
import dev.stapler.stelekit.ui.components.typedItemsIndexed
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** Everything the screen can ask for; the ViewModel provides it via [CopyPagesViewModel.actions]. */
data class CopyPagesActions(
    val onSearchChanged: (String) -> Unit = {},
    val onTogglePages: () -> Unit = {},
    val onToggleJournals: () -> Unit = {},
    val onDateRange: (LocalDate?, LocalDate?) -> Unit = { _, _ -> },
    val onNamespace: (String) -> Unit = {},
    val onTag: (String) -> Unit = {},
    val onClearFilters: () -> Unit = {},
    val onToggleRow: (PageUuid) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onSelectAllMatching: () -> Unit = {},
    val onSelectAllInGraph: () -> Unit = {},
    val onConfirmAllInGraph: () -> Unit = {},
    val onDismissAllInGraph: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onRequestClose: () -> Unit = {},
    val onConfirmDiscard: () -> Unit = {},
    val onKeepEditing: () -> Unit = {},
    val onChooseDestination: (GraphId) -> Unit = {},
    val onRetryProbe: (GraphId) -> Unit = {},
    val onDestinationAction: (GraphId, DestinationActionKind) -> Unit = { _, _ -> },
    val onAddGraph: () -> Unit = {},
    val onIncludeLinked: (Boolean) -> Unit = {},
    val onIncludeAssets: (Boolean) -> Unit = {},
    val onReview: () -> Unit = {},
    val onStopReading: () -> Unit = {},
    val onRetryIndex: () -> Unit = {},
    val onChangeSource: () -> Unit = {},
)

fun CopyPagesViewModel.actions() = CopyPagesActions(
    onSearchChanged = ::onSearchTextChanged,
    onTogglePages = ::toggleShowPages,
    onToggleJournals = ::toggleShowJournals,
    onDateRange = ::setDateRange,
    onNamespace = ::setNamespace,
    onTag = ::setTag,
    onClearFilters = ::clearFilters,
    onToggleRow = ::toggleRow,
    onLoadMore = ::loadMore,
    onRetryLoad = ::retryLoad,
    onSelectAllMatching = ::selectAllMatching,
    onSelectAllInGraph = ::requestSelectAllInGraph,
    onConfirmAllInGraph = ::confirmSelectAllInGraph,
    onDismissAllInGraph = ::dismissSelectAllInGraph,
    onClearSelection = ::clearSelection,
    onRequestClose = ::requestClose,
    onConfirmDiscard = ::confirmDiscard,
    onKeepEditing = ::keepEditing,
    onChooseDestination = ::chooseDestination,
    onRetryProbe = ::retryProbe,
    onDestinationAction = ::onDestinationAction,
    onAddGraph = ::addGraph,
    onIncludeLinked = ::setIncludeLinked,
    onIncludeAssets = ::setIncludeAssets,
    onReview = ::review,
    onStopReading = ::clearSource,
    onRetryIndex = ::retryIndex,
    onChangeSource = ::clearSource,
)

/**
 * Page picker (design/ux.md S2) with the destination chooser (S3) below it.
 *
 * Keyboard: Tab order is search, chips, select-all, list, link options, destination, Review.
 * In the list, Up/Down (layout-aware) move focus, Space toggles the focused row, Home/End jump to
 * the first/last loaded row, Ctrl/Cmd+A selects everything matching (only while the list has
 * focus, so it never steals the browser's or the search field's select-all), and Enter does
 * nothing: it only activates a focused button. Esc / Back closes at once with nothing selected,
 * otherwise asks "Discard selection of N pages?". Dialogs are separate windows, so Esc inside one
 * never reaches the screen. Focus rings are Material's defaults.
 */
@Composable
fun CopyPagesScreen(
    viewModel: CopyPagesViewModel,
    onEvent: (CopyPagesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.events.collect(onEvent) }
    CopyPagesContent(state, remember(viewModel) { viewModel.actions() }, modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CopyPagesContent(state: CopyPagesState, actions: CopyPagesActions, modifier: Modifier = Modifier) {
    PlatformBackHandler(enabled = !state.discardPrompt) { actions.onRequestClose() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope() // transient UI work only (Home/End scroll)
    val rowFocus = remember { FocusRequesterPool() }
    var listFocused by remember { mutableStateOf(false) }
    var focusAfterScroll by remember { mutableStateOf<Int?>(null) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(focusAfterScroll, state.rows.size) {
        val target = focusAfterScroll ?: return@LaunchedEffect
        rowFocus.get(target)?.let { runCatching { it.requestFocus() } }
        focusAfterScroll = null
    }
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }

    // Load the next 100 rows when the last loaded row is near the viewport.
    val rowCount by androidx.compose.runtime.rememberUpdatedState(state.rows.size)
    val nearEnd by remember { derivedNearEnd(listState) { rowCount } }
    LaunchedEffect(nearEnd, state.rows.size) { if (nearEnd) actions.onLoadMore() }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    actions.onRequestClose()
                    true
                } else {
                    false
                }
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Header(state, actions)
            if (state.isPull && state.destinationId == null) {
                PullSourceChooser(state, actions)
                return@Column
            }
            if (state.isPull) PullIndexStatus(state, actions)
            OutlinedTextField(
                value = state.searchText,
                onValueChange = actions.onSearchChanged,
                label = { Text("Search pages...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(searchFocus),
            )
            FilterRow(state, actions)
            Text(
                text = state.countLine,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state.stillReading) {
                Text("Still reading... results so far", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionActions(state, actions)
            Column(Modifier.weight(1f).fillMaxWidth()) {
                PageList(
                    state = state,
                    actions = actions,
                    listState = listState,
                    rowFocus = rowFocus,
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged { listFocused = it.hasFocus }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                (e.isCtrlPressed || e.isMetaPressed) && e.key == Key.A && listFocused -> {
                                    actions.onSelectAllMatching()
                                    true
                                }
                                e.key == Key.MoveHome && state.rows.isNotEmpty() -> {
                                    scope.launch { listState.scrollToItem(0) }
                                    focusAfterScroll = 0
                                    true
                                }
                                e.key == Key.MoveEnd && state.rows.isNotEmpty() -> {
                                    val last = state.rows.lastIndex
                                    scope.launch { listState.scrollToItem(last) }
                                    focusAfterScroll = last
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
            if (state.gate2LinkedPages || state.isPull) LinkedOptions(state, actions)
            if (state.isPull) {
                TextButton(onClick = actions.onChangeSource, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change source") }
            } else {
                DestinationChooser(state, actions)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = actions.onRequestClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = actions.onReview, enabled = state.canReview, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.review == ReviewState.Planning) "Reviewing..." else "Review copy")
                }
            }
            state.reviewHelper?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
            }
            (state.review as? ReviewState.Failed)?.let {
                Text(
                    "Couldn't review the copy: ${it.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    if (state.discardPrompt) {
        AlertDialog(
            onDismissRequest = actions.onKeepEditing,
            title = { Text("Discard selection of ${state.selectedCount} ${if (state.selectedCount == 1) "page" else "pages"}?") },
            text = { Text("Nothing has been copied.") },
            confirmButton = { TextButton(onClick = actions.onConfirmDiscard, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard") } },
            dismissButton = { TextButton(onClick = actions.onKeepEditing, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep editing") } },
        )
    }
    state.confirmAllPages?.let { n ->
        AlertDialog(
            onDismissRequest = actions.onDismissAllInGraph,
            title = { Text("Select all ${groupThousands(n)} pages?") },
            text = { Text("This selects every page in the graph, not just the ones matching your search.") },
            confirmButton = { TextButton(onClick = actions.onConfirmAllInGraph, modifier = Modifier.heightIn(min = 48.dp)) { Text("Select all") } },
            dismissButton = { TextButton(onClick = actions.onDismissAllInGraph, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
    }
}

private fun derivedNearEnd(listState: androidx.compose.foundation.lazy.LazyListState, rowCount: () -> Int) =
    androidx.compose.runtime.derivedStateOf {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        val n = rowCount()
        n > 0 && last >= n - NEAR_END
    }

private const val NEAR_END = 10

/** Visible rows' focus targets by list index, for Home/End roving focus. Not observed state. */
class FocusRequesterPool {
    private val byIndex = HashMap<Int, FocusRequester>()
    fun put(index: Int, requester: FocusRequester) { byIndex[index] = requester }
    fun get(index: Int): FocusRequester? = byIndex[index]
}

@Composable
private fun Header(state: CopyPagesState, actions: CopyPagesActions) = Column {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onRequestClose, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
        }
        Text(
            text = when {
                state.direction == CopyDirection.Push -> "Copy pages from \"${state.activeGraphName}\""
                state.chosenDestination != null -> "Copy pages from \"${state.chosenDestination?.name}\""
                else -> "Copy pages from..."
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
    if (state.listLoad == ListLoad.Initial && state.loadError == null && !state.isPull) {
        Text(
            "Loading pages...",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(state: CopyPagesState, actions: CopyPagesActions) = Column {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = state.filters.showPages, onClick = actions.onTogglePages, label = { Text("Pages") })
        FilterChip(selected = state.filters.showJournals, onClick = actions.onToggleJournals, label = { Text("Journals") })
        DateRangeChip(state.filters.dateFrom, state.filters.dateTo, actions.onDateRange)
        TextFilterChip("Namespace", state.filters.namespace, actions.onNamespace)
        TextFilterChip("Tag", state.filters.tag, actions.onTag, enabled = !state.isPull)
    }
    if (state.isPull) {
        Text(
            "Tag, property and backlink filters: ${CopyPagesState.NOT_AVAILABLE_PULL}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DateRangeChip(from: LocalDate?, to: LocalDate?, onChange: (LocalDate?, LocalDate?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var fromText by remember(from) { mutableStateOf(from?.toString().orEmpty()) }
    var toText by remember(to) { mutableStateOf(to?.toString().orEmpty()) }
    val active = from != null || to != null
    Column {
        FilterChip(
            selected = active,
            onClick = { open = true },
            label = { Text(if (active) "${from ?: "..."} to ${to ?: "..."}" else "Date range") },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(fromText, { fromText = it }, label = { Text("From (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(toText, { toText = it }, label = { Text("To (YYYY-MM-DD)") }, singleLine = true)
                val parsedFrom = fromText.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val parsedTo = toText.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val valid = (fromText.isBlank() || parsedFrom != null) && (toText.isBlank() || parsedTo != null)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { fromText = ""; toText = ""; onChange(null, null); open = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Any date") }
                    Button(
                        onClick = { onChange(parsedFrom, parsedTo); open = false },
                        enabled = valid,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Apply") }
                }
                if (!valid) Text("Use the form 2026-10-31", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TextFilterChip(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }
    Column {
        FilterChip(selected = value.isNotBlank(), enabled = enabled, onClick = { open = true }, label = { Text(if (value.isBlank()) label else "$label: $value") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { draft = it }, label = { Text(label) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { draft = ""; onChange(""); open = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
                    Button(onClick = { onChange(draft); open = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun SelectionActions(state: CopyPagesState, actions: CopyPagesActions) {
    var menu by remember { mutableStateOf(false) }
    val total = state.totalMatching
    FlowRowSpaced {
        TextButton(
            onClick = actions.onSelectAllMatching,
            enabled = total != null && total > 0 && !state.selecting,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(if (state.selecting) "Selecting..." else "Select all ${total?.let(::groupThousands) ?: ""} matching".replace("  ", " "))
        }
        TextButton(onClick = actions.onClearSelection, enabled = state.selectedCount > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
        Column {
            TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("All pages in graph") }, onClick = { menu = false; actions.onSelectAllInGraph() })
                if (!state.filters.isDefault || state.searchText.isNotBlank()) {
                    DropdownMenuItem(text = { Text("Clear filters") }, onClick = { menu = false; actions.onClearFilters() })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowSpaced(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }
}

@Composable
private fun PageList(
    state: CopyPagesState,
    actions: CopyPagesActions,
    listState: androidx.compose.foundation.lazy.LazyListState,
    rowFocus: FocusRequesterPool,
    modifier: Modifier = Modifier,
) {
    when {
        state.listLoad == ListLoad.Failed && state.rows.isEmpty() || state.loadError != null && state.listLoad == ListLoad.Failed -> {
            Column(modifier.padding(8.dp)) {
                Text(
                    "Couldn't read pages from ${state.activeGraphName}.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = actions.onRetryLoad, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
                    TextButton(onClick = actions.onRequestClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                }
            }
        }
        state.listLoad == ListLoad.Initial || state.stillReading && state.rows.isEmpty() ->
            Column(modifier) { repeat(SKELETON_ROWS) { PageSelectionSkeletonRow() } }
        state.indexState is PullIndexState.Failed && state.rows.isEmpty() -> Unit
        state.rows.isEmpty() -> Column(modifier.padding(8.dp)) {
            val filtered = !state.filters.isDefault || state.searchText.isNotBlank()
            Text(if (filtered) "No pages match." else "This graph has no pages to copy.")
            if (filtered) TextButton(onClick = actions.onClearFilters, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear filters") }
        }
        else -> LazyColumn(
            state = listState,
            modifier = modifier.alpha(if (state.listLoad == ListLoad.Updating) DIMMED else 1f),
        ) {
            typedItemsIndexed(state.rows, key = { _, row -> row.uuid.value }) { index, row ->
                val requester = remember(row.uuid) { FocusRequester() }
                rowFocus.put(index, requester)
                PageSelectionRow(
                    row = row,
                    checked = row.uuid in state.picked,
                    onToggle = { actions.onToggleRow(row.uuid) },
                    focusRequester = requester,
                )
            }
            if (state.listLoad == ListLoad.AppendingMore) {
                item {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Loading more pages...")
                    }
                }
            }
        }
    }
}

private const val SKELETON_ROWS = 5
private val DESTINATION_MAX_HEIGHT = 220.dp
private const val DIMMED = 0.5f

@Composable
private fun LinkedOptions(state: CopyPagesState, actions: CopyPagesActions) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(
                    selected = state.includeLinked,
                    enabled = !state.isPull,
                    role = Role.Checkbox,
                    onClick = { actions.onIncludeLinked(!state.includeLinked) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.includeLinked, onCheckedChange = null, enabled = !state.isPull)
            Spacer(Modifier.size(8.dp))
            Text("Include linked pages  (adds ${state.linkedDelta ?: 0} pages)")
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 24.dp)
                .selectable(
                    selected = state.includeAssets,
                    enabled = state.includeLinked && !state.isPull,
                    role = Role.Checkbox,
                    onClick = { actions.onIncludeAssets(!state.includeAssets) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.includeAssets, onCheckedChange = null, enabled = state.includeLinked && !state.isPull)
            Spacer(Modifier.size(8.dp))
            Text("Include their assets")
        }
        if (state.isPull) {
            Text(
                CopyPagesState.NOT_AVAILABLE_PULL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DestinationChooser(state: CopyPagesState, actions: CopyPagesActions) {
    val label = if (state.direction == CopyDirection.Push) "Copy to:" else "Copy from:"
    Column(Modifier.fillMaxWidth().heightIn(max = DESTINATION_MAX_HEIGHT).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (state.noOtherGraph) {
            Text("You need a second graph to copy pages.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.onAddGraph, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add a graph") }
                TextButton(onClick = actions.onRequestClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
            return@Column
        }
        state.destinations.forEach { DestinationRowView(it, state.destinationId == it.graphId, actions) }
    }
}

@Composable
internal fun DestinationRowView(row: DestinationRow, chosen: Boolean, actions: CopyPagesActions) {
    val status = row.status
    val selectable = status == DestinationStatus.Available
    val detail = when (status) {
        DestinationStatus.Checking -> "Checking..."
        DestinationStatus.Available -> null
        is DestinationStatus.Disabled -> status.text
        is DestinationStatus.CouldntCheck -> status.text
    }
    Column {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = chosen, enabled = selectable, role = Role.RadioButton, onClick = { actions.onChooseDestination(row.graphId) })
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(row.name, detail).joinToString(". ")
                if (status == DestinationStatus.Checking) liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = chosen, onClick = null, enabled = selectable)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (status == DestinationStatus.Checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    when (status) {
        is DestinationStatus.CouldntCheck ->
            TextButton(onClick = { actions.onRetryProbe(row.graphId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
        is DestinationStatus.Disabled -> status.action?.let { a ->
            TextButton(onClick = { actions.onDestinationAction(row.graphId, a.kind) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(a.label) }
        }
        else -> Unit
    }
    }
}
