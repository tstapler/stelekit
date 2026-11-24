// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.scene.CalibrationNote
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.UnderlayState
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchUnderlayStrings
import kotlin.math.roundToInt

object UnderlayTags {
    const val BAR = "sketch.underlay.bar"
    const val NOTE = "sketch.underlay.note"
    const val LOCK = "sketch.underlay.lock"
    const val ADJUST = "sketch.underlay.adjust"
    const val CALIBRATE = "sketch.underlay.calibrate"
    const val CHECK = "sketch.underlay.check"
    const val REMOVE = "sketch.underlay.remove"
    const val DISTANCE = "sketch.underlay.distance"
    const val APPLY = "sketch.underlay.apply"
    const val CANCEL = "sketch.underlay.cancel"
    const val DONE = "sketch.underlay.done"
    const val LEVEL = "sketch.underlay.level"
    const val ERROR = "sketch.underlay.error"
    const val REF_LABEL = "sketch.underlay.reflabel"
    fun opacity(step: Double) = "sketch.underlay.opacity.${UnderlayOpacitySteps.percent(step)}"
}

private const val BAR_PAD_DP = 8

/** Clears the shell's own hint chip at the top left of the canvas. */
private const val BAR_TOP_DP = 40
private const val GAP_DP = 4
private const val TARGET_DP = 44
private const val REF_STROKE_DP = 2
private const val REF_END_DP = 4
private const val LABEL_RAISE_DP = 18
private const val REF_ALPHA = 0.9f
private val REF_COLOR = Color(0xFFE8590C)

/** The chip row entry that adds a photo underlay; null when the sheet has one already or cannot pick a file. */
fun UnderlayController.chip(): ActionChip? =
    if (state != null || !canPick) null
    else ActionChip(UnderlayTags.CALIBRATE + ".add", SketchUnderlayStrings.CHIP, SketchUnderlayStrings.CHIP_NAME, onClick = ::choosePhoto)

/**
 * The photo underlay's controls over the canvas (epic E5): the calibration note, opacity steps, Lock, Move, Calibrate and Remove;
 * during a calibration the real-distance field with the length keys; during a move a hint and Done. Draws nothing without an underlay.
 */
@Composable
internal fun BoxScope.UnderlayBar(underlay: UnderlayController, modifier: Modifier = Modifier) {
    val state = underlay.state ?: return
    Surface(
        modifier.align(Alignment.TopCenter).padding(start = BAR_PAD_DP.dp, end = BAR_PAD_DP.dp, top = BAR_TOP_DP.dp).fillMaxWidth().testTag(UnderlayTags.BAR)
            .semantics { contentDescription = SketchUnderlayStrings.BAR_REGION },
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = BAR_PAD_DP.dp, vertical = GAP_DP.dp), verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            NoteRow(underlay, state)
            if (underlay.readOnly) return@Column
            when (underlay.mode) {
                UnderlayMode.Idle -> IdleControls(underlay, state)
                UnderlayMode.Adjusting -> HintRow(if (state.calibrated) SketchUnderlayStrings.ADJUST_HINT_CALIBRATED else SketchUnderlayStrings.ADJUST_HINT, underlay)
                UnderlayMode.Calibrating -> if (underlay.pending == null) {
                    HintRow(if (underlay.addingReference) SketchUnderlayStrings.CHECK_HINT else SketchUnderlayStrings.CALIBRATE_HINT, underlay, cancel = true)
                } else {
                    DistanceEntry(underlay)
                }
            }
        }
    }
}

@Composable
private fun NoteRow(underlay: UnderlayController, state: UnderlayState) {
    val muted = CalibrationNote.of(state) !is CalibrationNote.Calibrated
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            underlay.noteText(),
            Modifier.weight(1f).testTag(UnderlayTags.NOTE).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (!underlay.readOnly) TextButton(underlay::remove, Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(UnderlayTags.REMOVE)) { Text(SketchUnderlayStrings.REMOVE) }
    }
}

