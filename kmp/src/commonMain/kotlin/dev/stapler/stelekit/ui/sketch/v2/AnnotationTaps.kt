// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

/**
 * The finger-sized targets of the dimension values, kept apart from the pointer tree on purpose: a value is not a pointer node
 * (any pointer node over the canvas would take its gestures), so the canvas's own input chain reports a clean tap here through
 * [annotationTapInput] and the matching value's action runs. Drags, marquees, pinches and handle grabs never reach this.
 */
internal class AnnotationTapTargets {
    class Target(val rect: AnnotationRect, val onTap: () -> Unit)

    var targets: List<Target> = emptyList()

    /** Runs the target containing ([xDp], [yDp]); true when one did. Targets never overlap, so at most one contains the point. */
    fun tapAt(xDp: Double, yDp: Double): Boolean {
        val hit = targets.firstOrNull { xDp >= it.rect.left && xDp <= it.rect.right && yDp >= it.rect.top && yDp <= it.rect.bottom } ?: return false
        hit.onTap()
        return true
    }
}

/**
 * Watches the pointer events of this node in the Final pass, after the canvas has handled and consumed them, and calls
 * [AnnotationTapTargets.tapAt] for one pointer that went down and up within the touch slop. It consumes nothing.
 */
internal fun Modifier.annotationTapInput(taps: AnnotationTapTargets): Modifier = pointerInput(taps) {
    val tracker = TapTracker(viewConfiguration.touchSlop) { x, y -> taps.tapAt((x / density).toDouble(), (y / density).toDouble()) }
    awaitPointerEventScope {
        while (true) tracker.onEvent(awaitPointerEvent(PointerEventPass.Final).changes)
    }
}

/** One pointer's down-to-up path; a second pointer, or travel past [slop], voids the tap. */
private class TapTracker(private val slop: Float, private val onTap: (Float, Float) -> Unit) {
    private var downId: PointerId? = null
    private var downAt = Offset.Zero

    fun onEvent(changes: List<PointerInputChange>) {
        val change = changes.firstOrNull() ?: return
        if (changes.size > 1) downId = null
        val moved = hypot(change.position.x - downAt.x, change.position.y - downAt.y) > slop
        when {
            change.changedToDownIgnoreConsumed() && changes.size == 1 -> {
                downId = change.id
                downAt = change.position
            }
            change.changedToUpIgnoreConsumed() && change.id == downId -> {
                downId = null
                if (!moved) onTap(change.position.x, change.position.y)
            }
            change.pressed && change.id == downId && moved -> downId = null
        }
    }
}
