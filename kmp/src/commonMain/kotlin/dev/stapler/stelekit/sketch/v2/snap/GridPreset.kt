// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.snap

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The grid steps of a technical sheet, one scene unit being a sixteenth of an inch. [majorEvery] is how many steps make a
 * heavier line (an inch of sixteenths, a foot of inches), so a coarse grid still reads as a ruler.
 *
 * Metric hook (epic E2, not merged yet): 10 mm and 100 mm presets join here with `stepUnits = mm / 25.4 * 16`; nothing else
 * needs to change, since the menu, the persistence key and the drawing all go through [entries].
 */
enum class GridPreset(val stepUnits: Double, val majorEvery: Int) {
    Sixteenth(1.0, 16),
    Quarter(4.0, 4),
    Inch(16.0, 12),
    SixInch(96.0, 2),
    Foot(192.0, 5),
    ;

    fun settings(enabled: Boolean): GridSettings = GridSettings(enabled, stepUnits, majorEvery)

    /** The next coarser preset, wrapping to the finest after the coarsest. */
    fun next(): GridPreset = entries[(ordinal + 1) % entries.size]

    companion object {
        val Default = Sixteenth

        fun named(name: String?): GridPreset = entries.firstOrNull { it.name == name } ?: Default
    }
}

/** One grid line of the picture: a scene coordinate and whether it is a major line. */
data class GridLine(val position: Double, val major: Boolean)

/**
 * Which grid lines to draw along one axis between scene coordinates [from] and [to]. Lines closer than [MIN_SPACING_DP] on
 * screen are thinned by doubling the stride, so a fine grid zoomed out costs about as many lines as a coarse one and the
 * count never exceeds the screen length over the spacing floor.
 */
object GridLines {
    const val MIN_SPACING_DP = 12.0

    private const val SLACK_LINES = 2

    fun along(grid: GridSettings, from: Double, to: Double, zoom: Double, pxPerDp: Double): List<GridLine> {
        val usable = zoom.isFinite() && zoom > 0.0 && pxPerDp.isFinite() && pxPerDp > 0.0 && from.isFinite() && to.isFinite() && to >= from
        if (!grid.enabled || !usable) return emptyList()
        var stride = 1L
        while (grid.stepUnits * stride * zoom < MIN_SPACING_DP * pxPerDp) stride *= 2
        val step = grid.stepUnits * stride
        val first = ceil(from / step).toLong()
        val last = floor(to / step).toLong()
        val count = last - first + 1
        val room = ceil((to - from) * zoom / (MIN_SPACING_DP * pxPerDp)).toLong() + SLACK_LINES
        if (count <= 0L || count > room) return emptyList()
        return (first..last).map { i ->
            val index = i * stride
            GridLine(index * grid.stepUnits, grid.majorEvery > 0 && index % grid.majorEvery == 0L)
        }
    }
}
