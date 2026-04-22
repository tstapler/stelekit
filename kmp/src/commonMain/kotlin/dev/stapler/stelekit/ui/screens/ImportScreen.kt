// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.domain.FetchResult
import dev.stapler.stelekit.domain.TopicSuggestion
import dev.stapler.stelekit.performance.NavigationTracingEffect
import dev.stapler.stelekit.ui.components.parseMarkdownWithStyling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class Stage { INPUT, REVIEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    onDismiss: () -> Unit,
    /**
     * Optional callback invoked when the user clicks "Paste with formatting".
     * The caller is responsible for reading HTML from the clipboard and calling
     * [ImportViewModel.onHtmlPasted]. Provide null to hide the button.
     */
    onHtmlPaste: (() -> Unit)? = null,
) {
    NavigationTracingEffect("Import")
    val state by viewModel.state.collectAsState()
    val coroutineScope: CoroutineScope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(Stage.INPUT) }

    // When save completes and the undo snackbar is no longer showing, dismiss
    LaunchedEffect(state.savedPageName, state.showUndoSnackbar) {
        if (state.savedPageName != null && !state.showUndoSnackbar) {
            onDismiss()
        }
    }

    when (stage) {
        Stage.INPUT -> InputStage(
            state = state,
            viewModel = viewModel,
            coroutineScope = coroutineScope,
            onNext = { stage = Stage.REVIEW },
            onDismiss = onDismiss,
            onHtmlPaste = onHtmlPaste,
        )

        Stage.REVIEW -> ReviewStage(
            state = state,
            onConfirmImport = { coroutineScope.launch { viewModel.confirmImport() } },
            onBack = { stage = Stage.INPUT },
            onSuggestionAccepted = viewModel::onSuggestionAccepted,
            onSuggestionDismissed = viewModel::onSuggestionDismissed,
            onAcceptAllSuggestions = viewModel::onAcceptAllSuggestions,
            onUndoStubCreation = viewModel::onUndoStubCreation,
            onClearUndoSnackbar = viewModel::clearUndoSnackbar,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputStage(
    state: ImportState,
    viewModel: ImportViewModel,
    coroutineScope: CoroutineScope,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    onHtmlPaste: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import text") },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Tab row
            val selectedTabIndex = when (state.activeTab) {
                ImportTab.PASTE -> 0
                ImportTab.URL -> 1
            }
            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = state.activeTab == ImportTab.PASTE,
                    onClick = { viewModel.onTabChanged(ImportTab.PASTE) },
                    text = { Text("Paste text") },
                )
                Tab(
                    selected = state.activeTab == ImportTab.URL,
                    onClick = { viewModel.onTabChanged(ImportTab.URL) },
                    text = { Text("From URL") },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (state.activeTab) {
                ImportTab.PASTE -> PasteTabContent(
                    state = state,
                    onRawTextChanged = { viewModel.onRawTextChanged(it) },
                    onHtmlPaste = onHtmlPaste,
                )

                ImportTab.URL -> UrlTabContent(
                    state = state,
                    onUrlChanged = { viewModel.onUrlChanged(it) },
                    onFetch = { coroutineScope.launch { viewModel.fetchUrl() } },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Page name field — common to both tabs
            OutlinedTextField(
                value = state.pageName,
                onValueChange = { viewModel.onPageNameChanged(it) },
                label = { Text("Page name") },
                isError = state.pageNameError != null,
                supportingText = if (state.pageNameError != null) {
                    { Text(state.pageNameError, color = MaterialTheme.colorScheme.error) }
                } else null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onNext,
                enabled = state.rawText.isNotBlank() && !state.isScanning && !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Next: Review links")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PasteTabContent(
    state: ImportState,
    onRawTextChanged: (String) -> Unit,
    onHtmlPaste: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = state.rawText,
        onValueChange = onRawTextChanged,
        label = { Text("Paste your content here") },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 300.dp),
        minLines = 10,
    )

    if (onHtmlPaste != null) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onHtmlPaste,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Paste with formatting")
        }
    }

    if (state.rawText.length > 10_000) {
        Text(
            text = "${state.rawText.length} characters",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (state.isScanning) {
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun UrlTabContent(
    state: ImportState,
    onUrlChanged: (String) -> Unit,
    onFetch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.urlInput,
            onValueChange = onUrlChanged,
            label = { Text("Enter URL") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        Button(
            onClick = onFetch,
            enabled = !state.isFetching,
        ) {
            Text("Fetch")
        }
    }

    if (state.isFetching) {
        Spacer(modifier = Modifier.height(8.dp))
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }

    if (state.fetchError != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = fetchErrorMessage(state.fetchError),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun fetchErrorMessage(error: FetchResult.Failure): String = when (error) {
    is FetchResult.Failure.Timeout -> "Request timed out. Please try again."
    is FetchResult.Failure.NetworkUnavailable -> "Network unavailable. Check your connection."
    is FetchResult.Failure.HttpError -> "HTTP error ${error.code}."
    is FetchResult.Failure.ParseError -> "Could not parse the page content."
    is FetchResult.Failure.TooLarge -> "Page is too large to import."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStage(
    state: ImportState,
    onConfirmImport: () -> Unit,
    onBack: () -> Unit,
    onSuggestionAccepted: (String) -> Unit,
    onSuggestionDismissed: (String) -> Unit,
    onAcceptAllSuggestions: () -> Unit,
    onUndoStubCreation: () -> Unit,
    onClearUndoSnackbar: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.showUndoSnackbar) {
        if (state.showUndoSnackbar) {
            val result = snackbarHostState.showSnackbar(
                message = "Pages created",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> onUndoStubCreation()
                SnackbarResult.Dismissed -> onClearUndoSnackbar()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review links") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Back")
                    }
                    Button(
                        onClick = onConfirmImport,
                        enabled = !state.isSaving,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Import page")
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (state.matchedPageNames.isEmpty()) {
                Text(
                    text = "No existing page topics were detected in this text.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Detected topics",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(state.matchedPageNames) { pageName ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(pageName) },
                        )
                    }
                }
            }

            // Suggested new pages tray
            TopicSuggestionTray(
                state = state,
                onSuggestionAccepted = onSuggestionAccepted,
                onSuggestionDismissed = onSuggestionDismissed,
                onAcceptAllSuggestions = onAcceptAllSuggestions,
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (state.linkedText.isNotBlank()) {
                val linkColor = MaterialTheme.colorScheme.primary
                val textColor = MaterialTheme.colorScheme.onSurface
                val styledText = remember(state.linkedText, linkColor, textColor) {
                    parseMarkdownWithStyling(
                        text = state.linkedText,
                        linkColor = linkColor,
                        textColor = textColor,
                    )
                }
                Text(
                    text = styledText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (state.rawText.isNotBlank()) {
                Text(
                    text = state.rawText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TopicSuggestionTray(
    state: ImportState,
    onSuggestionAccepted: (String) -> Unit,
    onSuggestionDismissed: (String) -> Unit,
    onAcceptAllSuggestions: () -> Unit,
) {
    val visibleSuggestions = state.topicSuggestions.filter { !it.dismissed }
    if (visibleSuggestions.isEmpty()) return

    var showAcceptAllDialog by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    val displaySuggestions = if (showAll) visibleSuggestions else visibleSuggestions.take(8)
    val remainingCount = visibleSuggestions.size - 8

    Spacer(modifier = Modifier.height(16.dp))

    Column {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Suggested new pages (${visibleSuggestions.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Claude status badge
                when (state.claudeStatus) {
                    is ClaudeStatus.Loading -> {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                        )
                    }
                    is ClaudeStatus.Done -> {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "AI-enhanced",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    is ClaudeStatus.Failed -> {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "AI unavailable",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {}
                }
            }

            if (visibleSuggestions.any { !it.accepted }) {
                TextButton(onClick = { showAcceptAllDialog = true }) {
                    Text("Accept All")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(displaySuggestions) { suggestion ->
                TopicSuggestionChip(
                    suggestion = suggestion,
                    onAccepted = { onSuggestionAccepted(suggestion.term) },
                    onDismissed = { onSuggestionDismissed(suggestion.term) },
                )
            }
        }

        if (!showAll && remainingCount > 0) {
            TextButton(onClick = { showAll = true }) {
                Text("Show $remainingCount more")
            }
        }
    }

    if (showAcceptAllDialog) {
        val pendingCount = visibleSuggestions.count { !it.accepted }.coerceAtMost(10)
        AlertDialog(
            onDismissRequest = { showAcceptAllDialog = false },
            title = { Text("Accept all suggestions?") },
            text = { Text("This will create up to $pendingCount new stub pages.") },
            confirmButton = {
                TextButton(onClick = {
                    onAcceptAllSuggestions()
                    showAcceptAllDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showAcceptAllDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TopicSuggestionChip(
    suggestion: TopicSuggestion,
    onAccepted: () -> Unit,
    onDismissed: () -> Unit,
) {
    val dotColor = when {
        suggestion.confidence >= 0.7f -> MaterialTheme.colorScheme.primary
        suggestion.confidence >= 0.4f -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }

    val chipBackground = if (suggestion.accepted) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(chipBackground)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = suggestion.term,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(4.dp))
        if (suggestion.accepted) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Accepted",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            IconButton(
                onClick = onDismissed,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(12.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onAccepted,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Accept",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
