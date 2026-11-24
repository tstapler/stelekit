// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.snap.PointSnap
import dev.stapler.stelekit.sketch.v2.snap.PointSnapHit

/**
 * Locks a pointer position onto the drawing's endpoints, midpoints, intersections, centres and, from an [anchor], the
 * perpendicular and tangent points. Reads `ToolContext.snap`: a feature within reach wins, else the point rounds onto the grid when it is on. Ctrl, and a finger
 * resting on the Snap button (which makes the settings empty), leave the position alone. Typed sizes never come through here.
 */
object PointSnapper {
    /**
     * A pointer position after snapping: [point] is what the tool acts on, [hit] the feature it locked onto (null when none),
     * and [gridded] says it was rounded onto the grid instead.
     */
    class Placed(val point: ScenePoint, val hit: PointSnapHit?, val gridded: Boolean = false) {
        /** True when snapping moved the point at all. */
        val snapped: Boolean get() = hit != null || gridded

        /** A preview carrying only the marker, for a press that has drawn nothing yet; null when nothing was snapped. */
        fun markerOnly(): ToolPreview? = hit?.let { ToolPreview(emptyList(), cursor = point, point = it) }

        /** [preview] with the marker and the loupe cursor of this snap. */
        fun marked(preview: ToolPreview): ToolPreview = if (hit == null) preview else preview.copy(cursor = point, point = hit)
    }

    /** Reach of the snap in scene units: the pointer-class tolerance of `hitTolerancePx` (finger-sized for touch, in dp). */
    fun tolerance(event: InputEvent, ctx: ToolContext): Double = ctx.hitTolerancePx(event.pointerKind != PointerKind.Mouse) / ctx.zoom

    fun place(
        raw: ScenePoint,
        anchor: ScenePoint?,
        event: InputEvent,
        ctx: ToolContext,
        exclude: Set<ElementId> = emptySet(),
        extraVertices: List<ScenePoint> = emptyList(),
    ): Placed {
        if (event.modifiers.ctrl) return Placed(raw, null)
        val probe = PointSnap.probe(ctx.scene, ctx.snapIndex, raw.x.value, raw.y.value, tolerance(event, ctx), ctx.snap.points, anchor, exclude, extraVertices)
        val hit = probe.hit
        if (hit != null) return Placed(ScenePoint(unitOrZero(hit.x), unitOrZero(hit.y)), hit)
        val grid = ctx.snap.activeGrid ?: return Placed(raw, null)
        return Placed(ScenePoint(unitOrZero(grid.snap(raw.x.value)), unitOrZero(grid.snap(raw.y.value))), null, gridded = true)
    }
}
