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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.MergeSide
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.ConflictHunk
import dev.stapler.stelekit.git.model.HunkResolution
import kotlinx.coroutines.launch

/**
 * Per-file merge conflict resolution screen.
 *
 * For each conflicting file, the user picks a whole-file side ("Keep mine" / "Use remote") by
 * default — the fast path for the common case. When [ConflictFile.hunks] is non-empty (the file's
 * conflict markers parsed cleanly), a "Resolve line by line" button switches that file into a
 * per-hunk editor, so the user can keep some changes from each side instead of discarding one
 * entirely. Files with no parseable hunks (binary content, rename-only conflicts) only offer the
 * whole-file choice. Calls [onResolve] with the whole-file and per-hunk resolutions split into
 * their own maps when the user confirms — see [dev.stapler.stelekit.git.GitSyncService.resolveConflicts].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolutionScreen(
    conflicts: List<ConflictFile>,
    onResolve: suspend (
        sideResolutions: Map<String, MergeSide>,
        hunkResolutions: Map<String, List<ConflictHunk>>,
    ) -> Either<DomainError.GitError, Unit>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onAbortMerge: (suspend () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // Default: keep local version for each file, until the user either changes the whole-file
    // pick or switches that file into hunk-by-hunk mode.
    val sideSelections = remember(conflicts) {
        mutableStateMapOf<String, MergeSide>().also { map ->
            conflicts.forEach { map[it.filePath] = MergeSide.LOCAL }
        }
    }
    // Files the user has switched into the per-hunk editor.
    val hunkModeFiles = remember(conflicts) { mutableStateMapOf<String, Boolean>() }
    // Flat maps keyed by ConflictHunk.id (globally unique) rather than nested per-file, since a
    // hunk id never collides across files.
    val hunkChoices = remember(conflicts) { mutableStateMapOf<String, HunkResolution>() }
    val hunkManualText = remember(conflicts) { mutableStateMapOf<String, String>() }

    var resolving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAbortConfirm by remember { mutableStateOf(false) }

    val allResolved = conflicts.all { conflict ->
        if (hunkModeFiles[conflict.filePath] == true) {
            conflict.hunks.isNotEmpty() && conflict.hunks.all {
                val resolution = hunkChoices[it.id]
                resolution != null && resolution != HunkResolution.Unresolved &&
                    (resolution != HunkResolution.Manual || !hunkManualText[it.id].isNullOrBlank())
            }
        } else {
            sideSelections.containsKey(conflict.filePath)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Resolve Merge Conflicts") },
                actions = {
                    TextButton(
                        onClick = { showAbortConfirm = true },
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
                                val sideResolutions = mutableMapOf<String, MergeSide>()
                                val hunkResolutions = mutableMapOf<String, List<ConflictHunk>>()
                                conflicts.forEach { conflict ->
                                    if (hunkModeFiles[conflict.filePath] == true) {
                                        hunkResolutions[conflict.filePath] = conflict.hunks.map { hunk ->
                                            val resolution = hunkChoices[hunk.id] ?: HunkResolution.Unresolved
                                            hunk.copy(
                                                resolution = resolution,
                                                manualContent = if (resolution == HunkResolution.Manual) {
                                                    hunkManualText[hunk.id]
                                                } else {
                                                    null
                                                },
                                            )
                                        }
                                    } else {
                                        sideSelections[conflict.filePath]?.let {
                                            sideResolutions[conflict.filePath] = it
                                        }
                                    }
                                }
                                val result = onResolve(sideResolutions, hunkResolutions)
                                resolving = false
                                if (result.isRight()) {
                                    onDismiss()
                                } else {
                                    error = "Resolution failed: ${(result as Either.Left).value.message}"
                                }
                            }
                        },
                        enabled = !resolving && allResolved,
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
                "${conflicts.size} file(s) need resolution. Choose which version to keep, " +
                    "or resolve line by line where you need to keep changes from both sides.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            conflicts.forEach { conflict ->
                ConflictFileCard(
                    conflict = conflict,
                    sideSelection = sideSelections[conflict.filePath] ?: MergeSide.LOCAL,
                    onSideSelectionChange = {
                        sideSelections[conflict.filePath] = it
                        hunkModeFiles[conflict.filePath] = false
                    },
                    hunkMode = hunkModeFiles[conflict.filePath] == true,
                    onEnterHunkMode = { hunkModeFiles[conflict.filePath] = true },
                    onExitHunkMode = { hunkModeFiles[conflict.filePath] = false },
                    hunkChoice = { hunkId -> hunkChoices[hunkId] ?: HunkResolution.Unresolved },
                    onHunkChoiceChange = { hunkId, resolution -> hunkChoices[hunkId] = resolution },
                    manualText = { hunkId -> hunkManualText[hunkId] ?: "" },
                    onManualTextChange = { hunkId, text -> hunkManualText[hunkId] = text },
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
    sideSelection: MergeSide,
    onSideSelectionChange: (MergeSide) -> Unit,
    hunkMode: Boolean,
    onEnterHunkMode: () -> Unit,
    onExitHunkMode: () -> Unit,
    hunkChoice: (String) -> HunkResolution,
    onHunkChoiceChange: (String, HunkResolution) -> Unit,
    manualText: (String) -> String,
    onManualTextChange: (String, String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = conflict.wikiRelativePath.ifBlank { conflict.filePath },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (conflict.hunks.isNotEmpty()) {
                    TextButton(onClick = { if (hunkMode) onExitHunkMode() else onEnterHunkMode() }, enabled = enabled) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (hunkMode) "Use one version" else "Resolve line by line")
                    }
                }
            }

            if (conflict.duplicateBlockIds.isNotEmpty()) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Duplicate block ID" + (if (conflict.duplicateBlockIds.size > 1) "s" else "") + ": " +
                            conflict.duplicateBlockIds.joinToString(", ") { it.id } +
                            " — this page has more than one block claiming the same id::, which can break block references. Resolving this conflict does not fix it by itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (hunkMode) {
                Text(
                    "${conflict.hunks.size} conflicting section(s). Pick a side or write your own for each.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                conflict.hunks.forEachIndexed { index, hunk ->
                    if (index > 0) HorizontalDivider()
                    HunkEditor(
                        hunk = hunk,
                        resolution = hunkChoice(hunk.id),
                        onResolutionChange = { onHunkChoiceChange(hunk.id, it) },
                        manualText = manualText(hunk.id),
                        onManualTextChange = { onManualTextChange(hunk.id, it) },
                        enabled = enabled,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = sideSelection == MergeSide.LOCAL,
                        onClick = { if (enabled) onSideSelectionChange(MergeSide.LOCAL) },
                        label = { Text("Keep mine") },
                        leadingIcon = {
                            Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = sideSelection == MergeSide.REMOTE,
                        onClick = { if (enabled) onSideSelectionChange(MergeSide.REMOTE) },
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
}

@Composable
private fun HunkEditor(
    hunk: ConflictHunk,
    resolution: HunkResolution,
    onResolutionChange: (HunkResolution) -> Unit,
    manualText: String,
    onManualTextChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = resolution == HunkResolution.AcceptLocal,
                onClick = { if (enabled) onResolutionChange(HunkResolution.AcceptLocal) },
                label = { Text("Keep mine") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = resolution == HunkResolution.AcceptRemote,
                onClick = { if (enabled) onResolutionChange(HunkResolution.AcceptRemote) },
                label = { Text("Keep theirs") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = resolution == HunkResolution.Manual,
                onClick = { if (enabled) onResolutionChange(HunkResolution.Manual) },
                label = { Text("Write my own") },
                modifier = Modifier.weight(1f),
            )
        }
        HunkSideBlock(label = "Yours", lines = hunk.localLines)
        HunkSideBlock(label = "Theirs", lines = hunk.remoteLines)
        if (resolution == HunkResolution.Manual) {
            OutlinedTextField(
                value = manualText,
                onValueChange = onManualTextChange,
                enabled = enabled,
                label = { Text("Resolved content for this section") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }
    }
}

@Composable
private fun HunkSideBlock(label: String, lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}
