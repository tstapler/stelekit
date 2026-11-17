// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

object CrosshairTags {
    const val PAD = "sketch.crosshair.pad"
    const val MARKER = "sketch.crosshair.marker"
    const val PLACE = "sketch.crosshair.place"
    const val EXIT = "sketch.crosshair.exit"
    const val UP = "sketch.crosshair.up"
    const val DOWN = "sketch.crosshair.down"
    const val LEFT = "sketch.crosshair.left"
    const val RIGHT = "sketch.crosshair.right"
}

private const val PAD_BOTTOM_DP = 56
private const val ARM_DP = 10f
private const val GAP_DP = 4

/**
 * Crosshair mode over the canvas: the marker at the crosshair, the text overlay of a Text tool placed by the keyboard, and the
 * pad whose buttons do what the keys do (the tab order's "crosshair pad" stop). Draws nothing while Crosshair is off.
 */
@Composable
internal fun BoxScope.CrosshairLayer(access: EditorKeyboardAccess, viewport: SketchViewport, regions: RegionFocus?) {
    val point = access.point ?: return
    CrosshairMarker(point, viewport)
    access.textSession?.let { TextEditorOverlay(it, viewport, hasHardwareKeyboard = true, onCommit = access::commitText) }
    val padModifier = Modifier.align(Alignment.BottomCenter).padding(bottom = PAD_BOTTOM_DP.dp).let { base -> if (regions != null) base.region(regions, TabStop.CrosshairPad) else base }
    CrosshairPad(access, padModifier)
}

@Composable
private fun CrosshairMarker(point: ScenePoint, viewport: SketchViewport) {
    val inner = MaterialTheme.colorScheme.onSurface
    val outer = MaterialTheme.colorScheme.inverseOnSurface
    val at = viewport.toScreen(point)
    Canvas(Modifier.fillMaxSize().testTag(CrosshairTags.MARKER)) {
        val arm = ARM_DP.dp.toPx()
        val centre = Offset(at.x.toFloat(), at.y.toFloat())
        for ((colour, width) in listOf(outer to OUTER_STROKE_DP, inner to INNER_STROKE_DP)) {
            val stroke = width.dp.toPx()
            drawLine(colour, centre - Offset(arm, 0f), centre + Offset(arm, 0f), stroke)
            drawLine(colour, centre - Offset(0f, arm), centre + Offset(0f, arm), stroke)
        }
    }
}

private const val OUTER_STROKE_DP = 4
private const val INNER_STROKE_DP = 2

@Composable
private fun CrosshairPad(access: EditorKeyboardAccess, modifier: Modifier = Modifier) {
    Surface(modifier.testTag(CrosshairTags.PAD).semantics { contentDescription = SketchStrings.KeyboardDrawing.PAD }, tonalElevation = 3.dp) {
        Column(Modifier.padding(GAP_DP.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
            PadButton(CrosshairTags.UP, SketchStrings.NonDrag.NUDGE_UP) { access.press(CrosshairKey.Move(0, -1, far = false)) }
            Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
                PadButton(CrosshairTags.LEFT, SketchStrings.NonDrag.NUDGE_LEFT) { access.press(CrosshairKey.Move(-1, 0, far = false)) }
                PadButton(CrosshairTags.PLACE, SketchStrings.KeyboardDrawing.PLACE) { access.press(CrosshairKey.Enter) }
                PadButton(CrosshairTags.RIGHT, SketchStrings.NonDrag.NUDGE_RIGHT) { access.press(CrosshairKey.Move(1, 0, far = false)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp)) {
                PadButton(CrosshairTags.DOWN, SketchStrings.NonDrag.NUDGE_DOWN) { access.press(CrosshairKey.Move(0, 1, far = false)) }
                PadButton(CrosshairTags.EXIT, SketchStrings.KeyboardDrawing.EXIT, access::exit)
            }
        }
    }
}

@Composable
private fun PadButton(tag: String, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(tag)) { Text(label) }
}
