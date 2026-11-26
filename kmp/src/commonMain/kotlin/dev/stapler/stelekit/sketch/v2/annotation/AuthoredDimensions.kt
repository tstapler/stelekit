// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.annotation

import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import kotlin.math.abs
import kotlin.math.hypot

/** What the computed annotations leave alone because the sheet already draws it. */
object AuthoredDimensions {
    /** Door and window symbols live here; only their box is measured, so each width is shown once. */
    const val OPENINGS_LAYER_ID = "openings"

    private const val SLACK_DP = 0.5
    private const val TWO_POINT_ARROW = 2

    /** False for authored dimension marks and for the strokes of a door or window symbol. */
    fun isMeasured(element: SceneElement): Boolean = when (element.base.layerId.value) {
        DimensionExport.LAYER_ID -> false
        OPENINGS_LAYER_ID -> element is RectElement
        else -> true
    }

    /** The two-point arrows on the dimensions layer, in canvas dp. Extension lines have no arrowhead, so they state nothing. */
    internal fun lines(bodies: List<SceneElement>, canvas: AnnotationCanvas): List<AnnotationSegment> =
        bodies.filterIsInstance<ArrowElement>()
            .filter { it.base.layerId.value == DimensionExport.LAYER_ID && it.points.size == TWO_POINT_ARROW }
            .filter { it.startArrowhead != Arrowhead.None || it.endArrowhead != Arrowhead.None }
            .map { arrow ->
                val (a, b) = arrow.points.map { canvas.toScreenDp(it.x.value, it.y.value) }
                AnnotationSegment(AnnotationPoint(a.first, a.second), AnnotationPoint(b.first, b.second))
            }

    /** True when one of [lines] runs parallel to [span] over the same stretch, at any offset to its side. */
    internal fun states(lines: List<AnnotationSegment>, span: AnnotationSpan): Boolean {
        val dx = span.q.x - span.p.x
        val dy = span.q.y - span.p.y
        val length = hypot(dx, dy).takeIf { it > 0.0 } ?: return false
        val along = { p: AnnotationPoint -> ((p.x - span.p.x) * dx + (p.y - span.p.y) * dy) / length }
        val across = { p: AnnotationPoint -> ((p.x - span.p.x) * dy - (p.y - span.p.y) * dx) / length }
        return lines.any { line ->
            val a = along(line.a)
            val b = along(line.b)
            abs(across(line.a) - across(line.b)) <= SLACK_DP && abs(minOf(a, b)) <= SLACK_DP && abs(maxOf(a, b) - length) <= SLACK_DP
        }
    }
}
