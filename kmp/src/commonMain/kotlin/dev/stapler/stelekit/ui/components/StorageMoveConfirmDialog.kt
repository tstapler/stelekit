// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.StorageLocation

/**
 * Story 3.4.2: names the exact source/destination before a relocate/link starts (design/ux.md
 * Surface 6), modeled on [Sidebar]'s graph-removal `AlertDialog` — a plain `AlertDialog` whose
 * `onDismissRequest` closes with zero side effects, matching that dialog's `graphToRemove = null`
 * convention exactly (AC26).
 *
 * Default keyboard focus lands on "Cancel," never the confirm action (AC23) — per
 * `research/ux.md` §3's W3C WAI-ARIA-derived guidance for a destructive-adjacent default focus,
 * mirroring [RenamePageDialog]'s existing `FocusRequester` + `LaunchedEffect` pattern.
 */
@Composable
fun StorageMoveConfirmDialog(
    graphName: String,
    source: StorageLocation,
    destination: StorageLocation,
    confirmLabel: String = "Move",
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val cancelFocusRequester = remember { FocusRequester() }
    val reassurance = "Your files stay where they are until the copy is verified."

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Move \"$graphName\"?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Move \"$graphName\" from ${source.describeForHumans()} to " +
                        "${destination.describeForHumans()}?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // AC25: verbatim, and doubles as the dialog's accessible description.
                Text(
                    reassurance,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = reassurance },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.focusRequester(cancelFocusRequester),
            ) { Text("Cancel") }
        },
    )

    LaunchedEffect(Unit) {
        cancelFocusRequester.requestFocus()
    }
}
