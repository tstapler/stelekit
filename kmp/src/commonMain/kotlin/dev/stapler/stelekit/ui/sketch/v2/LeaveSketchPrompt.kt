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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

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
    canExport: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(SketchStrings.Leave.TITLE) },
        text = { Text(SketchStrings.Leave.BODY) },
        confirmButton = { LeaveSketchActions(onRetry, onExportCopy, onDiscard, canExport) },
    )
}

/** The three actions stacked, Retry save first (the primary action, and focused when shown). Separate so focus is testable without a window. */
@Composable
internal fun LeaveSketchActions(onRetry: () -> Unit, onExportCopy: () -> Unit, onDiscard: () -> Unit, canExport: Boolean = true) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { retryFocus.requestFocus() }
    Column(horizontalAlignment = Alignment.End) {
        Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus).testTag(LEAVE_SKETCH_RETRY_TAG)) { Text(SketchStrings.Leave.RETRY) }
        if (canExport) TextButton(onClick = onExportCopy, modifier = Modifier.testTag(LEAVE_SKETCH_EXPORT_TAG)) { Text(SketchStrings.Leave.EXPORT_COPY) }
        TextButton(onClick = onDiscard, modifier = Modifier.testTag(LEAVE_SKETCH_DISCARD_TAG)) { Text(SketchStrings.Leave.DISCARD) }
    }
}

/**
 * Back, graph switch and Android back go through here (UX-72): the editor saves first, and only a sheet whose latest edits could not
 * be written asks. [leave] runs once the sheet is on disk or the user chose Discard; Retry, Export copy and Keep editing stay.
 * Plain state, so a test drives it without a window; [scope] is the host's event scope, never stored beyond the composition.
 */
class LeaveGuard(
    private val flush: suspend () -> SaveState,
    private val scope: CoroutineScope,
    private val leave: () -> Unit,
) {
    /** True while the prompt is up. */
    var prompting: Boolean by mutableStateOf(false)
        private set

    /** True after the user chose Discard: the host closes the editor without the flush a normal leave does. */
    var discarded: Boolean = false
        private set

    private var busy = false

    /** The back action: flush, then leave when everything is saved, otherwise prompt. */
    fun requestLeave() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (leaveNeedsPrompt(flush())) {
                    prompting = true
                } else {
                    prompting = false
                    leave()
                }
            } finally {
                busy = false
            }
        }
    }

    /** Retry save: leaves when the retry wrote the sheet, stays in the prompt when it did not. */
    fun retry() {
        requestLeave()
    }

    fun keepEditing() {
        prompting = false
    }

    /** Leaves with the sheet as last saved. */
    fun discard() {
        discarded = true
        prompting = false
        leave()
    }
}
