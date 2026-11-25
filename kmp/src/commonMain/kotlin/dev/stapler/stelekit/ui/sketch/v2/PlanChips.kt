// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.sketch.v2.commands.FlipOpening
import dev.stapler.stelekit.sketch.v2.commands.SetWallProperties
import dev.stapler.stelekit.sketch.v2.commands.SetWallSegmentLength
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallJustification
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.tools.PlanMode
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings.Plan as P

object PlanTags {
    const val DIALOG = "sketch.plan.length"
    const val FIELD = "sketch.plan.length.field"
    const val SET = "sketch.plan.length.set"
    const val CANCEL = "sketch.plan.length.cancel"
    const val ERROR = "sketch.plan.length.error"
    const val CHIP_DONE = "sketch.plan.done"
    const val CHIP_CLOSE = "sketch.plan.close"
    const val CHIP_LENGTH = "sketch.plan.chip.length"
    const val CHIP_LAST_LENGTH = "sketch.plan.chip.lastlength"
    const val CHIP_FLIP_SWING = "sketch.plan.flip.swing"
    const val CHIP_FLIP_HINGE = "sketch.plan.flip.hinge"
    const val CHIP_JUSTIFY = "sketch.plan.justify"
    fun mode(mode: PlanMode) = "sketch.plan.mode.${mode.name.lowercase()}"
}

private fun PlanMode.label(): String = when (this) {
    PlanMode.Line -> P.MODE_LINE
    PlanMode.Wall -> P.MODE_WALL
    PlanMode.Door -> P.MODE_DOOR
    PlanMode.Window -> P.MODE_WINDOW
    PlanMode.Opening -> P.MODE_OPENING
}

/**
 * What the Line tool can draw on a floor plan, as chips: plain lines, a chain of walls, or a door, window or opening placed on a
 * wall. While a wall chain is being drawn the row also offers Done, Close room (three or more vertices) and Length, which opens the
 * keypad for the exact length of the next wall. The Line tool and a technical sheet only; nothing for a read-only sheet.
 */
internal fun planChips(controller: SketchEditorController): List<ActionChip> {
    if (controller.readOnly) return emptyList()
    if (controller.ui.activeTool == ToolId.Select) return (if (controller.sheetKind == SheetKind.Technical) selectionChips(controller) else emptyList()) + rotateChip(controller)
    if (controller.sheetKind != SheetKind.Technical) return emptyList()
    if (controller.ui.activeTool != ToolId.Line) return emptyList()
    val tools = controller.tools
    val modes = PlanMode.entries.map { mode ->
        val on = tools.planMode == mode
        ActionChip(PlanTags.mode(mode), if (on) P.selected(mode.label()) else mode.label(), P.modeDescription(mode.label(), on)) { tools.choosePlanMode(mode) }
    }
    val placed = tools.wallVertexCount
    if (tools.planMode != PlanMode.Wall || placed == 0) return modes
    return modes + listOfNotNull(
        ActionChip(PlanTags.CHIP_LENGTH, P.LENGTH, P.LENGTH_DESCRIPTION) { controller.overlay = EditorOverlay.WallLength },
        ActionChip(PlanTags.CHIP_DONE, P.DONE, P.DONE_DESCRIPTION) { tools.finishWalls() },
        if (placed >= PlanLimits.CLOSE_FROM) ActionChip(PlanTags.CHIP_CLOSE, P.CLOSE_ROOM, P.CLOSE_ROOM_DESCRIPTION) { tools.closeRoom() } else null,
    )
}

/** "Rotate" for a selection of one or more elements: opens the angle dialog (typed degrees, quick steps, pivot). */
private fun rotateChip(controller: SketchEditorController): List<ActionChip> {
    val scene = controller.doc.scene
    val turnable = controller.ui.selection.mapNotNull { scene[ElementId(it)] }.any { it !is OpeningElement && dev.stapler.stelekit.sketch.v2.tools.Selectability.isSelectable(scene, it) }
    if (!turnable) return emptyList()
    val strings = dev.stapler.stelekit.ui.sketch.v2.strings.SketchHostStrings.Rotate
    return listOf(ActionChip(RotateTags.CHIP, strings.CHIP, strings.CHIP_DESCRIPTION) { controller.overlay = EditorOverlay.Rotate })
}

