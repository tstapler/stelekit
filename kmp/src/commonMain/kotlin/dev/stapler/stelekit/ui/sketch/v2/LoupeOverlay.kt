// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import kotlin.math.roundToInt

/**
 * The touch loupe's live state: which touch the tool is following and where. The bubble reads [state] in the layout and draw
 * phases only, so a drag moves it without recomposing the scene inside it.
 */
class LoupeTracker {
    var state by mutableStateOf(LoupeState())
        private set

    /** Folds the tool input [event] (scene space) into the state under [viewport]. */
    fun onInput(event: InputEvent, viewport: SketchViewport, pxPerDp: Double) {
        state = LoupeSpec.reduce(state, event, viewport.toScreen(event.point), pxPerDp)
    }

    /** The view moved (a pan or a pinch), which is never a precision gesture. */
    fun onViewChange() {
        if (state.phase != LoupePhase.Hidden) state = LoupeState()
    }

    internal fun holdElapsed(pointerId: Int) {
        state = LoupeSpec.holdElapsed(state, pointerId)
    }
}

/**
 * A magnifier bubble beside the finger while a touch drives a tool (drag a shape, a handle or a vertex, draw, place), so the
 * contact point stays visible when it is covered by the finger. [content] is the scene as the canvas draws it (the same
 * renderer, not a copy of the model), drawn again under a scale and clipped to a circle. The bubble takes no pointer input and
 * is hidden from accessibility: it is a visual aid only, and it never animates.
 */
@Composable
fun BoxScope.TouchLoupe(
    tracker: LoupeTracker,
    enabled: Boolean,
    canvasSize: () -> ScreenPx,
    pxPerDp: Double,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val holdFor by remember(tracker) { derivedStateOf { tracker.state.takeIf { it.phase == LoupePhase.Armed }?.pointerId } }
    LaunchedEffect(holdFor) {
        val id = holdFor ?: return@LaunchedEffect
        // Frame time, not delay(): it follows the test clock and needs no timer of its own.
        val start = withFrameMillis { it }
        while (withFrameMillis { it } - start < LoupeSpec.HOLD_MS) Unit
        tracker.holdElapsed(id)
    }
    val showing by remember(tracker, enabled) { derivedStateOf { LoupeSpec.shouldShow(tracker.state, enabled) } }
    // Read when the bubble shows: the size is set by layout after the host last composed, so a parameter value could be stale.
    val canvas = canvasSize()
    if (!showing || canvas.x <= 0.0 || canvas.y <= 0.0) return
    val diameter = LoupeSpec.diameterPx(pxPerDp)
    val size = remember(pxPerDp) { (LoupeSpec.DIAMETER_DP).dp }
    fun placement() = LoupeSpec.place(tracker.state.at, canvas.x, canvas.y, pxPerDp)
    val background = MaterialTheme.colorScheme.background
    val ring = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .clearAndSetSemantics { }
            .offset {
                val c = placement().centre
                IntOffset((c.x - diameter / 2).roundToInt(), (c.y - diameter / 2).roundToInt())
            }
            .size(size)
            .shadow(SHADOW, CircleShape)
            .clip(CircleShape)
            .background(background, CircleShape),
    ) {
        MagnifiedScene(tracker, canvas, pxPerDp, content)
        Crosshair(pxPerDp)
        Box(Modifier.size(size).border(RING, ring, CircleShape))
    }
}

/** The whole canvas laid out at its own size, scaled about the contact point and shifted so that point sits at the bubble's centre. */
@Composable
private fun MagnifiedScene(tracker: LoupeTracker, canvas: ScreenPx, pxPerDp: Double, content: @Composable () -> Unit) {
    val diameter = LoupeSpec.diameterPx(pxPerDp)
    fun centre() = LoupeSpec.place(tracker.state.at, canvas.x, canvas.y, pxPerDp).centre
    Box(
        Modifier
            .layout { measurable, _ ->
                val inner = measurable.measure(Constraints.fixed(canvas.x.roundToInt(), canvas.y.roundToInt()))
                layout(diameter.roundToInt(), diameter.roundToInt()) {
                    val c = centre()
                    inner.place(-(c.x - diameter / 2).roundToInt(), -(c.y - diameter / 2).roundToInt())
                }
            }
            .graphicsLayer {
                val contact = tracker.state.at
                val t = LoupeSpec.layerTransform(contact, centre())
                scaleX = t.scale.toFloat()
                scaleY = t.scale.toFloat()
                transformOrigin = TransformOrigin((contact.x / canvas.x).toFloat(), (contact.y / canvas.y).toFloat())
                translationX = t.translateX.toFloat()
                translationY = t.translateY.toFloat()
            },
        content = { content() },
    )
}

@Composable
private fun Crosshair(pxPerDp: Double) {
    val ink = MaterialTheme.colorScheme.onSurface
    val halo = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(LoupeSpec.DIAMETER_DP.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val gap = (CROSS_GAP_DP * pxPerDp).toFloat()
        val reach = (CROSS_REACH_DP * pxPerDp).toFloat()
        val lines = listOf(
            Offset(c.x - reach, c.y) to Offset(c.x - gap, c.y),
            Offset(c.x + gap, c.y) to Offset(c.x + reach, c.y),
            Offset(c.x, c.y - reach) to Offset(c.x, c.y - gap),
            Offset(c.x, c.y + gap) to Offset(c.x, c.y + reach),
        )
        for ((from, to) in lines) drawLine(halo, from, to, strokeWidth = (HALO_DP * pxPerDp).toFloat())
        for ((from, to) in lines) drawLine(ink, from, to, strokeWidth = (INK_DP * pxPerDp).toFloat())
    }
}

private val SHADOW = 6.dp
private val RING = 2.dp
private const val CROSS_GAP_DP = 3.0
private const val CROSS_REACH_DP = 14.0
private const val HALO_DP = 3.5
private const val INK_DP = 1.5
