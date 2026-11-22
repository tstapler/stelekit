// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.tools.HandleTier
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchChromeStrings
import dev.stapler.stelekit.sketch.v2.tools.Handle
import dev.stapler.stelekit.sketch.v2.tools.HandleLayout
import dev.stapler.stelekit.sketch.v2.tools.SelectionFrame
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.math.PI
import kotlin.math.roundToInt

/** The one status line shown when the selection is too small for handles (UX-127). */
const val HANDLES_HINT = "Zoom in to use handles"

/** What the selection bar's Transform menu offers; Resize, Rotate and Move are the handle-free paths of UX-127. */
enum class TransformAction(val label: String) {
    Move("Move"), Resize("Resize"), Rotate("Rotate"), FlipHorizontal("Flip horizontal"), FlipVertical("Flip vertical"),
}

private fun handleLabel(h: Handle): String = when (h) {
    Handle.NW -> "Resize handle, top left"
    Handle.N -> "Resize handle, top"
    Handle.NE -> "Resize handle, top right"
    Handle.E -> "Resize handle, right"
    Handle.SE -> "Resize handle, bottom right"
    Handle.S -> "Resize handle, bottom"
    Handle.SW -> "Resize handle, bottom left"
    Handle.W -> "Resize handle, left"
    Handle.Rotate -> "Rotation handle"
}

/**
 * The selection's outline and handles over the canvas (Task 3.2.2c, 3.2.2d). The outline is drawn rotated with the
 * element; each shown handle is a node whose size is its hit area (the touch-target floor), so what a screen reader and a
 * test see is what the Select tool hit-tests. Nothing here takes pointer input: the canvas owns the gesture. Below the
 * smallest tier no handle is shown and [HANDLES_HINT] is, with the Transform menu as the alternative.
 */
@Composable
fun SelectionOverlay(
    frame: SelectionFrame?,
    layout: HandleLayout?,
    viewport: SketchViewport,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    onZoomToSelection: (() -> Unit)? = null,
    vertices: List<ScenePoint> = emptyList(),
) {
    if (frame == null) return
    val density = LocalDensity.current
    Box(modifier.fillMaxSize().testTag("selection-overlay")) {
        Canvas(Modifier.fillMaxSize()) {
            val topLeft = viewport.toScreen(point(frame.box.x, frame.box.y))
            val size = Size((frame.box.width * viewport.zoom).toFloat(), (frame.box.height * viewport.zoom).toFloat())
            val pivot = Offset((topLeft.x + size.width / 2.0).toFloat(), (topLeft.y + size.height / 2.0).toFloat())
            rotate((frame.angle * DEGREES_PER_RADIAN_NUMERATOR / PI).toFloat(), pivot) {
                drawRect(color, Offset(topLeft.x.toFloat(), topLeft.y.toFloat()), size, style = Stroke(width = OUTLINE_DP.dp.toPx()))
            }
            // A selected line's vertices are round grab points (a press on one drags only that point), unlike the square box handles.
            vertices.forEach { v ->
                val c = viewport.toScreen(v)
                val r = VERTEX_DP.dp.toPx()
                drawCircle(Color.White, r, Offset(c.x.toFloat(), c.y.toFloat()))
                drawCircle(color, r, Offset(c.x.toFloat(), c.y.toFloat()), style = Stroke(width = OUTLINE_DP.dp.toPx() * 1.5f))
            }
            layout?.handles?.forEach { h ->
                val c = viewport.toScreen(h.centre)
                val half = HANDLE_VISIBLE_DP.dp.toPx() / 2f
                drawRect(Color.White, Offset(c.x.toFloat() - half, c.y.toFloat() - half), Size(half * 2f, half * 2f))
                drawRect(color, Offset(c.x.toFloat() - half, c.y.toFloat() - half), Size(half * 2f, half * 2f), style = Stroke(width = OUTLINE_DP.dp.toPx()))
            }
        }
        layout?.handles?.forEach { h ->
            val c = viewport.toScreen(h.hitCentre)
            val hitPx = (h.halfHit * 2.0 * viewport.zoom).toFloat()
            Box(
                Modifier
                    .offset { IntOffset((c.x - hitPx / 2.0).roundToInt(), (c.y - hitPx / 2.0).roundToInt()) }
                    .size(with(density) { hitPx.toDp() })
                    .testTag("handle-${h.handle.name}")
                    .semantics { contentDescription = handleLabel(h.handle) },
            )
        }
        val small = layout != null && (layout.needsTransformMenu || layout.tier == HandleTier.Compact)
        // Only where it helps: a line's vertices are grab points already, and there is nothing to zoom into at the maximum zoom.
        val zoomWouldHelp = vertices.isEmpty() && viewport.zoom < viewport.maxZoom - ZOOM_EPS
        if (small && zoomWouldHelp && minOf(frame.box.width, frame.box.height) > 0.0) {
            // At the end, above the view cluster: the start side is where the host's action chips (Free draw, Tool lock) sit.
            HandlesChip(onZoomToSelection, Modifier.align(Alignment.BottomEnd).padding(end = CHIP_END, bottom = CHIP_BOTTOM))
        }
    }
}

