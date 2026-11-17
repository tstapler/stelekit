// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionFailure
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings

object MigrationTags {
    const val FIRST_WRITE_WARNING = "sketch.first-write.warning"
    const val FIRST_WRITE_CHECKBOX = "sketch.first-write.checkbox"
    const val FIRST_WRITE_PRIMARY = "sketch.first-write.primary"
    const val FIRST_WRITE_CLASSIC = "sketch.first-write.classic"
    const val ENABLE_CONFIRM = "sketch.enable.confirm"
    const val ENABLE_CANCEL = "sketch.enable.cancel"
    const val ENABLE_LEARN = "sketch.enable.learn"
    const val FAILURE_COPY = "sketch.failure.copy"
    const val FAILURE_OPEN_LEGACY = "sketch.failure.legacy"
    const val FAILURE_TRY_AGAIN = "sketch.failure.retry"
    const val FAILURE_CLOSE = "sketch.failure.close"
    const val SHEET_TITLE = "sketch.consent-sheet.title"
    const val SHEET_START = "sketch.consent-sheet.start"
    const val SHEET_NOT_NOW = "sketch.consent-sheet.not-now"
    const val SHEET_KEEP_CLASSIC = "sketch.consent-sheet.keep-classic"
    const val SHEET_BODY = "sketch.consent-sheet.body"
}

private val DialogGap = 8.dp

/**
 * The first-write dialog (Task 1.4.6a, UX-64, UX-103). Initial focus is the warning block; the primary button is disabled
 * until the ONE checkbox is checked but stays focusable, with the reason visible under the checkbox and as its description.
 * Esc and back arrive as `onDismissRequest` and mean [FirstWriteAction.Dismiss].
 */
@Composable
fun FirstV2WriteDialog(model: FirstWriteDialogModel, onCheckedChange: (Boolean) -> Unit, onAction: (FirstWriteAction) -> Unit) {
    val warningFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { warningFocus.requestFocus() }
    AlertDialog(
        onDismissRequest = { onAction(FirstWriteAction.Dismiss) },
        title = { Text(model.title, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DialogGap), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(DialogGap),
                    modifier = Modifier
                        .focusRequester(warningFocus)
                        .focusable()
                        .testTag(MigrationTags.FIRST_WRITE_WARNING)
                        .semantics { contentDescription = model.blocks.joinToString(" ") },
                ) {
                    model.blocks.forEach { Text(it) }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .toggleable(model.checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                        .testTag(MigrationTags.FIRST_WRITE_CHECKBOX),
                ) {
                    Checkbox(checked = model.checked, onCheckedChange = null)
                    Text(SketchMigrationStrings.FirstWrite.CHECKBOX, modifier = Modifier.padding(start = DialogGap))
                }
                model.disabledHint?.let { Text(it) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAction(FirstWriteAction.Primary) },
                modifier = Modifier.testTag(MigrationTags.FIRST_WRITE_PRIMARY).semantics {
                    if (!model.canConfirm) {
                        disabled()
                        model.disabledHint?.let { stateDescription = it }
                    }
                },
            ) { Text(model.primaryLabel) }
        },
        dismissButton = {
            TextButton(onClick = { onAction(FirstWriteAction.Classic) }, modifier = Modifier.testTag(MigrationTags.FIRST_WRITE_CLASSIC)) {
                Text(model.classicLabel)
            }
        },
    )
}

/** The flag-enable confirmation: [Cancel] [Turn on] and the "Learn what changes" link (Task 1.4.6c). Cancel stores nothing. */
@Composable
fun EnableV2Confirmation(variant: EnableVariant, onConfirm: () -> Unit, onCancel: () -> Unit, onLearn: () -> Unit) {
    val copy = variant.copy
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(copy.title, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DialogGap), modifier = Modifier.verticalScroll(rememberScrollState())) {
                copy.paragraphs.forEach { Text(it) }
                TextButton(onClick = onLearn, modifier = Modifier.testTag(MigrationTags.ENABLE_LEARN)) {
                    Text(SketchMigrationStrings.Mode.LEARN_WHAT_CHANGES)
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm, modifier = Modifier.testTag(MigrationTags.ENABLE_CONFIRM)) { Text(copy.confirmLabel) } },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(MigrationTags.ENABLE_CANCEL)) { Text(SketchMigrationStrings.Mode.CANCEL) }
        },
    )
}

