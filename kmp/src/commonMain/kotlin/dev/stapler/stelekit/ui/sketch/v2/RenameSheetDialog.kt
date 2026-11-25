// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.stapler.stelekit.sketch.v2.commands.SheetNameError
import dev.stapler.stelekit.sketch.v2.commands.SheetNameExtras
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings.RenameSheet as R

object RenameSheetTags {
    const val DIALOG = "sketch.rename.dialog"
    const val FIELD = "sketch.rename.field"
    const val SAVE = "sketch.rename.save"
    const val CANCEL = "sketch.rename.cancel"
    const val ERROR = "sketch.rename.error"
    const val OVERFLOW = "sketch.overflow.rename"
}

private const val BUTTON_MIN_DP = 48
private const val PAD_DP = 24
private const val ELEVATION_DP = 6

/**
 * Names the sheet: the field starts with the sheet's own name (empty for an unnamed sheet, which shows the sketch's name as a
 * placeholder, so Save without typing never copies it); Save is refused while the name is blank or too long.
 */
@Composable
internal fun RenameSheetDialog(controller: SketchEditorController, placeholder: String) {
    Dialog(onDismissRequest = { controller.overlay = null }) { RenameSheetDialogContent(controller, placeholder, autoFocus = true) }
}

/** The dialog's card, apart from its window so a test can compose it directly. */
@Composable
internal fun RenameSheetDialogContent(controller: SketchEditorController, placeholder: String, autoFocus: Boolean) {
    var text by remember { mutableStateOf(controller.sheetName.orEmpty()) }
    val limit = controller.doc.scene.limits.maxNameChars
    val verdict = SheetNameExtras.validate(text, controller.doc.scene.limits)
    // A blank field is not an error until the user tries to save it, so an empty start is not shown in red.
    var tried by remember { mutableStateOf(false) }
    val problem = verdict.leftOrNull()?.takeIf { it is SheetNameError.TooLong || tried }
    val focus = remember { FocusRequester() }
    // A focused field blinks its cursor forever, which keeps a Robolectric test from ever idling; the test turns this off.
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    val close = { controller.overlay = null }
    val save = {
        tried = true
        if (controller.renameSheet(text).isRight()) close()
    }
    Surface(Modifier.testTag(RenameSheetTags.DIALOG), shape = MaterialTheme.shapes.extraLarge, tonalElevation = ELEVATION_DP.dp) {
        Column(Modifier.padding(PAD_DP.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(R.TITLE, style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                text,
                { text = it },
                Modifier.fillMaxWidth().focusRequester(focus).testTag(RenameSheetTags.FIELD),
                label = { Text(R.FIELD) },
                placeholder = { Text(placeholder, maxLines = 1) },
                singleLine = true,
                isError = problem != null,
                supportingText = { Text(R.count(SheetNameExtras.normalize(text).length, limit)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
            problem?.let { Text(message(it), Modifier.testTag(RenameSheetTags.ERROR), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(close, Modifier.defaultMinSize(minHeight = BUTTON_MIN_DP.dp).testTag(RenameSheetTags.CANCEL)) { Text(R.CANCEL) }
                TextButton(save, Modifier.defaultMinSize(minHeight = BUTTON_MIN_DP.dp).testTag(RenameSheetTags.SAVE), enabled = verdict.isRight()) { Text(R.SAVE) }
            }
        }
    }
}

private fun message(error: SheetNameError): String = when (error) {
    SheetNameError.Blank -> R.BLANK
    is SheetNameError.TooLong -> R.tooLong(error.limit)
}
