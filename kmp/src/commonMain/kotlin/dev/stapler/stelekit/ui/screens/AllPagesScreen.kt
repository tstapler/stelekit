// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.Page
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val BACKLINKS_COL_WIDTH: Dp = 90.dp
private val MODIFIED_COL_WIDTH: Dp = 90.dp
private val CREATED_COL_WIDTH: Dp = 80.dp

@Composable
fun AllPagesScreen(
    viewModel: AllPagesViewModel,
    onPageClick: (Page) -> Unit,
    onBulkDelete: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val pages by viewModel.pages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedUuids by viewModel.selectedUuids.collectAsState()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsState()
    val sortColumn by viewModel.sortColumn.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val filterQuery by viewModel.filterQuery.collectAsState()
    val pageTypeFilter by viewModel.pageTypeFilter.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Pages") },
            text = { Text("Delete ${selectedUuids.size} selected page(s)? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onBulkDelete(selectedUuids.toList())
                        viewModel.clearSelection()
                        showDeleteDialog = false
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Title
        Text(
            text = "All Pages",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        // Filter text field
        OutlinedTextField(
            value = filterQuery,
            onValueChange = { viewModel.onFilterChange(it) },
            label = { Text("Filter by name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Page type filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PageTypeFilter.entries.forEach { filter ->
                FilterChip(
                    selected = pageTypeFilter == filter,
                    onClick = { viewModel.setPageTypeFilter(filter) },
                    label = {
                        Text(
                            when (filter) {
                                PageTypeFilter.ALL -> "All"
                                PageTypeFilter.JOURNALS -> "Journals"
                                PageTypeFilter.PAGES -> "Pages"
                            }
                        )
                    }
                )
            }
        }

        // Column header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isInSelectionMode) {
                Checkbox(
                    checked = selectedUuids.size == pages.size && pages.isNotEmpty(),
                    onCheckedChange = { checked ->
                        if (checked) viewModel.selectAll() else viewModel.clearSelection()
                    },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            SortHeader(
                label = "Name",
                column = SortColumn.NAME,
                currentColumn = sortColumn,
                ascending = sortAscending,
                onClick = { viewModel.toggleSort(SortColumn.NAME) },
                modifier = Modifier.weight(2f)
            )
            SortHeader(
                label = "Backlinks",
                column = SortColumn.BACKLINKS,
                currentColumn = sortColumn,
                ascending = sortAscending,
                onClick = { viewModel.toggleSort(SortColumn.BACKLINKS) },
                modifier = Modifier.width(BACKLINKS_COL_WIDTH)
            )
            SortHeader(
                label = "Modified",
                column = SortColumn.LAST_MODIFIED,
                currentColumn = sortColumn,
                ascending = sortAscending,
                onClick = { viewModel.toggleSort(SortColumn.LAST_MODIFIED) },
                modifier = Modifier.width(MODIFIED_COL_WIDTH)
            )
            SortHeader(
                label = "Created",
                column = SortColumn.CREATED,
                currentColumn = sortColumn,
                ascending = sortAscending,
                onClick = { viewModel.toggleSort(SortColumn.CREATED) },
                modifier = Modifier.width(CREATED_COL_WIDTH)
            )
        }

        HorizontalDivider()

        // Content
        when {
            isLoading && pages.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            pages.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No pages found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(items = pages, key = { it.page.uuid }) { row ->
                        PageRowItem(
                            row = row,
                            isSelected = row.page.uuid in selectedUuids,
                            isInSelectionMode = isInSelectionMode,
                            onToggleSelection = { viewModel.toggleSelection(row.page.uuid) },
                            onClick = {
                                if (isInSelectionMode) {
                                    viewModel.toggleSelection(row.page.uuid)
                                } else {
                                    onPageClick(row.page)
                                }
                            },
                            onLongClick = {
                                viewModel.toggleSelection(row.page.uuid)
                            }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }

        // Bottom action bar shown in selection mode
        if (isInSelectionMode) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${selectedUuids.size} selected",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.selectAll() }) { Text("Select All") }
                TextButton(onClick = { viewModel.clearSelection() }) { Text("Deselect All") }
                Button(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun SortHeader(
    label: String,
    column: SortColumn,
    currentColumn: SortColumn,
    ascending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (currentColumn == column) FontWeight.Bold else FontWeight.Normal,
            color = if (currentColumn == column)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (currentColumn == column) {
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageRowItem(
    row: PageRow,
    isSelected: Boolean,
    isInSelectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isInSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = row.page.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(2f),
            maxLines = 1
        )
        Text(
            text = row.backlinkCount.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(BACKLINKS_COL_WIDTH)
        )
        Text(
            text = formatInstantShort(row.page.updatedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(MODIFIED_COL_WIDTH),
            maxLines = 1
        )
        Text(
            text = formatInstantShort(row.page.createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(CREATED_COL_WIDTH),
            maxLines = 1
        )
    }
}

private fun formatInstantShort(instant: kotlin.time.Instant): String {
    val date = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "${date.year}-${date.monthNumber.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"
}
