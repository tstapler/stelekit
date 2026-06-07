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
import kotlinx.datetime.LocalDate

// ── Format ID constants ──────────────────────────────────────────────────────

private const val FORMAT_MARKDOWN = "markdown"
private const val FORMAT_PLAIN_TEXT = "plain-text"
private const val FORMAT_HTML = "html"
private const val FORMAT_JSON = "json"

/**
 * Inner content of the share dialog — scope selector, format picker, and destination tiles.
 *
 * All selections update AppState via ViewModel callbacks.
 * Auth state is owned by AppState ([AppState.shareIsGoogleAuthenticated]) and refreshed
 * when the dialog opens via [StelekitViewModel.refreshShareGoogleAuthState].
 */
@Composable
fun ShareContent(
    appState: AppState,
    viewModel: StelekitViewModel,
    page: Page?,
    blocks: List<Block>,
    selectedBlockUuids: Set<String>,
    shareProvider: ShareProvider,
    googleAuthManager: GoogleAuthManager?,
    driveClient: DriveUploader?,
    isMobile: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // Journal date range text inputs (shown only for JournalRange scope)
    var journalFromText by remember { mutableStateOf("") }
    var journalToText by remember { mutableStateOf("") }
    var dateError by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    val isSelectionEmpty = appState.shareScope == ShareScope.SelectedBlocks && selectedBlockUuids.isEmpty()
    val isGoogleAuthenticated = appState.shareIsGoogleAuthenticated
    val googleEmail = appState.shareGoogleEmail

    // Parse journal date range; sets dateError and returns null on invalid input.
    fun parseDateRange(): Pair<LocalDate, LocalDate>? {
        val from = runCatching { LocalDate.parse(journalFromText) }.getOrNull()
        val to = runCatching { LocalDate.parse(journalToText) }.getOrNull()
        if (from == null || to == null) {
            dateError = "Enter dates as YYYY-MM-DD"
            return null
        }
        if (from > to) {
            dateError = "Start date must be on or before end date"
            return null
        }
        dateError = null
        return from to to
    }

    // Returns parsed dates for JournalRange (or null+sets error), and (null,null) for other scopes.
    fun datesForScope(): Pair<LocalDate?, LocalDate?>? {
        if (appState.shareScope != ShareScope.JournalRange) return null to null
        return parseDateRange() ?: return null
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

        // ── Journal date range inputs ────────────────────────────────────────
        if (appState.shareScope == ShareScope.JournalRange) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = journalFromText,
                    onValueChange = { journalFromText = it; dateError = null },
                    label = { Text("From (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = journalToText,
                    onValueChange = { journalToText = it; dateError = null },
                    label = { Text("To (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            dateError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        // ── Format picker ────────────────────────────────────────────────────
        Text(
            text = "Format",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        val formats = listOf(
            FORMAT_MARKDOWN to "Markdown",
            FORMAT_PLAIN_TEXT to "Plain Text",
            FORMAT_HTML to "HTML",
            FORMAT_JSON to "JSON",
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

        exportError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        // Clipboard
        OutlinedButton(
            onClick = {
                val p = page ?: return@OutlinedButton
                val dates = datesForScope() ?: return@OutlinedButton
                viewModel.exportScopeToClipboard(
                    shareScope = appState.shareScope,
                    page = p,
                    allBlocks = blocks,
                    selectedUuids = selectedBlockUuids,
                    formatId = appState.shareFormat,
                    journalFrom = dates.first,
                    journalTo = dates.second,
                    onDone = { onDismiss() },
                )
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
                    val dates = datesForScope() ?: return@OutlinedButton
                    scope.launch {
                        val result = viewModel.resolveExportContent(
                            shareScope = appState.shareScope,
                            page = p,
                            allBlocks = blocks,
                            selectedUuids = selectedBlockUuids,
                            formatId = appState.shareFormat,
                            journalFrom = dates.first,
                            journalTo = dates.second,
                        )
                        result.fold(
                            ifLeft = { err -> exportError = err.message },
                            ifRight = { content ->
                                exportError = null
                                if (appState.shareFormat == FORMAT_HTML) {
                                    val plainResult = viewModel.resolveExportContent(
                                        shareScope = appState.shareScope,
                                        page = p,
                                        allBlocks = blocks,
                                        selectedUuids = selectedBlockUuids,
                                        formatId = FORMAT_PLAIN_TEXT,
                                        journalFrom = dates.first,
                                        journalTo = dates.second,
                                    )
                                    shareProvider.shareHtml(content, plainResult.getOrNull() ?: content)
                                } else {
                                    shareProvider.shareText(content)
                                }
                                onDismiss()
                            },
                        )
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
                val dates = datesForScope() ?: return@OutlinedButton
                val extension = formatExtension(appState.shareFormat)
                scope.launch {
                    val result = viewModel.resolveExportContent(
                        shareScope = appState.shareScope,
                        page = p,
                        allBlocks = blocks,
                        selectedUuids = selectedBlockUuids,
                        formatId = appState.shareFormat,
                        journalFrom = dates.first,
                        journalTo = dates.second,
                    )
                    result.fold(
                        ifLeft = { err -> exportError = err.message },
                        ifRight = { content ->
                            exportError = null
                            val saved = shareProvider.saveToFile(content, p.name, extension)
                            saved.fold(
                                ifLeft = { err -> exportError = err.message },
                                ifRight = { didSave -> if (didSave) onDismiss() },
                            )
                        },
                    )
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
                            val manager = googleAuthManager ?: return@OutlinedButton
                            viewModel.launchGoogleAuth(manager)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Connect Google Account")
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            val p = page ?: return@OutlinedButton
                            val dates = datesForScope() ?: return@OutlinedButton
                            viewModel.shareToGoogleDocs(
                                shareScope = appState.shareScope,
                                page = p,
                                allBlocks = blocks,
                                selectedUuids = selectedBlockUuids,
                                driveClient = driveClient,
                                journalFrom = dates.first,
                                journalTo = dates.second,
                            )
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
 *
 * Refreshes Google auth state from [googleAuthManager] each time the dialog opens (A3 fix:
 * single source of truth in [AppState] rather than local composable state).
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
    driveClient: DriveUploader?,
    googleAuthManager: GoogleAuthManager?,
    onDismiss: () -> Unit,
) {
    if (!appState.shareDialogVisible) return

    // Refresh auth state once per dialog open so the UI reflects the current token store.
    LaunchedEffect(googleAuthManager) {
        googleAuthManager?.let { viewModel.refreshShareGoogleAuthState(it) }
    }

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
                googleAuthManager = googleAuthManager,
                driveClient = driveClient,
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
                    googleAuthManager = googleAuthManager,
                    driveClient = driveClient,
                    isMobile = false,
                    onDismiss = onDismiss,
                )
            },
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun formatExtension(formatId: String): String = when (formatId) {
    FORMAT_MARKDOWN -> "md"
    FORMAT_PLAIN_TEXT -> "txt"
    FORMAT_HTML -> "html"
    FORMAT_JSON -> "json"
    else -> "txt"
}

/** Human-readable display name for [ShareScope] values. */
val ShareScope.displayName: String get() = when (this) {
    ShareScope.CurrentPage -> "Current Page"
    ShareScope.PageAndLinks -> "Page + Linked Pages"
    ShareScope.SelectedBlocks -> "Selected Blocks"
    ShareScope.JournalRange -> "Journal Date Range"
}