/** The conversion-failure dialog (UX-66): the original was not changed; the reason in words, then its code. */
@Composable
fun ConversionFailureDialog(
    failure: ConversionFailure,
    onCopyDetails: () -> Unit,
    onOpenLegacy: () -> Unit,
    onTryAgain: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(SketchMigrationStrings.Failure.TITLE, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DialogGap)) {
                Text(SketchMigrationStrings.Failure.BODY)
                Text(SketchMigrationStrings.Failure.reason(failure))
                TextButton(onClick = onCopyDetails, modifier = Modifier.testTag(MigrationTags.FAILURE_COPY)) {
                    Text(SketchMigrationStrings.Failure.COPY_DETAILS)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(DialogGap)) {
                TextButton(onClick = onOpenLegacy, modifier = Modifier.testTag(MigrationTags.FAILURE_OPEN_LEGACY)) {
                    Text(SketchMigrationStrings.Failure.OPEN_LEGACY)
                }
                Button(onClick = onTryAgain, modifier = Modifier.testTag(MigrationTags.FAILURE_TRY_AGAIN)) {
                    Text(SketchMigrationStrings.Failure.TRY_AGAIN)
                }
                TextButton(onClick = onClose, modifier = Modifier.testTag(MigrationTags.FAILURE_CLOSE)) {
                    Text(SketchMigrationStrings.Failure.CLOSE)
                }
            }
        },
    )
}

/**
 * The "Upgrade this sketch?" sheet that replaces the Upgrade chip row where its summary exceeds the row's lines (Task 1.4.6i).
 * Focus moves to the title, which is announced as a dialog; Esc, back, a scrim tap and a drag down all arrive as
 * `onDismissRequest` and mean Not now, like [Not now] itself. The body scrolls, so nothing is clipped at font scale 2.0.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeConsentSheet(onStart: () -> Unit, onNotNow: () -> Unit, onKeepClassic: () -> Unit) {
    val titleFocus = remember { FocusRequester() }
    // The sheet's content is attached a frame after it enters the composition, so a request made at once finds no target.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        titleFocus.requestFocus()
    }
    ModalBottomSheet(onDismissRequest = onNotNow, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(DialogGap),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(
                SketchMigrationStrings.ConsentSheet.TITLE,
                modifier = Modifier
                    .focusRequester(titleFocus)
                    .focusable()
                    .testTag(MigrationTags.SHEET_TITLE)
                    .semantics {
                        heading()
                        contentDescription = SketchMigrationStrings.ConsentSheet.ANNOUNCEMENT
                    },
            )
            Text(SketchMigrationStrings.Banners.UPGRADE_SUMMARY)
            Text(SketchConsentCopy.CHIP_DETAILS, modifier = Modifier.testTag(MigrationTags.SHEET_BODY))
            Button(onClick = onStart, modifier = Modifier.testTag(MigrationTags.SHEET_START)) { Text(SketchMigrationStrings.ConsentSheet.START) }
            TextButton(onClick = onNotNow, modifier = Modifier.testTag(MigrationTags.SHEET_NOT_NOW)) {
                Text(SketchMigrationStrings.ConsentSheet.NOT_NOW)
            }
            TextButton(onClick = onKeepClassic, modifier = Modifier.testTag(MigrationTags.SHEET_KEEP_CLASSIC)) {
                Text(SketchMigrationStrings.ConsentSheet.KEEP_CLASSIC)
            }
        }
    }
}
