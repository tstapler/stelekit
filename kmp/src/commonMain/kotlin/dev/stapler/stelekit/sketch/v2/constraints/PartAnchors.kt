// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.constraints

import dev.stapler.stelekit.sketch.v2.hit.PartHitTester
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a picker marker for a part sits, in scene units (Task 4.2.6b): the point itself for a point-like part, the middle of
 * an edge, and a point on the rim for a circle or arc taken as a whole. Null when the element has no such part.
 */
object PartAnchors {
    fun anchorOf(element: SceneElement, part: TargetPart): Pair<Double, Double>? = when (element) {
        is PointElement -> if (part == TargetPart.Whole) element.x.value to element.y.value else null
        is LineElement -> linear(element.points.map { it.x.value to it.y.value }, if (element.polygon) element.points.size else element.points.size - 1, part)
        is RectElement -> linear(PartHitTester.cornersOf(element), RECT_CORNERS, part)
        is EllipseElement -> circle(element, part)
        is ArcElement -> arc(element, part)
        else -> null
    }

    private fun linear(points: List<Pair<Double, Double>>, edges: Int, part: TargetPart): Pair<Double, Double>? = when (part) {
        TargetPart.Start -> points.firstOrNull()
        TargetPart.End -> points.lastOrNull()
        is TargetPart.Vertex -> points.getOrNull(part.index)
        is TargetPart.Edge -> {
            val a = points.getOrNull(part.index)
            val b = points.getOrNull((part.index + 1) % points.size)
            if (part.index >= edges || a == null || b == null) null else (a.first + b.first) / 2.0 to (a.second + b.second) / 2.0
        }
        else -> null
    }

    private fun circle(e: EllipseElement, part: TargetPart): Pair<Double, Double>? = when {
        !e.isCircle -> null
        part == TargetPart.Centre -> e.cx.value to e.cy.value
        part == TargetPart.Whole -> (e.cx.value + e.rx.value) to e.cy.value
        else -> null
    }

    private fun arc(e: ArcElement, part: TargetPart): Pair<Double, Double>? {
        val r = e.radius.value
        fun rim(angle: Double) = (e.cx.value + r * cos(angle)) to (e.cy.value + r * sin(angle))
        val sweep = ((e.endAngle - e.startAngle) % FULL_TURN).let { if (it < 0.0) it + FULL_TURN else it }
        return when (part) {
            TargetPart.Centre -> e.cx.value to e.cy.value
            TargetPart.ArcStart -> rim(e.startAngle)
            TargetPart.ArcEnd -> rim(e.endAngle)
            TargetPart.Whole -> rim(e.startAngle + sweep / 2.0)
            else -> null
        }
    }

    private const val RECT_CORNERS = 4
    private const val FULL_TURN = 2.0 * PI
}
