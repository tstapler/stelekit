// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.ui.placement.NudgeDirection
import dev.stapler.stelekit.ui.placement.NudgeStep
import dev.stapler.stelekit.ui.sketch.label
import dev.stapler.stelekit.ui.sketch.nudgeDescription
import kotlin.math.roundToInt

private val PAD_CELL = 48.dp
private val CROSSHAIR_COLOR = Color(0xFFFF9800)
private const val PERCENT = 100
private const val CROSSHAIR_REACH = 50_000f

/** TalkBack description of the crosshair position, in whole percent of the image. */
fun crosshairDescription(point: NormalizedPoint): String =
    "Crosshair at ${(point.x * PERCENT).roundToInt()} percent across, ${(point.y * PERCENT).roundToInt()} percent down"

/** The crosshair lines over the image; draws nothing when [point] is null. */
@Composable
fun AnnotationCrosshairLayer(
    point: NormalizedPoint?,
    canvasSize: IntSize,
    modifier: Modifier = Modifier,
    view: () -> AnnotationViewTransform = { AnnotationViewTransform.Fit },
) {
    if (point == null || canvasSize == IntSize.Zero) return
    Canvas(modifier.fillMaxSize().semantics { contentDescription = crosshairDescription(point) }) {
        val center = view().toView(point.toScreen(canvasSize))
        val stroke = 2.dp.toPx()
        // The lines run past the canvas box so they span the whole viewport when zoomed.
        drawLine(CROSSHAIR_COLOR, Offset(-CROSSHAIR_REACH, center.y), Offset(CROSSHAIR_REACH, center.y), strokeWidth = stroke)
        drawLine(CROSSHAIR_COLOR, Offset(center.x, -CROSSHAIR_REACH), Offset(center.x, CROSSHAIR_REACH), strokeWidth = stroke)
        drawCircle(CROSSHAIR_COLOR, radius = 10.dp.toPx(), center = center, style = Stroke(width = stroke))
    }
}

/** The mode toggle plus, while the mode is on, the step selector, nudge pad, and Place/Cancel buttons. */
@Composable
fun AnnotationPlacementControls(
    session: AnnotationPlacementSession,
    onToggle: () -> Unit,
    onNudge: (NudgeDirection, NudgeStep) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(NudgeStep.TEN_PIXELS) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (session.active) {
            NudgePad(
                step = step,
                isMove = session.moving != null,
                onStep = { step = it },
                onNudge = { onNudge(it, step) },
                onConfirm = onConfirm,
                onCancel = onCancel,
            )
        }
        FilterChip(
            selected = session.active,
            onClick = onToggle,
            label = { Text("Crosshair") },
            modifier = Modifier.semantics { contentDescription = "Place at crosshair mode" },
        )
    }
}

@Composable
private fun NudgePad(
    step: NudgeStep,
    isMove: Boolean,
    onStep: (NudgeStep) -> Unit,
    onNudge: (NudgeDirection) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NudgeStep.entries.forEach {
                FilterChip(
                    selected = step == it,
                    onClick = { onStep(it) },
                    label = { Text(it.label()) },
                    modifier = Modifier.semantics { contentDescription = "Nudge step ${it.label()}" },
                )
            }
        }
        NudgeButton(NudgeDirection.UP, step, onNudge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            NudgeButton(NudgeDirection.LEFT, step, onNudge)
            Button(onClick = onConfirm, modifier = Modifier.size(width = PAD_CELL * 2, height = PAD_CELL)) {
                Text(if (isMove) "Apply" else "Place point")
            }
            NudgeButton(NudgeDirection.RIGHT, step, onNudge)
        }
        NudgeButton(NudgeDirection.DOWN, step, onNudge)
        TextButton(onClick = onCancel, modifier = Modifier.size(width = PAD_CELL * 2, height = PAD_CELL)) {
            Text(if (isMove) "Cancel move" else "Cancel")
        }
    }
}

@Composable
private fun NudgeButton(direction: NudgeDirection, step: NudgeStep, onNudge: (NudgeDirection) -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = { onNudge(direction) },
        modifier = modifier.size(PAD_CELL).semantics { contentDescription = nudgeDescription(direction, step) },
    ) { Text(direction.arrow()) }
}

private fun NudgeDirection.arrow(): String = when (this) {
    NudgeDirection.LEFT -> "<"
    NudgeDirection.RIGHT -> ">"
    NudgeDirection.UP -> "^"
    NudgeDirection.DOWN -> "v"
}
