// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.snap

import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.view.SceneBounds

/**
 * What the drag tools snap to, read at every pointer event so a toggle takes effect mid-session. [objects] snaps to other
 * elements' edges and centres; [grid] is the sheet's grid (sixteenth of an inch on technical sheets). [None] snaps nothing,
 * the default of every tool context, so a tool test sees raw geometry unless it asks for snapping.
 */
data class SnapSettings(val objects: Boolean = false, val grid: GridSettings? = null) {
    /** The grid, or null when absent or switched off. */
    val activeGrid: GridSettings? get() = grid?.takeIf { it.enabled }

    val active: Boolean get() = objects || activeGrid != null

    /**
     * These settings while a finger rests on the Snap button (the touch counterpart of holding Ctrl): [None] for as long as
     * [held], so the drag in progress and the next ones go unsnapped until the finger lifts.
     */
    fun whileHeld(held: Boolean): SnapSettings = if (held) None else this

    /** [dx], [dy] of moving [ids] corrected to the neighbours and grid; unchanged when [bypass] (Ctrl held) or nothing snaps. */
    @Suppress("LongParameterList")
    fun move(scene: SketchSceneV2, index: SpatialIndex, ids: Set<ElementId>, dx: Double, dy: Double, zoom: Double, pxPerDp: Double, bypass: Boolean): SnapOutcome {
        val gridOnly = activeGrid
        return when {
            bypass || !active -> SnapOutcome(dx, dy, emptyList())
            objects -> ObjectSnap.snapMove(scene, index, ids, dx, dy, zoom, grid = gridOnly, pxPerDp = pxPerDp)
            else -> ObjectSnap.snapMoveToGrid(scene, ids, dx, dy, checkNotNull(gridOnly))
        }
    }

    /** Pointer delta of a handle resize corrected so the moving edge(s) of [frame] land on a neighbour or the grid; see [ResizeSnap]. */
    @Suppress("LongParameterList")
    fun resize(
        scene: SketchSceneV2,
        index: SpatialIndex,
        ids: Set<ElementId>,
        frame: SceneBounds,
        sides: ResizeSides,
        dx: Double,
        dy: Double,
        keepAspect: Boolean,
        fromCentre: Boolean,
        zoom: Double,
        pxPerDp: Double,
        bypass: Boolean,
    ): SnapOutcome =
        if (bypass) SnapOutcome(dx, dy, emptyList()) else ResizeSnap.snap(scene, index, ids, frame, sides, dx, dy, keepAspect, fromCentre, zoom, pxPerDp, this)

    companion object {
        val None = SnapSettings()
    }
}
