// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.snap

import dev.stapler.stelekit.sketch.SnapKind
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.UnderlayIds
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import kotlin.math.abs
import kotlin.math.roundToLong

enum class GuideAxis { Vertical, Horizontal }

/**
 * A guide line shown while a drag is snapped: a [Vertical] guide sits at x = [position] and runs from [from] to [to] in y
 * (a [Horizontal] one is the mirror). [kind] maps the alignment onto the existing `SnapEngine` kinds: an edge is an
 * ENDPOINT, a centre a MIDPOINT, a grid line GRID.
 */
data class SnapGuide(val axis: GuideAxis, val position: Double, val from: Double, val to: Double, val kind: SnapKind)

/** The corrected delta of a drag and the guides to draw; [snapped] is false when nothing was in reach. */
data class SnapOutcome(val dx: Double, val dy: Double, val guides: List<SnapGuide>) {
    val snapped: Boolean get() = guides.isNotEmpty()
}

/** Grid settings of a sheet (Task 3.5.5b): technical sheets snap to the sixteenth of an inch, diagram sheets to the grid. */
data class GridSettings(val enabled: Boolean, val stepUnits: Double, val majorEvery: Int = 0) {
    init {
        require(stepUnits.isFinite() && stepUnits > 0.0) { "grid step must be positive" }
        require(majorEvery >= 0) { "major interval cannot be negative" }
    }

    fun snap(value: Double): Double = if (enabled) (value / stepUnits).roundToLong() * stepUnits else value

    companion object {
        /** One scene unit is a sixteenth of an inch, so the technical step is one unit. */
        const val SIXTEENTH_STEP_UNITS = 1.0

        /** Excalidraw's grid size for diagram sheets. */
        const val DIAGRAM_STEP_UNITS = 20.0

        fun technical(enabled: Boolean = true) = GridSettings(enabled, SIXTEENTH_STEP_UNITS)

        fun diagram(enabled: Boolean) = GridSettings(enabled, DIAGRAM_STEP_UNITS)
    }
}

/**
 * Snapping a dragged selection to the edges and centres of nearby elements (Story 3.5.5a), through [SpatialIndex] so
 * only elements near the dragged bounds are looked at. Pure: the caller keeps the index current with the scene.
 */
object ObjectSnap {
    /** Reach of a snap in screen pixels at one pixel per dp (so 8 dp); scaled by the density and divided by the zoom to get scene units. */
    const val SNAP_RADIUS_PX = 8.0

    /** How far from the dragged bounds, in screen pixels, other elements are looked at: about one screen. */
    const val SNAP_REACH_PX = 480.0

    private const val CENTRE = 2.0

    internal class Line(val value: Double, val kind: SnapKind, val source: SceneBounds)

    internal fun xLines(b: SceneBounds) = listOf(Line(b.minX, SnapKind.ENDPOINT, b), Line((b.minX + b.maxX) / CENTRE, SnapKind.MIDPOINT, b), Line(b.maxX, SnapKind.ENDPOINT, b))

    internal fun yLines(b: SceneBounds) = listOf(Line(b.minY, SnapKind.ENDPOINT, b), Line((b.minY + b.maxY) / CENTRE, SnapKind.MIDPOINT, b), Line(b.maxY, SnapKind.ENDPOINT, b))

