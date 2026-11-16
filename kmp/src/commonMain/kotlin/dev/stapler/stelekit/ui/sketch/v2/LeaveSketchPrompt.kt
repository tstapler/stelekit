// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState

const val LEAVE_SKETCH_RETRY_TAG = "sketch.leave.retry"
const val LEAVE_SKETCH_EXPORT_TAG = "sketch.leave.export"
const val LEAVE_SKETCH_DISCARD_TAG = "sketch.leave.discard"

/** Whether leaving the sheet must ask first (UX-72): any state where the latest edits are not on disk. */
fun leaveNeedsPrompt(state: SaveState): Boolean = state.hasUnsavedWork

/**
 * The dialog shown when the user navigates back with unsaved or failed changes. Retry save is the default and takes
 * focus first; Export copy hands the current scene to the export path without touching the sidecar; Discard leaves the
 * sheet as last saved. Dismissing the dialog (back, scrim) keeps editing: nothing destructive is the default.
 */
@Composable
fun LeaveSketchPrompt(
    onRetry: () -> Unit,
    onExportCopy: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Changes not saved") },
        text = { Text("Your latest changes are not saved yet. Your work is kept until you choose.") },
        confirmButton = { LeaveSketchActions(onRetry, onExportCopy, onDiscard) },
    )
}

/** The three actions stacked, Retry save first (the primary action, and focused when shown). Separate so focus is testable without a window. */
@Composable
internal fun LeaveSketchActions(onRetry: () -> Unit, onExportCopy: () -> Unit, onDiscard: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryFocus.requestFocus() }
    Column(horizontalAlignment = Alignment.End) {
        Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus).testTag(LEAVE_SKETCH_RETRY_TAG)) { Text("Retry save") }
        TextButton(onClick = onExportCopy, modifier = Modifier.testTag(LEAVE_SKETCH_EXPORT_TAG)) { Text("Export copy") }
        TextButton(onClick = onDiscard, modifier = Modifier.testTag(LEAVE_SKETCH_DISCARD_TAG)) { Text("Discard") }
    }
}
