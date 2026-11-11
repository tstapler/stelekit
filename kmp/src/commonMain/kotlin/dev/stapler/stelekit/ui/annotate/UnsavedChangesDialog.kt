// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Confirms discarding only the unfinished points; placed measurements are already saved. */
@Composable
fun UnsavedChangesDialog(
    unfinishedPoints: Int,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        icon = { WarningIcon() },
        title = { Text(discardDialogTitle(unfinishedPoints)) },
        text = { Text("Placed measurements are already saved.") },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text("Keep editing") } },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
    )
}

/** Shown when leaving while the last save failed. */
@Composable
fun SaveFailedDialog(
    onRetry: () -> Unit,
    onCloseAnyway: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onRetry,
        icon = { WarningIcon() },
        title = { Text("Couldn't save the last change") },
        text = { Text("Close without saving the last change?") },
        dismissButton = { TextButton(onClick = onCloseAnyway) { Text("Close anyway") } },
        confirmButton = { TextButton(onClick = onRetry) { Text("Retry") } },
    )
}

@Composable
private fun WarningIcon() {
    Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
    )
}
