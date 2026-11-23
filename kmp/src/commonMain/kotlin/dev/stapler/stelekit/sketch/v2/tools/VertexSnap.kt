// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.hit.PartHitTester
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import kotlin.math.hypot

/** Where a dragged vertex snaps to: the nearest vertex, corner or centre of the scene within reach. */
internal object VertexSnap {
    /** The point of another element (or another vertex of [line]) within [tolerance] of [to], nearest first; null when none is. */
    fun nearest(to: ScenePoint, line: LineElement, skip: Int, tolerance: Double, ctx: ToolContext): ScenePoint? {
        val area = Bounds.around(to.x.value, to.y.value, tolerance)
        val near = ctx.spatialIndex?.query(area)?.mapNotNull { ctx.scene[it] } ?: ctx.scene.elementsInOrder
        val hidden = ctx.scene.layers.filter { !it.visible }.map { it.id }.toSet()
        return near.filter { it.base.layerId !in hidden }
            .flatMap { e -> pointsOf(e).filterIndexed { i, _ -> e.id != line.id || i != skip } }
            .minByOrNull { hypot(it.x.value - to.x.value, it.y.value - to.y.value) }
            ?.takeIf { hypot(it.x.value - to.x.value, it.y.value - to.y.value) <= tolerance }
    }

    private fun pointsOf(e: SceneElement): List<ScenePoint> = when (e) {
        is LineElement -> e.points
        is ArrowElement -> e.points
        is PointElement -> listOf(ScenePoint(e.x, e.y))
        is RectElement -> PartHitTester.cornersOf(e).map { (x, y) -> ScenePoint(unitOrZero(x), unitOrZero(y)) }
        is EllipseElement -> listOf(ScenePoint(e.cx, e.cy))
        else -> emptyList()
    }
}
