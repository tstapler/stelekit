// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.render.SheetStyleDefaults
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEngineStrings

object EngineTags {
    const val LABEL_DIALOG = "sketch.engine.label"
    const val LABEL_FIELD = "sketch.engine.label.field"
    const val LABEL_SAVE = "sketch.engine.label.save"
    const val LABEL_CANCEL = "sketch.engine.label.cancel"
    const val LABEL_REMOVE = "sketch.engine.label.remove"
    const val SHEET_STYLE = "sketch.engine.sheetstyle"
    const val SHEET_CLEAN = "sketch.engine.sheetstyle.clean"
    const val SHEET_SKETCHY = "sketch.engine.sheetstyle.sketchy"
    const val SHEET_KIND = "sketch.engine.sheetkind"
    const val SHEET_DIAGRAM = "sketch.engine.sheetkind.diagram"
    const val SHEET_TECHNICAL = "sketch.engine.sheetkind.technical"
}

private const val TARGET_DP = 44
private const val GAP_DP = 8
private const val FIELD_WIDTH_DP = 240

/** The label of the shape or arrow the user double-clicked or chose from the selection menu; nothing while no label is being edited. */
@Composable
internal fun LabelDialog(engine: EngineSurfaces) {
    val edit = engine.labelEdit ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(edit.target) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = engine::cancelLabel,
        modifier = Modifier.testTag(EngineTags.LABEL_DIALOG),
        title = { Text(if (edit.arrow) SketchEngineStrings.Label.TITLE_ARROW else SketchEngineStrings.Label.TITLE_SHAPE) },
        text = {
            OutlinedTextField(
                value = edit.text,
                onValueChange = engine::typeLabel,
                label = { Text(SketchEngineStrings.Label.FIELD) },
                modifier = Modifier.width(FIELD_WIDTH_DP.dp).focusRequester(focus).testTag(EngineTags.LABEL_FIELD),
            )
        },
        confirmButton = { DialogButton(SketchEngineStrings.Label.SAVE, EngineTags.LABEL_SAVE, engine::commitLabel) },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
                if (edit.hadLabel) DialogButton(SketchEngineStrings.Label.REMOVE, EngineTags.LABEL_REMOVE, engine::removeLabel)
                DialogButton(SketchEngineStrings.Label.CANCEL, EngineTags.LABEL_CANCEL, engine::cancelLabel)
            }
        },
    )
}

/** The Style tab's sheet row: Clean or Sketchy for the shapes this sheet draws from now on (OD-5). */
@Composable
internal fun SheetStyleRow(controller: SketchEditorController, modifier: Modifier = Modifier) {
    val current = controller.sheetStyle.renderStyle
    Row(modifier.padding(horizontal = 16.dp).testTag(EngineTags.SHEET_STYLE), horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(SketchEngineStrings.SheetStyle.TITLE)
        StyleChoice(SketchEngineStrings.SheetStyle.CLEAN, EngineTags.SHEET_CLEAN, current == RenderStyle.Clean, !controller.readOnly) { controller.setSheetStyle(SheetStyleDefaults(RenderStyle.Clean)) }
        StyleChoice(SketchEngineStrings.SheetStyle.SKETCHY, EngineTags.SHEET_SKETCHY, current == RenderStyle.Sketchy, !controller.readOnly) { controller.setSheetStyle(SheetStyleDefaults(RenderStyle.Sketchy)) }
    }
}

/** The Style tab's sheet-type row: a Diagram, or a Technical sheet with the constraint and dimension tools in inches. */
@Composable
internal fun SheetKindRow(controller: SketchEditorController, modifier: Modifier = Modifier) {
    val current = controller.sheetKind
    Row(modifier.padding(horizontal = 16.dp).testTag(EngineTags.SHEET_KIND), horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(SketchEngineStrings.SheetKind.TITLE)
        StyleChoice(SketchEngineStrings.SheetKind.DIAGRAM, EngineTags.SHEET_DIAGRAM, current == SheetKind.Diagram, !controller.readOnly) { controller.setSheetKind(SheetKind.Diagram) }
        StyleChoice(SketchEngineStrings.SheetKind.TECHNICAL, EngineTags.SHEET_TECHNICAL, current == SheetKind.Technical, !controller.readOnly) { controller.setSheetKind(SheetKind.Technical) }
    }
}

@Composable
private fun StyleChoice(label: String, tag: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick, Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(tag), enabled = enabled && !selected) { Text(label) }
}

@Composable
private fun DialogButton(label: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.defaultMinSize(minHeight = TARGET_DP.dp, minWidth = TARGET_DP.dp).testTag(tag)) { Text(label) }
}
