package dev.stapler.stelekit.ui.assets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetSortOrder
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.platform.rememberPlatformFileOpener
import dev.stapler.stelekit.ui.rememberClipboardProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetBrowserScreen(
    viewModel: AssetBrowserViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAsset: (AssetUuid) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val clipboardProvider = rememberClipboardProvider(clipboardManager)
    val fileOpener = rememberPlatformFileOpener()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assets") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    SortDropdownButton(
                        current = uiState.sortOrder,
                        onSelect = { viewModel.setSort(it) },
                    )
                    IconButton(onClick = {
                        viewModel.setViewMode(
                            if (uiState.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID,
                        )
                    }) {
                        Icon(
                            if (uiState.viewMode == ViewMode.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle view",
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            AssetBrowserSearchBar(
                query = uiState.searchQuery,
                onQueryChange = { viewModel.setSearch(it) },
            )

            ScrollableFilterChipRow(
                selectedFilter = uiState.selectedFilter,
                availableTags = uiState.availableTags,
                onFilterSelect = { viewModel.setFilter(it) },
                onNewGroup = { showNewGroupDialog = true },
            )

            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            uiState.error?.let { err ->
                Text(
                    text = "Error: $err",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }

            if (uiState.assets.isEmpty() && !uiState.isLoading) {
                AssetBrowserEmptyState(selectedFilter = uiState.selectedFilter)
            } else {
                when (uiState.viewMode) {
                    ViewMode.GRID -> {
                        val gridState = rememberLazyGridState()
                        LaunchedEffect(gridState) {
                            snapshotFlow { gridState.layoutInfo }
                                .collect { layout ->
                                    val total = layout.totalItemsCount
                                    val visible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
                                    if (total > 0 && visible >= total - 5) {
                                        viewModel.loadMore()
                                    }
                                }
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(120.dp),
                            modifier = Modifier.fillMaxSize(),
                            state = gridState,
                        ) {
                            items(uiState.assets) { asset ->
                                AssetItemCard(
                                    asset = asset,
                                    viewMode = ViewMode.GRID,
                                    onClick = { onNavigateToAsset(asset.uuid) },
                                    onLongPress = { viewModel.showActionMenu(asset) },
                                )
                            }
                            if (uiState.isLoadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                    ViewMode.LIST -> {
                        val listState = rememberLazyListState()
                        LaunchedEffect(listState) {
                            snapshotFlow { listState.layoutInfo }
                                .collect { layout ->
                                    val total = layout.totalItemsCount
                                    val visible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
                                    if (total > 0 && visible >= total - 5) {
                                        viewModel.loadMore()
                                    }
                                }
                        }
                        LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                            items(uiState.assets) { asset ->
                                AssetItemCard(
                                    asset = asset,
                                    viewMode = ViewMode.LIST,
                                    onClick = { onNavigateToAsset(asset.uuid) },
                                    onLongPress = { viewModel.showActionMenu(asset) },
                                )
                                HorizontalDivider()
                            }
                            if (uiState.isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewGroupDialog) {
        AlertDialog(
            onDismissRequest = { showNewGroupDialog = false },
            title = { Text("New Group") },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("Group name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newGroupName.isNotBlank()) {
                        viewModel.createGroup(newGroupName)
                        showNewGroupDialog = false
                        newGroupName = ""
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewGroupDialog = false }) { Text("Cancel") }
            },
        )
    }

    val actionAsset = uiState.actionMenuAsset
    if (actionAsset != null) {
        AssetActionMenu(
            asset = actionAsset,
            expanded = true,
            onDismiss = { viewModel.dismissActionMenu() },
            onAction = { action ->
                when (action) {
                    AssetAction.Delete -> viewModel.deleteAsset(actionAsset)
                    AssetAction.CopyLink -> viewModel.copyMarkdownLink(actionAsset, clipboardProvider)
                    AssetAction.Open -> {
                        viewModel.dismissActionMenu()
                        scope.launch {
                            fileOpener.openFile(actionAsset.filePath, mimeTypeFor(actionAsset.mediaType))
                        }
                    }
                    else -> viewModel.dismissActionMenu()
                }
            },
        )
    }
}

private fun mimeTypeFor(mediaType: AssetMediaType): String = when (mediaType) {
    AssetMediaType.IMAGE -> "image/*"
    AssetMediaType.PDF -> "application/pdf"
    AssetMediaType.AUDIO -> "audio/*"
    AssetMediaType.VIDEO -> "video/*"
    else -> "*/*"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScrollableFilterChipRow(
    selectedFilter: AssetFilter,
    availableTags: List<String>,
    onFilterSelect: (AssetFilter) -> Unit,
    onNewGroup: () -> Unit,
) {
    val filters = AssetFilter.all + availableTags.map { AssetFilter.TAG(it) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelect(filter) },
                label = { Text(filter.displayName()) },
            )
        }
        AssistChip(
            onClick = onNewGroup,
            label = { Text("+ Group") },
        )
    }
}

@Composable
private fun SortDropdownButton(
    current: AssetSortOrder,
    onSelect: (AssetSortOrder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Sort assets",
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AssetSortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.displayName()) },
                    onClick = {
                        onSelect(order)
                        expanded = false
                    },
                    leadingIcon = {
                        if (order == current) {
                            RadioButton(selected = true, onClick = null)
                        }
                    },
                )
            }
        }
    }
}

private fun AssetSortOrder.displayName(): String = when (this) {
    AssetSortOrder.BY_DATE_ADDED -> "Date added"
    AssetSortOrder.BY_NAME -> "Name"
    AssetSortOrder.BY_SIZE -> "Size"
}
