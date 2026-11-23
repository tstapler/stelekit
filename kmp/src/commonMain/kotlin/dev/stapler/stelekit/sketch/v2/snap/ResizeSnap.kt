// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.snap

import dev.stapler.stelekit.sketch.SnapKind
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import kotlin.math.abs

/** Which sides a resize handle drags: [sx] and [sy] are -1 (left/top), 0 (not dragged) or 1 (right/bottom), as on `Handle`. */
data class ResizeSides(val sx: Int, val sy: Int) {
    val isCorner: Boolean get() = sx != 0 && sy != 0
}

/**
 * Snapping the moving edge(s) of a handle resize to the edges and centres of other elements, then to the grid.
 *
 * Works on the pointer delta, not the finished box: the edge a handle drags sits at its start position plus the delta
 * (also with Alt/from-centre, where the opposite edge mirrors it), so correcting the delta moves only that edge and the
 * anchored edge stays put. Aspect lock (Shift on a corner) derives one axis from the other, so only the primary axis
 * (the one scaled most) is snapped and the other follows. Only unrotated frames are snapped; a rotated frame has no
 * axis-aligned edge to align, so the caller does not ask.
 */
object ResizeSnap {
    private const val FROM_CENTRE_GROWTH = 2.0

    /** A snap never moves an edge by more than this share of the frame's size on that axis, so a small shape is not dominated by its neighbours. */
    private const val MAX_TOLERANCE_SHARE = 0.25

    @Suppress("LongParameterList")
    fun snap(
        scene: SketchSceneV2,
        index: SpatialIndex,
        movingIds: Set<ElementId>,
        frame: SceneBounds,
        sides: ResizeSides,
        dx: Double,
        dy: Double,
        keepAspect: Boolean,
        fromCentre: Boolean,
        zoom: Double,
        pxPerDp: Double,
        settings: SnapSettings,
    ): SnapOutcome {
        val unchanged = SnapOutcome(dx, dy, emptyList())
        val usable = zoom.isFinite() && zoom > 0.0 && pxPerDp.isFinite() && pxPerDp > 0.0
        val grid = settings.activeGrid
        val dragsAnEdge = sides.sx != 0 || sides.sy != 0
        if (!usable || !settings.active || !dragsAnEdge) return unchanged

        val edgeX = (if (sides.sx > 0) frame.maxX else frame.minX) + dx
        val edgeY = (if (sides.sy > 0) frame.maxY else frame.minY) + dy
        val grow = if (fromCentre) FROM_CENTRE_GROWTH else 1.0
        var snapX = sides.sx != 0
        var snapY = sides.sy != 0
        val hasArea = frame.width > 0.0 && frame.height > 0.0
        if (keepAspect && sides.isCorner && hasArea) {
            val xPrimary = (frame.width + sides.sx * dx * grow) / frame.width >= (frame.height + sides.sy * dy * grow) / frame.height
            snapX = xPrimary
            snapY = !xPrimary
        }

        val tolerance = ObjectSnap.SNAP_RADIUS_PX * pxPerDp / zoom
        val near = if (settings.objects) neighbours(scene, index, movingIds, frame, edgeX, edgeY, dx, dy, zoom) else emptyList()
        val xHit = if (snapX) correction(edgeX, near.flatMap { ObjectSnap.xLines(it) }, capped(tolerance, frame.width), grid) else null
        val yHit = if (snapY) correction(edgeY, near.flatMap { ObjectSnap.yLines(it) }, capped(tolerance, frame.height), grid) else null
        if (xHit == null && yHit == null) return unchanged

        val moved = SceneBounds(
            if (sides.sx < 0) edgeX + (xHit?.first ?: 0.0) else frame.minX,
            if (sides.sy < 0) edgeY + (yHit?.first ?: 0.0) else frame.minY,
            if (sides.sx > 0) edgeX + (xHit?.first ?: 0.0) else frame.maxX,
            if (sides.sy > 0) edgeY + (yHit?.first ?: 0.0) else frame.maxY,
        )
        val guides = listOfNotNull(
            xHit?.second?.let { ObjectSnap.guide(GuideAxis.Vertical, it, moved) },
            yHit?.second?.let { ObjectSnap.guide(GuideAxis.Horizontal, it, moved) },
        )
        var outX = dx + (xHit?.first ?: 0.0)
        var outY = dy + (yHit?.first ?: 0.0)
        // Aspect lock: re-derive the other axis from the snapped one so TransformMath keeps the same primary axis and the edge stays on its line.
        if (keepAspect && sides.isCorner && hasArea) {
            if (xHit != null) outY = ((frame.width + sides.sx * outX * grow) / frame.width * frame.height - frame.height) / (sides.sy * grow)
            if (yHit != null) outX = ((frame.height + sides.sy * outY * grow) / frame.height * frame.width - frame.width) / (sides.sx * grow)
        }
        return SnapOutcome(outX, outY, guides)
    }

    private fun capped(tolerance: Double, size: Double): Double = if (size > 0.0) minOf(tolerance, size * MAX_TOLERANCE_SHARE) else tolerance

    @Suppress("LongParameterList")
    private fun neighbours(
        scene: SketchSceneV2,
        index: SpatialIndex,
        movingIds: Set<ElementId>,
        frame: SceneBounds,
        edgeX: Double,
        edgeY: Double,
        dx: Double,
        dy: Double,
        zoom: Double,
    ): List<SceneBounds> {
        val area = Bounds(minOf(frame.minX, edgeX), minOf(frame.minY, edgeY), maxOf(frame.maxX, edgeX), maxOf(frame.maxY, edgeY))
        val reach = ObjectSnap.SNAP_REACH_PX / zoom + abs(dx) + abs(dy)
        val hidden = scene.layers.filter { !it.visible }.map { it.id }.toSet()
        return index.query(area.inflated(reach)).filter { it !in movingIds }.mapNotNull { scene[it] }.filter { it.base.layerId !in hidden }.mapNotNull { SceneBounds.of(it) }
    }

    /** The shift that puts [edge] on the closest element line within [tolerance], else on the grid; null when neither applies. */
    private fun correction(edge: Double, targets: List<ObjectSnap.Line>, tolerance: Double, grid: GridSettings?): Pair<Double, ObjectSnap.Line>? {
        val mine = ObjectSnap.Line(edge, SnapKind.ENDPOINT, SceneBounds(edge, edge, edge, edge))
        ObjectSnap.best(listOf(mine), targets, tolerance)?.let { return it }
        if (grid == null) return null
        val target = grid.snap(edge)
        return (target - edge) to ObjectSnap.Line(target, SnapKind.GRID, SceneBounds(target, target, target, target))
    }
}
