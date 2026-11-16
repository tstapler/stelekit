// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.hit.HitTester
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2

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
) {
    /** Narrow-phase hit testing over [scene], shared by every tool handling this event. */
    val hitTester: HitTester by lazy { HitTester(scene, spatialIndex ?: SpatialIndex().also { it.rebuild(scene) }) }

    init {
        require(zoom.isFinite() && zoom > 0.0) { "zoom must be positive and finite" }
    }
}