/** Edits of a selected opening (flip its swing or hinge) or wall (cycle justification, type the last segment's length). */
private fun selectionChips(controller: SketchEditorController): List<ActionChip> {
    val id = controller.ui.selection.singleOrNull()?.let(::ElementId) ?: return emptyList()
    return when (val e = controller.doc.scene[id]) {
        is OpeningElement -> listOf(
            ActionChip(PlanTags.CHIP_FLIP_SWING, P.FLIP_SWING, P.FLIP_SWING_DESCRIPTION) { controller.submit(FlipOpening(id)) },
            ActionChip(PlanTags.CHIP_FLIP_HINGE, P.FLIP_HINGE, P.FLIP_HINGE_DESCRIPTION) { controller.submit(FlipOpening(id, swing = false, hinge = true)) },
        )
        is WallElement -> listOf(
            ActionChip(PlanTags.CHIP_JUSTIFY, P.justify(e.justification.name), P.JUSTIFY_DESCRIPTION) {
                val next = WallJustification.entries[(e.justification.ordinal + 1) % WallJustification.entries.size]
                controller.submit(SetWallProperties(id, justification = next))
            },
            ActionChip(PlanTags.CHIP_LAST_LENGTH, P.LAST_LENGTH, P.LAST_LENGTH_DESCRIPTION) { controller.overlay = EditorOverlay.WallLength },
        )
        else -> emptyList()
    }
}

internal object PlanLimits {
    /** A chain closes into a room from this many vertices. */
    const val CLOSE_FROM = 3
}

/** The exact length of the next wall: the field takes the active unit (feet and inches, or metric), the keypad types its marks. */
@Composable
internal fun WallLengthDialog(controller: SketchEditorController, extras: EditorExtras) {
    val format = extras.lengthUnit.format
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var problem: String? by remember { mutableStateOf(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val close = { controller.overlay = null }
    val apply = {
        DimensionValueParser.parse(ConstraintKind.Distance, field.text, technical = true, format = format).fold(
            { message -> problem = message },
            { units ->
                val selected = controller.ui.selection.singleOrNull()?.let { controller.doc.scene[ElementId(it)] } as? WallElement
                if (selected != null) controller.submit(SetWallSegmentLength(selected.id, selected.segmentCount - 1, units)) else controller.tools.typeWallLength(units)
                close()
            },
        )
    }
    AlertDialog(
        onDismissRequest = close,
        modifier = Modifier.testTag(PlanTags.DIALOG),
        title = { Text(P.LENGTH_TITLE) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    field,
                    { field = it; problem = null },
                    Modifier.fillMaxWidth().focusRequester(focus).testTag(PlanTags.FIELD),
                    label = { Text(P.LENGTH_LABEL) },
                    singleLine = true,
                    isError = problem != null,
                )
                problem?.let { Text(it, Modifier.testTag(PlanTags.ERROR)) }
                LengthKeyRow(
                    onKey = { key -> field = field.typed(LengthKeyInsertion.apply(CaretText(field.text, field.selection.start, field.selection.end), key)) },
                    format = format,
                    onMeasureKey = { key -> field = field.typed(LengthKeyInsertion.apply(CaretText(field.text, field.selection.start, field.selection.end), key)) },
                )
            }
        },
        confirmButton = { TextButton(apply, Modifier.testTag(PlanTags.SET)) { Text(P.LENGTH_SET) } },
        dismissButton = { TextButton(close, Modifier.testTag(PlanTags.CANCEL)) { Text(P.LENGTH_CANCEL) } },
    )
}

private fun TextFieldValue.typed(result: CaretResult) = copy(text = result.text, selection = TextRange(result.caret))
