// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.merge.JournalMergeProposal

/**
 * Three-panel merge review screen for algorithmically-merged journal files.
 * Shows LOCAL (read-only), MERGED RESULT (editable), REMOTE (read-only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalMergeReviewScreen(
    proposal: JournalMergeProposal,
    onAccept: (mergedContent: String) -> Unit,
    onFallbackToManual: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editedMerge by remember(proposal) { mutableStateOf(proposal.proposedMerge) }
    var showAbandonDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Review Merged Journal") },
                actions = {
                    TextButton(onClick = { showAbandonDialog = true }) { Text("Cancel") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (proposal.confidenceWarning) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            Text(
                                text = "The merged result is shorter than expected — some entries may have been lost. Review carefully.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                    if (proposal.hasConflictMarkers) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            Text(
                                text = "Some lines could not be merged automatically and are marked with <<<<<<< / >>>>>>>. Review and resolve these manually.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onFallbackToManual,
                            modifier = Modifier.weight(1f),
                        ) { Text("Manual resolve") }
                        Button(
                            onClick = { onAccept(editedMerge) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Accept") }
                    }
                }
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.padding(innerPadding)) {
            if (maxWidth < 600.dp) {
                // Mobile: stacked layout
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MergePanel("LOCAL (read-only)", proposal.localContent, editable = false, onEdit = {})
                    MergePanel("MERGED RESULT", editedMerge, editable = true, onEdit = { editedMerge = it })
                    MergePanel("REMOTE (read-only)", proposal.remoteContent, editable = false, onEdit = {})
                }
            } else {
                // Desktop: 3-column layout
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MergePanel("LOCAL", proposal.localContent, editable = false, onEdit = {}, modifier = Modifier.weight(1f))
                    MergePanel("MERGED", editedMerge, editable = true, onEdit = { editedMerge = it }, modifier = Modifier.weight(1f))
                    MergePanel("REMOTE", proposal.remoteContent, editable = false, onEdit = {}, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (showAbandonDialog) {
        AlertDialog(
            onDismissRequest = { showAbandonDialog = false },
            title = { Text("Abandon merge review?") },
            text = { Text("Any edits to the merged result will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    showAbandonDialog = false
                    onDismiss()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showAbandonDialog = false }) { Text("Continue editing") }
            },
        )
    }
}

@Composable
private fun MergePanel(
    title: String,
    content: String,
    editable: Boolean,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        OutlinedTextField(
            value = content,
            onValueChange = onEdit,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 200.dp),
            readOnly = !editable,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            maxLines = Int.MAX_VALUE,
        )
    }
}
