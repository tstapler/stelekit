// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.merge.SketchCopyAction
import dev.stapler.stelekit.merge.SketchCopyDialogModel

const val SKETCH_COPY_DIALOG_TAG = "sketch-copy-dialog"
const val SKETCH_COPY_NAME_FIELD_TAG = "sketch-copy-name-field"

/** Test tag of the button for [action], e.g. `sketch-copy-action-open`. */
fun sketchCopyActionTag(action: SketchCopyAction): String = "sketch-copy-action-" + when (action) {
    is SketchCopyAction.Open -> "open"
    SketchCopyAction.Done -> "done"
    SketchCopyAction.RenameAndCopy -> "rename"
    SketchCopyAction.Retry -> "retry"
    SketchCopyAction.Cancel -> "cancel"
}

/** The button text of [action] (UX-63); the one place the wording lives. */
fun sketchCopyActionLabel(action: SketchCopyAction): String = when (action) {
    is SketchCopyAction.Open -> "Open"
    SketchCopyAction.Done -> "Done"
    SketchCopyAction.RenameAndCopy -> "Rename and copy"
    SketchCopyAction.Retry -> "Retry"
    SketchCopyAction.Cancel -> "Cancel"
}

private val NAME_FIELD_WIDTH = 240.dp
private val SAFE_SLUG = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")

/** A slug the copy accepts for [SketchCopyAction.RenameAndCopy]: a different, file-safe name. */
fun isRenameTarget(candidate: String, currentSlug: String): Boolean = candidate != currentSlug && SAFE_SLUG.matches(candidate)

/**
 * The outcome of "Copy sketch to graph" as a dialog (Task 6.1.2c, UX-63): [model] says what to show and which actions
 * exist, in order; the first is the main one. When Rename and copy is offered the dialog also asks for the new name and
 * reports it through [onRenameAndCopy] (the button stays off until the name is a different, file-safe one). Every other
 * action goes to [onAction]; dismissing the dialog is Cancel when offered, else Done, else the first action.
 */
@Composable
fun SketchCopyDialog(
    model: SketchCopyDialogModel,
    currentSlug: String,
    onAction: (SketchCopyAction) -> Unit,
    onRenameAndCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newName by remember(model, currentSlug) { mutableStateOf("$currentSlug-2") }
    val renames = SketchCopyAction.RenameAndCopy in model.actions
    val dismiss = model.actions.firstOrNull { it == SketchCopyAction.Cancel } ?: model.actions.firstOrNull { it == SketchCopyAction.Done } ?: model.actions.firstOrNull()

    val button: @Composable (SketchCopyAction) -> Unit = { action ->
        val isRename = action == SketchCopyAction.RenameAndCopy
        CopyActionButton(
            action = action,
            enabled = !isRename || isRenameTarget(newName, currentSlug),
            onClick = { if (isRename) onRenameAndCopy(newName) else onAction(action) },
        )
    }

    AlertDialog(
        onDismissRequest = { dismiss?.let(onAction) },
        modifier = modifier.testTag(SKETCH_COPY_DIALOG_TAG),
        title = { Text(model.title, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                model.detail?.let { Text(it) }
                if (renames) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New name") },
                        singleLine = true,
                        isError = !isRenameTarget(newName, currentSlug),
                        supportingText = { if (!isRenameTarget(newName, currentSlug)) Text("Use letters, digits, - or _, and a name that is not taken.") },
                        modifier = Modifier.width(NAME_FIELD_WIDTH).testTag(SKETCH_COPY_NAME_FIELD_TAG),
                    )
                }
            }
        },
        confirmButton = { model.actions.firstOrNull()?.let { button(it) } },
        dismissButton = { model.actions.getOrNull(1)?.let { button(it) } },
    )
}

@Composable
private fun CopyActionButton(action: SketchCopyAction, enabled: Boolean, onClick: () -> Unit) {
    TextButton(enabled = enabled, onClick = onClick, modifier = Modifier.testTag(sketchCopyActionTag(action))) { Text(sketchCopyActionLabel(action)) }
}
