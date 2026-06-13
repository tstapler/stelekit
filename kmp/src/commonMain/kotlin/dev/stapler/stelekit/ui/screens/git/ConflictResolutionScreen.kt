// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.MergeSide
import dev.stapler.stelekit.git.model.ConflictFile
import kotlinx.coroutines.launch

/**
 * Per-file merge conflict resolution screen.
 * For each conflicting file, the user picks: keep their local version or use the remote version.
 * Calls [onResolve] with the map of filePath → side when the user confirms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolutionScreen(
    conflicts: List<ConflictFile>,
    onResolve: suspend (Map<String, MergeSide>) -> Either<DomainError.GitError, Unit>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onAbortMerge: (suspend () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // Default: keep local version for each file
    val selections = remember(conflicts) {
        androidx.compose.runtime.mutableStateMapOf<String, MergeSide>().also { map ->
            conflicts.forEach { map[it.filePath] = MergeSide.LOCAL }
        }
    }
    var resolving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAbortConfirm by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Resolve Merge Conflicts") },
                actions = {
                    TextButton(
                        onClick = { if (onAbortMerge != null) showAbortConfirm = true else onDismiss() },
                        enabled = !resolving,
                    ) { Text("Cancel") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                resolving = true
                                error = null
                                val result = onResolve(selections.toMap())
                                resolving = false
                                if (result.isRight()) {
                                    onDismiss()
                                } else {
                                    error = "Resolution failed: ${(result as Either.Left).value.message}"
                                }
                            }
                        },
                        enabled = !resolving && selections.size == conflicts.size,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (resolving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Finish Merge")
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "A background sync pulled remote changes that conflict with your local edits.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${conflicts.size} file(s) need resolution. Choose which version to keep for each.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            conflicts.forEach { conflict ->
                ConflictFileCard(
                    conflict = conflict,
                    selection = selections[conflict.filePath] ?: MergeSide.LOCAL,
                    onSelectionChange = { selections[conflict.filePath] = it },
                    enabled = !resolving,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showAbortConfirm) {
        AlertDialog(
            onDismissRequest = { showAbortConfirm = false },
            title = { Text("Abort merge?") },
            text = { Text("Canceling will undo the merge attempt. Your local changes will be preserved and the remote changes discarded.") },
            confirmButton = {
                TextButton(onClick = {
                    showAbortConfirm = false
                    scope.launch {
                        onAbortMerge?.invoke()
                        onDismiss()
                    }
                }) { Text("Abort merge", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showAbortConfirm = false }) { Text("Keep resolving") }
            },
        )
    }
}

@Composable
private fun ConflictFileCard(
    conflict: ConflictFile,
    selection: MergeSide,
    onSelectionChange: (MergeSide) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = conflict.wikiRelativePath.ifBlank { conflict.filePath },
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selection == MergeSide.LOCAL,
                    onClick = { if (enabled) onSelectionChange(MergeSide.LOCAL) },
                    label = { Text("Keep mine") },
                    leadingIcon = {
                        Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = selection == MergeSide.REMOTE,
                    onClick = { if (enabled) onSelectionChange(MergeSide.REMOTE) },
                    label = { Text("Use remote") },
                    leadingIcon = {
                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
