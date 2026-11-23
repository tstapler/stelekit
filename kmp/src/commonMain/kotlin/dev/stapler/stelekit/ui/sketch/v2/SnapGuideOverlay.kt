// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.SnapKind
import dev.stapler.stelekit.sketch.v2.snap.GuideAxis
import dev.stapler.stelekit.sketch.v2.snap.SnapGuide
import dev.stapler.stelekit.sketch.v2.snap.SnapTicker
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.time.TimeSource

object SnapGuideTags {
    const val OVERLAY = "snap-guides"
}

/**
 * The guide lines of the snap a move or resize drag has engaged, over the canvas: a thin line along each aligned edge or centre
 * with a small marker at each end, in the theme's tertiary colour (the selection uses the primary one). It takes no pointer input
 * and is invisible to screen readers (the Snap toggle announces its own state); with [haptics] a light tick marks each new
 * snap target, debounced by [SnapTicker]. Draws nothing, and ticks nothing, when [guides] is empty.
 */
@Composable
fun SnapGuideOverlay(guides: List<SnapGuide>, viewport: SketchViewport, haptics: Boolean, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val ticker = remember { SnapTicker() }
    val clock = remember { TimeSource.Monotonic.markNow() }
    LaunchedEffect(guides) {
        val tick = ticker.onGuides(guides, clock.elapsedNow().inWholeMilliseconds)
        if (tick && haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    if (guides.isEmpty()) return
    val color = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.fillMaxSize().clearAndSetSemantics { testTag = SnapGuideTags.OVERLAY }) {
        val line = Stroke(width = LINE_DP.dp.toPx())
        val marker = MARKER_DP.dp.toPx()
        for (guide in guides) {
            val a = screenOf(viewport, guide, guide.from)
            val b = screenOf(viewport, guide, guide.to)
            val alpha = if (guide.kind == SnapKind.GRID) GRID_ALPHA else 1f
            val ink = color.copy(alpha = alpha)
            drawLine(ink, a, b, strokeWidth = line.width)
            drawCircle(ink, marker, a)
            drawCircle(ink, marker, b)
        }
    }
}

/** The screen position of the point [along] a guide's run: x is the guide's position for a vertical guide, y for a horizontal one. */
private fun screenOf(viewport: SketchViewport, guide: SnapGuide, along: Double): Offset {
    val (x, y) = if (guide.axis == GuideAxis.Vertical) guide.position to along else along to guide.position
    return Offset((x * viewport.zoom + viewport.panX).toFloat(), (y * viewport.zoom + viewport.panY).toFloat())
}

private const val LINE_DP = 1
private const val MARKER_DP = 3
private const val GRID_ALPHA = 0.55f