    /**
     * Snaps the selection [movingIds], proposed to move by ([dx], [dy]), to other elements, then to [grid] on the axes that
     * found no element. [disabled] is true while Ctrl is held: the proposed delta comes back unchanged with no guides.
     */
    fun snapMove(
        scene: SketchSceneV2,
        index: SpatialIndex,
        movingIds: Set<ElementId>,
        dx: Double,
        dy: Double,
        zoom: Double,
        disabled: Boolean = false,
        grid: GridSettings? = null,
        pxPerDp: Double = 1.0,
    ): SnapOutcome {
        val moving = movingIds.mapNotNull { scene[it] }
        val base = SceneBounds.of(moving)
        val usableZoom = zoom.isFinite() && zoom > 0.0
        if (disabled || base == null || !usableZoom) return SnapOutcome(dx, dy, emptyList())
        val tolerance = SNAP_RADIUS_PX * pxPerDp / zoom
        val proposed = SceneBounds(base.minX + dx, base.minY + dy, base.maxX + dx, base.maxY + dy)
        val hidden = scene.layers.filter { !it.visible }.map { it.id }.toSet() + UnderlayIds.LAYER
        val near = index.query(Bounds(proposed.minX, proposed.minY, proposed.maxX, proposed.maxY).inflated(SNAP_REACH_PX / zoom))
            .filter { it !in movingIds }.mapNotNull { scene[it] }.filter { it.base.layerId !in hidden }.mapNotNull { SceneBounds.of(it) }

        val xBest = best(xLines(proposed), near.flatMap { xLines(it) }, tolerance)
        val yBest = best(yLines(proposed), near.flatMap { yLines(it) }, tolerance)
        var outX = dx + (xBest?.first ?: 0.0)
        var outY = dy + (yBest?.first ?: 0.0)
        val guides = ArrayList<SnapGuide>()
        xBest?.second?.let { target -> guides += guide(GuideAxis.Vertical, target, proposed.shifted(xBest.first, yBest?.first ?: 0.0)) }
        yBest?.second?.let { target -> guides += guide(GuideAxis.Horizontal, target, proposed.shifted(xBest?.first ?: 0.0, yBest.first)) }
        if (grid != null && grid.enabled) {
            if (xBest == null) {
                outX = grid.snap(base.minX + dx) - base.minX
                guides += SnapGuide(GuideAxis.Vertical, base.minX + outX, base.minY + outY, base.maxY + outY, SnapKind.GRID)
            }
            if (yBest == null) {
                outY = grid.snap(base.minY + dy) - base.minY
                guides += SnapGuide(GuideAxis.Horizontal, base.minY + outY, base.minX + outX, base.maxX + outX, SnapKind.GRID)
            }
        }
        return SnapOutcome(outX, outY, guides)
    }

    /** [dx], [dy] with the top-left of [movingIds]' joint bounds rounded onto [grid]; unchanged when the grid is off or nothing is selected. */
    fun snapMoveToGrid(scene: SketchSceneV2, movingIds: Set<ElementId>, dx: Double, dy: Double, grid: GridSettings): SnapOutcome {
        val base = SceneBounds.of(movingIds.mapNotNull { scene[it] })
        if (!grid.enabled || base == null) return SnapOutcome(dx, dy, emptyList())
        return SnapOutcome(grid.snap(base.minX + dx) - base.minX, grid.snap(base.minY + dy) - base.minY, emptyList())
    }

    /** The correction (target minus mine) and target of the closest pair within [tolerance], or null. */
    internal fun best(mine: List<Line>, targets: List<Line>, tolerance: Double): Pair<Double, Line>? {
        var winner: Pair<Double, Line>? = null
        for (m in mine) for (t in targets) {
            val d = t.value - m.value
            if (abs(d) <= tolerance && (winner == null || abs(d) < abs(winner.first))) winner = d to t
        }
        return winner
    }

    internal fun SceneBounds.shifted(dx: Double, dy: Double) = SceneBounds(minX + dx, minY + dy, maxX + dx, maxY + dy)

    internal fun guide(axis: GuideAxis, target: Line, moved: SceneBounds): SnapGuide {
        val s = target.source
        return if (axis == GuideAxis.Vertical) {
            SnapGuide(axis, target.value, minOf(s.minY, moved.minY), maxOf(s.maxY, moved.maxY), target.kind)
        } else {
            SnapGuide(axis, target.value, minOf(s.minX, moved.minX), maxOf(s.maxX, moved.maxX), target.kind)
        }
    }
}
