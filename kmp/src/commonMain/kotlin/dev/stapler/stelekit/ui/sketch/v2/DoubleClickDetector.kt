// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.tools.InputEvent
import kotlin.math.hypot

/**
 * Tells a second quick press on the same element from a first one. The platform pointer events carry no click count, so the
 * detector counts presses itself: two presses of one pointer kind within [WINDOW_MS] and [SLOP_SCENE_UNITS] over the same element.
 */
class DoubleClickDetector {
    private var lastAt: Long = NEVER
    private var lastPoint: ScenePoint? = null
    private var lastHit: ElementId? = null

    /** Records the press [event] over [hit] and returns true when it is the second press of a double click. */
    fun press(event: InputEvent, hit: ElementId?): Boolean {
        val previous = lastPoint
        val second = hit != null && hit == lastHit && previous != null && event.timestamp - lastAt <= WINDOW_MS && distance(previous, event.point) <= SLOP_SCENE_UNITS
        lastAt = if (second) NEVER else event.timestamp
        lastPoint = if (second) null else event.point
        lastHit = if (second) null else hit
        return second
    }

    private fun distance(a: ScenePoint, b: ScenePoint): Double = hypot(a.x.value - b.x.value, a.y.value - b.y.value)

    companion object {
        const val WINDOW_MS = 400L
        const val SLOP_SCENE_UNITS = 12.0
        private const val NEVER = Long.MIN_VALUE / 2
    }
}
