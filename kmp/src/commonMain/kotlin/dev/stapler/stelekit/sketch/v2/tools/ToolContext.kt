// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.hit.HitTester
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.snap.SnapSettings
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider

/** Source of fresh element ids; a seam so scenario tests are deterministic. */
fun interface ElementIdSource {
    fun next(): ElementId
}

/**
 * Everything a tool may read when it handles an event. Read-only: a tool never mutates the scene, it returns a
 * [ToolCommand] (Story 1.3.1 turns that into an undoable command).
 *
 * [zoom] is the view scale (screen px per scene unit); hit tolerances are screen px divided by it (ADR-002).
 */
data class ToolContext(
    val scene: SketchSceneV2,
    val zoom: Double,
    val style: ElementStyle,
    val ids: ElementIdSource,
    /** The host's live index of [scene]; when null the first hit test builds one (O(elements): fine for a test or one-off). */
    val spatialIndex: SpatialIndex? = null,
    /** Screen pixels per dp, so a touch target stated in dp is the same physical size on every screen. */
    val pxPerDp: Double = 1.0,
    /** What drags snap to (object edges, the grid); none by default so a tool sees raw geometry unless the host asks. */
    val snap: SnapSettings = SnapSettings.None,
    /** Measures text for a gesture that re-wraps it (a side-handle drag on a text box); null keeps the box change only. */
    val textMetrics: TextMetricsProvider? = null,
) {
    /** How far from an outline a press still hits it, in screen px: a finger-sized reach for touch, the fine mouse reach otherwise. */
    fun hitTolerancePx(touch: Boolean): Double = if (touch) maxOf(TOUCH_HIT_DP * pxPerDp, HitTester.HIT_TOLERANCE_PX) else HitTester.HIT_TOLERANCE_PX

    /** The index snapping queries: the host's, or one built once from [scene]. */
    val snapIndex: SpatialIndex by lazy { spatialIndex ?: SpatialIndex().also { it.rebuild(scene) } }

    /** Narrow-phase hit testing over [scene], shared by every tool handling this event. */
    val hitTester: HitTester by lazy { HitTester(scene, spatialIndex ?: SpatialIndex().also { it.rebuild(scene) }) }

    init {
        require(zoom.isFinite() && zoom > 0.0) { "zoom must be positive and finite" }
        require(pxPerDp.isFinite() && pxPerDp > 0.0) { "pxPerDp must be positive and finite" }
    }

    private companion object {
        const val TOUCH_HIT_DP = 12.0
    }
}
