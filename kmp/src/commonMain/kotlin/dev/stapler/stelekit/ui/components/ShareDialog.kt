// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.export.ExportService
import dev.stapler.stelekit.export.ShareProvider
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.google.DriveUploader
import dev.stapler.stelekit.platform.google.GoogleAuthManager
import dev.stapler.stelekit.ui.AppState
import dev.stapler.stelekit.ui.LocalWindowSizeClass
import dev.stapler.stelekit.ui.ShareScope
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.isMobile
import kotlinx.coroutines.launch

/**
 * Inner content of the share dialog — scope selector, format picker, and destination tiles.
 *
 * All selections update AppState via ViewModel callbacks.
 * Destination actions are dispatched through [shareProvider] or [viewModel.shareToGoogleDocs].
 */
@Composable
fun ShareContent(
    appState: AppState,
    viewModel: StelekitViewModel,
    page: Page?,
    blocks: List<Block>,
    selectedBlockUuids: Set<String>,
    shareProvider: ShareProvider,
    exportService: ExportService,
    driveClient: DriveUploader?,
    googleAuthManager: GoogleAuthManager?,
    isMobile: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var isGoogleAuthenticated by remember { mutableStateOf(false) }
    var googleEmail by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(googleAuthManager) {
        isGoogleAuthenticated = googleAuthManager?.isAuthenticated() == true
        googleEmail = googleAuthManager?.getConnectedEmail()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Title ────────────────────────────────────────────────────────────
        Text(
            text = "Share / Export",
            style = MaterialTheme.typography.titleMedium,
        )

        // ── Scope selector ───────────────────────────────────────────────────
        Text(
            text = "Scope",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Column {
            ShareScope.entries.forEach { scopeOption ->
                // Hide SelectedBlocks when no blocks are selected
                val isDisabled = scopeOption == ShareScope.SelectedBlocks && selectedBlockUuids.isEmpty()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = appState.shareScope == scopeOption,
                        onClick = { viewModel.setShareScope(scopeOption) },
                        enabled = !isDisabled,
                    )
                    Text(
                        text = scopeOption.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDisabled)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else
                            MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        // ── Empty selection warning ──────────────────────────────────────────
        if (appState.shareScope == ShareScope.SelectedBlocks && selectedBlockUuids.isEmpty()) {
            Text(
                text = "No blocks selected. Select blocks in the editor first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ── Format picker ────────────────────────────────────────────────────
        Text(
            text = "Format",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        val formats = listOf(
            "markdown" to "Markdown",
            "plain-text" to "Plain Text",
            "html" to "HTML",
            "json" to "JSON",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            formats.forEach { (formatId, label) ->
                FilterChip(
                    selected = appState.shareFormat == formatId,
                    onClick = { viewModel.setShareFormat(formatId) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        HorizontalDivider()

        // ── Destination tiles ─────────────────────────────────────────────────
        Text(
            text = "Export to",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        val isSelectionEmpty = appState.shareScope == ShareScope.SelectedBlocks && selectedBlockUuids.isEmpty()

        // Clipboard
        OutlinedButton(
            onClick = {
                val p = page ?: return@OutlinedButton
                val targetBlocks = resolveBlocks(appState.shareScope, blocks, selectedBlockUuids)
                scope.launch {
                    exportService.exportToClipboard(p, targetBlocks, appState.shareFormat)
                    onDismiss()
                }
            },
            enabled = page != null && !isSelectionEmpty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Copy to Clipboard")
        }

        // Share via app (mobile only)
        if (isMobile) {
            OutlinedButton(
                onClick = {
                    val p = page ?: return@OutlinedButton
                    val targetBlocks = resolveBlocks(appState.shareScope, blocks, selectedBlockUuids)
                    scope.launch {
                        val result = exportService.exportToString(p, targetBlocks, appState.shareFormat)
                        result.getOrNull()?.let { content ->
                            if (appState.shareFormat == "html") {
                                val plain = exportService.exportToString(p, targetBlocks, "plain-text")
                                    .getOrNull() ?: content
                                shareProvider.shareHtml(content, plain)
                            } else {
                                shareProvider.shareText(content)
                            }
                        }
                        onDismiss()
                    }
                },
                enabled = page != null && !isSelectionEmpty,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share via…")
            }
        }

        // Save to file
        OutlinedButton(
            onClick = {
                val p = page ?: return@OutlinedButton
                val targetBlocks = resolveBlocks(appState.shareScope, blocks, selectedBlockUuids)
                val extension = formatExtension(appState.shareFormat)
                scope.launch {
                    val result = exportService.exportToString(p, targetBlocks, appState.shareFormat)
                    result.getOrNull()?.let { content ->
                        val saved = shareProvider.saveToFile(content, p.name, extension)
                        saved.getOrNull() // success = true (user saved), false (cancelled)
                    }
                    onDismiss()
                }
            },
            enabled = page != null && !isSelectionEmpty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save to File")
        }

        // Google Docs
        if (driveClient != null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!isGoogleAuthenticated) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = googleAuthManager?.authenticate()
                                if (result?.getOrNull() != null) {
                                    isGoogleAuthenticated = true
                                    googleEmail = result.getOrNull()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Connect Google Account")
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            val p = page ?: return@OutlinedButton
                            val targetBlocks = resolveBlocks(appState.shareScope, blocks, selectedBlockUuids)
                            viewModel.shareToGoogleDocs(p, targetBlocks, driveClient, exportService)
                            onDismiss()
                        },
                        enabled = page != null && !isSelectionEmpty && !appState.isExportingToDrive,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (appState.isExportingToDrive) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Export to Google Docs")
                    }
                    googleEmail?.let { email ->
                        Text(
                            text = email,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                        )
                    }
                }
            }
        }

        // Close button
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text("Close")
        }
    }
}

/**
 * Adaptive wrapper that renders as [ModalBottomSheet] on mobile and [AlertDialog] on desktop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareDialog(
    appState: AppState,
    viewModel: StelekitViewModel,
    page: Page?,
    blocks: List<Block>,
    selectedBlockUuids: Set<String>,
    shareProvider: ShareProvider,
    exportService: ExportService,
    driveClient: DriveUploader?,
    googleAuthManager: GoogleAuthManager?,
    onDismiss: () -> Unit,
) {
    if (!appState.shareDialogVisible) return

    val isMobile = LocalWindowSizeClass.current.isMobile

    if (isMobile) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            ShareContent(
                appState = appState,
                viewModel = viewModel,
                page = page,
                blocks = blocks,
                selectedBlockUuids = selectedBlockUuids,
                shareProvider = shareProvider,
                exportService = exportService,
                driveClient = driveClient,
                googleAuthManager = googleAuthManager,
                isMobile = true,
                onDismiss = onDismiss,
            )
            Spacer(Modifier.height(16.dp))
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            modifier = Modifier.width(480.dp),
            text = {
                ShareContent(
                    appState = appState,
                    viewModel = viewModel,
                    page = page,
                    blocks = blocks,
                    selectedBlockUuids = selectedBlockUuids,
                    shareProvider = shareProvider,
                    exportService = exportService,
                    driveClient = driveClient,
                    googleAuthManager = googleAuthManager,
                    isMobile = false,
                    onDismiss = onDismiss,
                )
            },
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun resolveBlocks(
    scope: ShareScope,
    allBlocks: List<Block>,
    selectedUuids: Set<String>,
): List<Block> = when (scope) {
    ShareScope.SelectedBlocks -> allBlocks.filter { it.uuid.value in selectedUuids }
    else -> allBlocks  // CurrentPage, PageAndLinks, JournalRange handled upstream
}

private fun formatExtension(formatId: String): String = when (formatId) {
    "markdown" -> "md"
    "plain-text" -> "txt"
    "html" -> "html"
    "json" -> "json"
    else -> "txt"
}

/** Human-readable display name for [ShareScope] values. */
val ShareScope.displayName: String get() = when (this) {
    ShareScope.CurrentPage -> "Current Page"
    ShareScope.PageAndLinks -> "Page + Linked Pages"
    ShareScope.SelectedBlocks -> "Selected Blocks"
    ShareScope.JournalRange -> "Journal Date Range"
}