@Composable
private fun IdleControls(underlay: UnderlayController, state: UnderlayState) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp), verticalArrangement = Arrangement.spacedBy(GAP_DP.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        val current = UnderlayOpacitySteps.nearest(state.layer.opacity)
        for (step in UnderlayOpacitySteps.ALL) {
            val label = "${UnderlayOpacitySteps.percent(step)}%"
            val modifier = Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(UnderlayTags.opacity(step)).semantics { contentDescription = "${SketchUnderlayStrings.OPACITY_NAME} $label" }
            if (step == current) FilledTonalButton({ underlay.setOpacity(step) }, modifier) { Text(label) } else TextButton({ underlay.setOpacity(step) }, modifier) { Text(label) }
        }
        TextButton(
            { underlay.setLocked(!state.locked) },
            Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(UnderlayTags.LOCK).semantics { contentDescription = if (state.locked) SketchUnderlayStrings.UNLOCK_NAME else SketchUnderlayStrings.LOCK_NAME },
        ) { Text(if (state.locked) SketchUnderlayStrings.UNLOCK else SketchUnderlayStrings.LOCK) }
        TextButton(
            underlay::startAdjust,
            Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(UnderlayTags.ADJUST).semantics { contentDescription = if (state.locked) SketchUnderlayStrings.ADJUST_LOCKED else SketchUnderlayStrings.ADJUST_NAME },
            enabled = !state.locked,
        ) { Text(SketchUnderlayStrings.ADJUST) }
        TextButton({ underlay.startCalibrate() }, Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(UnderlayTags.CALIBRATE)) {
            Text(if (state.calibrated) SketchUnderlayStrings.RECALIBRATE else SketchUnderlayStrings.CALIBRATE)
        }
        if (state.calibrated) {
            TextButton(
                { underlay.startCalibrate(addReference = true) },
                Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(UnderlayTags.CHECK).semantics { contentDescription = SketchUnderlayStrings.CHECK_NAME },
            ) { Text(SketchUnderlayStrings.CHECK) }
        }
    }
}

@Composable
private fun HintRow(text: String, underlay: UnderlayController, cancel: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(underlay::endMode, Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(if (cancel) UnderlayTags.CANCEL else UnderlayTags.DONE)) {
            Text(if (cancel) SketchUnderlayStrings.CANCEL else SketchUnderlayStrings.DONE)
        }
    }
}

@Composable
private fun DistanceEntry(underlay: UnderlayController) {
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it },
            label = { Text(SketchUnderlayStrings.DISTANCE_LABEL) },
            singleLine = true,
            isError = underlay.error != null,
            supportingText = { underlay.error?.let { Text(it, Modifier.testTag(UnderlayTags.ERROR).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { underlay.apply(field.text) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(UnderlayTags.DISTANCE),
        )
        LengthKeyRow(onKey = { key ->
            val typed = LengthKeyInsertion.apply(CaretText(field.text, field.selection.start, field.selection.end), key)
            field = TextFieldValue(typed.text, androidx.compose.ui.text.TextRange(typed.caret))
        })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(underlay.level, { underlay.level = it }, Modifier.testTag(UnderlayTags.LEVEL))
            Text(SketchUnderlayStrings.LEVEL, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(underlay::endMode, Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(UnderlayTags.CANCEL)) { Text(SketchUnderlayStrings.CANCEL) }
            FilledTonalButton({ underlay.apply(field.text) }, Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(UnderlayTags.APPLY)) { Text(SketchUnderlayStrings.APPLY) }
        }
    }
}

/**
 * The reference segment of each calibration drawn on the photo with its real length, so the note is visible where the scale came
 * from. It follows the photo through zoom and pan and is hidden while the underlay layer is hidden.
 */
@Composable
internal fun BoxScope.UnderlayReferences(controller: SketchEditorController, underlay: UnderlayController) {
    val state = underlay.state ?: return
    if (!state.layer.visible || state.meta.refs.isEmpty()) return
    val viewport = controller.ui.viewport
    val density = LocalDensity.current.density
    val segments = state.meta.refs.map { ref ->
        val a = state.placement.toScene(ref.a, state.meta.widthPx, state.meta.heightPx)
        val b = state.placement.toScene(ref.b, state.meta.widthPx, state.meta.heightPx)
        Triple(viewport.toScreen(point(a)), viewport.toScreen(point(b)), UnderlayNoteText.length(ref.lengthUnits, underlay.lengthFormat))
    }
    Canvas(Modifier.fillMaxSize()) {
        for ((a, b, _) in segments) {
            val from = Offset(a.x.toFloat(), a.y.toFloat())
            val to = Offset(b.x.toFloat(), b.y.toFloat())
            drawLine(REF_COLOR.copy(alpha = REF_ALPHA), from, to, strokeWidth = REF_STROKE_DP * density)
            drawCircle(REF_COLOR.copy(alpha = REF_ALPHA), REF_END_DP * density, from, style = Stroke(REF_STROKE_DP * density))
            drawCircle(REF_COLOR.copy(alpha = REF_ALPHA), REF_END_DP * density, to, style = Stroke(REF_STROKE_DP * density))
        }
    }
    for ((a, b, length) in segments) {
        val x = ((a.x + b.x) / 2).roundToInt()
        val y = ((a.y + b.y) / 2 - LABEL_RAISE_DP * density).roundToInt()
        Box(Modifier.offset { IntOffset(x, y) }) {
            Text(length, Modifier.testTag(UnderlayTags.REF_LABEL), color = REF_COLOR, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun point(p: Pair<Double, Double>) = ScenePoint(SceneUnit.of(p.first).getOrNull() ?: SceneUnit.ZERO, SceneUnit.of(p.second).getOrNull() ?: SceneUnit.ZERO)
