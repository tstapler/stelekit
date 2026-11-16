// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

enum class FlashMode {
    OFF, AUTO, ON;

    /** One step per press: Off, Auto, On, Off. A lens without a flash unit stays put (the button is disabled). */
    fun next(hasFlash: Boolean): FlashMode = if (!hasFlash) this else entries[(ordinal + 1) % entries.size]
}

/**
 * Keeps the highlighted chip in step with a pinch without a rebind (UX-59). Only optical chips can be highlighted,
 * and the highlight moves to a neighbour only after the pinch passes the midpoint by [HYSTERESIS] of the gap, so a
 * hand hovering at the midpoint does not flicker.
 */
class PinchChipTracker(chips: List<CameraLens>) {
    private val optical = chips.filter { it.optical }.sortedBy { it.labelRatio }
    var highlighted: CameraLens? = null
        private set

    fun onZoom(ratio: Double): CameraLens? {
        if (optical.isEmpty()) return null
        val current = highlighted
        if (current == null) {
            highlighted = optical.minBy { kotlin.math.abs(it.labelRatio - ratio) }
            return highlighted
        }
        var index = optical.indexOf(current)
        while (index + 1 < optical.size && ratio >= threshold(optical[index], optical[index + 1], up = true)) index++
        while (index > 0 && ratio <= threshold(optical[index - 1], optical[index], up = false)) index--
        highlighted = optical[index]
        return highlighted
    }

    private fun threshold(low: CameraLens, high: CameraLens, up: Boolean): Double {
        val gap = high.labelRatio - low.labelRatio
        val mid = (low.labelRatio + high.labelRatio) / 2.0
        return if (up) mid + HYSTERESIS * gap else mid - HYSTERESIS * gap
    }

    private companion object {
        const val HYSTERESIS = 0.05
    }
}
