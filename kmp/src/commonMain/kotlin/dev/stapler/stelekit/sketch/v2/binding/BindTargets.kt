// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.binding

import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2

/** The shape an arrow end dropped at one point would bind to, and the binding it would get. */
data class BindTarget(val target: SceneElement, val binding: Binding)

/**
 * Finds the shape under or near a dropped arrow end (ux.md section 5: within 8 px at zoom 1). The topmost bindable shape
 * in paint order wins, and a locked shape may be bound to (the owner default, ux.md "Error/edge states"); an element on a
 * hidden layer is not offered. An arrow is never a target.
 */
object BindTargets {
    /** Screen pixels around a drop that still bind. */
    const val SNAP_RADIUS_PX = 8.0

    fun find(scene: SketchSceneV2, index: SpatialIndex?, px: Double, py: Double, zoom: Double): BindTarget? {
        if (!zoom.isFinite() || zoom <= 0.0) return null
        val tolerance = SNAP_RADIUS_PX / zoom
        val candidates = index?.query(Bounds.around(px, py, tolerance))?.mapNotNull { scene[it] } ?: scene.elementsInOrder
        val order = scene.order.withIndex().associate { it.value to it.index }
        val top = candidates
            .filter { BindingGeometry.isBindable(it) && visible(scene, it) && BindingGeometry.reach(it, px, py) <= tolerance }
            .maxWithOrNull(compareBy<SceneElement>({ scene.layer(it.base.layerId)?.order ?: Layer.DefaultLayer.order }, { it.base.zKey }, { order[it.id] ?: 0 }))
            ?: return null
        return BindingGeometry.bindingAt(top, px, py)?.let { BindTarget(top, it) }
    }

    private fun visible(scene: SketchSceneV2, e: SceneElement): Boolean = (scene.layer(e.base.layerId) ?: Layer.DefaultLayer).visible
}
