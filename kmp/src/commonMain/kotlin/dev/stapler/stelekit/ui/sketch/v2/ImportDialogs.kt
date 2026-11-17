// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.runtime.importing.OverCap
import dev.stapler.stelekit.sketch.v2.runtime.importing.OverCapChoice
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Import as Strings

object ImportTags {
    const val PROGRESS = "sketch.import.progress"
    const val PROGRESS_CANCEL = "sketch.import.progress.cancel"
    const val CONFIRM = "sketch.import.confirm"
    const val OVER_CAP_TEXT = "sketch.import.overcap.text"
    const val NEWER_NOTICE = "sketch.import.newer"
    const val IMPORT_FIRST = "sketch.import.first"
    const val IMPORT_NEW_SKETCH = "sketch.import.newsketch"
    const val CONFIRM_IMPORT = "sketch.import.confirm.import"
    const val CONFIRM_CANCEL = "sketch.import.confirm.cancel"
    const val SUMMARY = "sketch.import.summary"
    const val SUMMARY_LINE = "sketch.import.summary.line"
    const val DETAILS_TOGGLE = "sketch.import.details.toggle"
    const val DETAILS_TEXT = "sketch.import.details.text"
    const val COPY_DETAILS = "sketch.import.copy"
    const val UNDO_IMPORT = "sketch.import.undo"
    const val DONE = "sketch.import.done"
    const val REFUSED = "sketch.import.refused"
    const val REFUSED_CODE = "sketch.import.refused.code"
    const val CHOOSE_ANOTHER = "sketch.import.another"
    const val CLOSE = "sketch.import.close"
    const val UNAVAILABLE = "sketch.import.unavailable"
    const val NOTICE = "sketch.import.notice"
}

private const val TARGET_DP = 44

/**
 * The import dialogs (ux.md section 11), one at a time, driven by [controller]'s state: progress with Cancel, the over-cap and
 * newer-schema choice, the summary with Copy details, Undo import and Done, and the refusal, unavailable and cancelled messages.
 * [onCopyDetails] puts the details text on the clipboard (the host owns the platform clipboard). [canCreateSheet] is false where
 * the host cannot add a sheet, which disables "Import into a new sketch" with its reason.
 */
@Composable
fun ExcalidrawImportDialogs(controller: ExcalidrawImportController, onCopyDetails: (String) -> Unit, canCreateSheet: Boolean = true) {
    val state by controller.state.collectAsState()
    when (val current = state) {
        ImportUiState.Idle -> Unit
        is ImportUiState.Reading -> ProgressDialog(current, controller::cancel)
        is ImportUiState.Confirm -> ConfirmDialog(current, controller, canCreateSheet)
        is ImportUiState.Summary -> SummaryDialog(current, controller, onCopyDetails)
        is ImportUiState.Refused -> RefusedDialog(current, controller)
        is ImportUiState.Unavailable -> MessageDialog(Strings.UNAVAILABLE_TITLE, current.reason, ImportTags.UNAVAILABLE, controller::dismiss)
        is ImportUiState.Notice -> MessageDialog(Strings.MENU_ITEM, current.message, ImportTags.NOTICE, controller::dismiss)
    }
}

@Composable
private fun ProgressDialog(state: ImportUiState.Reading, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.testTag(ImportTags.PROGRESS),
        title = { Text(Strings.progressTitle(state.name), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Strings.reading(sizeText(state.sizeBytes)))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { DialogButton(Strings.CANCEL, ImportTags.PROGRESS_CANCEL, onCancel) },
    )
}