/**
 * The one notice for a selection too small to resize comfortably: with [onZoom] it is a "Zoom to selection" action chip (the
 * corners still work, this makes them easy), without it the old plain line. It sits at the bottom, clear of the hint and the name tags.
 */
@Composable
private fun HandlesChip(onZoom: (() -> Unit)?, modifier: Modifier = Modifier) {
    val label = if (onZoom != null) SketchChromeStrings.ZOOM_TO_SELECTION else HANDLES_HINT
    Surface(
        modifier.testTag("handles-hint").defaultMinSize(minHeight = BAR_BUTTON_DP.dp).clip(RoundedCornerShape(CHIP_RADIUS))
            .then(if (onZoom != null) Modifier.clickable(onClick = onZoom) else Modifier),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(CHIP_RADIUS),
    ) {
        Box(Modifier.padding(horizontal = CHIP_PAD), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
    }
}

private val CHIP_BOTTOM = 60.dp
private val CHIP_RADIUS = 24.dp
private val CHIP_PAD = 16.dp
private val CHIP_END = 16.dp

private fun point(x: Double, y: Double) = ScenePoint(SceneUnit.of(x).getOrNull() ?: SceneUnit.ZERO, SceneUnit.of(y).getOrNull() ?: SceneUnit.ZERO)

/**
 * The selection bar (one of the three transient rows of UX-119): count, Duplicate, Delete and the Transform menu that
 * keeps Move, Resize, Rotate and Flip reachable when no handle fits (UX-127). Every control is at least 48 dp tall.
 */
@Composable
fun SelectionBar(
    count: Int,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onTransform: (TransformAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.testTag("selection-bar"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (count == 1) "1 selected" else "$count selected", Modifier.testTag("selection-count"))
        TextButton(onDuplicate, Modifier.defaultMinSize(minHeight = BAR_BUTTON_DP.dp).testTag("selection-duplicate")) { Text("Duplicate") }
        TextButton(onDelete, Modifier.defaultMinSize(minHeight = BAR_BUTTON_DP.dp).testTag("selection-delete")) { Text("Delete") }
        Box {
            TextButton({ open = true }, Modifier.defaultMinSize(minHeight = BAR_BUTTON_DP.dp).testTag("selection-transform")) { Text("Transform") }
            DropdownMenu(open, { open = false }) {
                TransformAction.entries.forEach { a ->
                    DropdownMenuItem(
                        text = { Text(a.label) },
                        onClick = {
                            open = false
                            onTransform(a)
                        },
                        modifier = Modifier.testTag("transform-${a.name}"),
                    )
                }
            }
        }
    }
}

private const val DEGREES_PER_RADIAN_NUMERATOR = 180.0
private const val OUTLINE_DP = 1.5f
private const val HANDLE_VISIBLE_DP = 8f
private const val VERTEX_DP = 6f
private const val ZOOM_EPS = 1e-6
private const val BAR_BUTTON_DP = 48
