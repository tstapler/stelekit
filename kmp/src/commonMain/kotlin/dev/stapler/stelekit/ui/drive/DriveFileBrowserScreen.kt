// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.drive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.google.DriveFile

/**
 * Story 7.6: Google Drive file browser screen.
 *
 * Presents a [LazyColumn] of Drive files/folders. Supports:
 * - Folder navigation (tap a folder to enter it; back button to go up)
 * - File import (tap a non-folder file to download it and trigger import)
 * - Offline error surfacing
 *
 * Data is loaded and managed by [DriveFileBrowserViewModel].
 *
 * Navigation:
 * - Back button pops the folder stack (or calls [onNavigateBack] at the root).
 * - File tap calls [onFileSelect] with the selected [DriveFile] for download + import.
 */
@Composable
fun DriveFileBrowserScreen(
    viewModel: DriveFileBrowserViewModel,
    onNavigateBack: () -> Unit,
    onFileSelect: (DriveFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadFiles(folderId = null)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header: back navigation + current folder path
        DriveFileBrowserHeader(
            breadcrumb = state.breadcrumb,
            onNavigateBack = {
                if (state.folderStack.isNotEmpty()) {
                    viewModel.navigateUp()
                } else {
                    onNavigateBack()
                }
            },
            onRefresh = { viewModel.reload() },
        )

        HorizontalDivider()

        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                DriveErrorState(
                    message = state.error ?: "Unknown error",
                    onRetry = { viewModel.reload() },
                )
            }

            state.files.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "This folder is empty.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.files, key = { it.id }) { file ->
                        DriveFileRow(
                            file = file,
                            isDownloading = state.downloadingFileId == file.id,
                            onClick = {
                                if (file.isFolder) {
                                    viewModel.navigateInto(file)
                                } else {
                                    onFileSelect(file)
                                }
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveFileBrowserHeader(
    breadcrumb: String,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Navigate back",
                )
            }
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = null,
                tint = Color(0xFF1565C0),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = breadcrumb,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }
    }
}

@Composable
private fun DriveFileRow(
    file: DriveFile,
    isDownloading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(file.name) },
        supportingContent = file.modifiedTime?.let { time ->
            { Text(time.take(10), style = MaterialTheme.typography.bodySmall) }
        },
        leadingContent = {
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = driveFileIcon(file),
                    contentDescription = if (file.isFolder) "Folder" else "File",
                    tint = if (file.isFolder) Color(0xFFFFA000) else MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !isDownloading, onClick = onClick),
    )
}

@Composable
private fun DriveErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.padding(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.padding(8.dp))
        TextButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}

private fun driveFileIcon(file: DriveFile): ImageVector = when {
    file.isFolder -> Icons.Default.Folder
    file.mimeType.startsWith("image/") -> Icons.Default.Image
    else -> Icons.Default.InsertDriveFile
}