@Composable
private fun ConfirmDialog(state: ImportUiState.Confirm, controller: ExcalidrawImportController, canCreateSheet: Boolean) {
    AlertDialog(
        onDismissRequest = controller::cancel,
        modifier = Modifier.testTag(ImportTags.CONFIRM),
        title = { Text(Strings.progressTitle(state.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.overCapText?.let { Text(it, Modifier.testTag(ImportTags.OVER_CAP_TEXT)) }
                if (state.newerSchema) Text(Strings.NEWER_SCHEMA, Modifier.testTag(ImportTags.NEWER_NOTICE))
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                val over = state.overCap
                if (over == null) {
                    DialogButton(Strings.IMPORT_ANYWAY, ImportTags.CONFIRM_IMPORT, controller::confirmNewer)
                } else {
                    OverCapButtons(over, canCreateSheet, controller)
                }
                DialogButton(Strings.CANCEL, ImportTags.CONFIRM_CANCEL, controller::cancel)
            }
        },
    )
}

@Composable
private fun OverCapButtons(over: OverCap, canCreateSheet: Boolean, controller: ExcalidrawImportController) {
    Column(horizontalAlignment = Alignment.End) {
        DialogButton(Strings.importFirst(over.limit), ImportTags.IMPORT_FIRST, { controller.chooseOverCap(OverCapChoice.ImportFirst) }, enabled = over.canImportFirst)
        if (!over.canImportFirst) Text(Strings.OVER_CAP_FIRST_UNAVAILABLE, style = MaterialTheme.typography.bodySmall)
        DialogButton(Strings.IMPORT_NEW_SKETCH, ImportTags.IMPORT_NEW_SKETCH, { controller.chooseOverCap(OverCapChoice.ImportIntoNewSketch) }, enabled = canCreateSheet)
        if (!canCreateSheet) Text(Strings.OVER_CAP_NEW_SKETCH_UNAVAILABLE, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SummaryDialog(state: ImportUiState.Summary, controller: ExcalidrawImportController, onCopyDetails: (String) -> Unit) {
    var showDetails by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = controller::dismiss,
        modifier = Modifier.testTag(ImportTags.SUMMARY),
        title = { Text(Strings.SUMMARY_TITLE, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.lines.forEach { Text(it, Modifier.testTag(ImportTags.SUMMARY_LINE)) }
                TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(ImportTags.DETAILS_TOGGLE)) {
                    Text(Strings.DETAILS_HEADING)
                }
                if (showDetails) Text(state.details, Modifier.testTag(ImportTags.DETAILS_TEXT), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                DialogButton(Strings.COPY_DETAILS, ImportTags.COPY_DETAILS, { onCopyDetails(controller.detailsText()) })
                if (state.canUndo && controller.canUndoImport()) DialogButton(Strings.UNDO_IMPORT, ImportTags.UNDO_IMPORT, controller::undoImport)
                DialogButton(Strings.DONE, ImportTags.DONE, controller::dismiss)
            }
        },
    )
}

@Composable
private fun RefusedDialog(state: ImportUiState.Refused, controller: ExcalidrawImportController) {
    AlertDialog(
        onDismissRequest = controller::dismiss,
        modifier = Modifier.testTag(ImportTags.REFUSED),
        title = { Text(Strings.MENU_ITEM) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.message, Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                state.code?.let { Text(Strings.reasonLine(it), Modifier.testTag(ImportTags.REFUSED_CODE), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (state.canChooseAnother) DialogButton(Strings.CHOOSE_ANOTHER, ImportTags.CHOOSE_ANOTHER, { controller.dismiss(); controller.chooseFile() })
                DialogButton(Strings.CLOSE, ImportTags.CLOSE, controller::dismiss)
            }
        },
    )
}

@Composable
private fun MessageDialog(title: String, message: String, tag: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag(tag),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { DialogButton(Strings.CLOSE, ImportTags.CLOSE, onClose) },
    )
}

@Composable
internal fun DialogButton(label: String, tag: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(tag)) { Text(label) }
}

private fun sizeText(bytes: Long): String {
    val tenths = (bytes * TENTHS / BYTES_PER_MB).toInt()
    return Strings.megabytes(tenths)
}

private const val BYTES_PER_MB = 1_048_576L
private const val TENTHS = 10
