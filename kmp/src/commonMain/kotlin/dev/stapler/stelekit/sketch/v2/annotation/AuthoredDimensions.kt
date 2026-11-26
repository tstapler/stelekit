// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.annotation

import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.units.LengthFormat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Which computed annotation: an element's measurement, and for a wall which segment. */
internal data class MeasureRef(val element: ElementId, val kind: MeasureKind, val tag: String)

/**
 * What the computed annotations leave alone because the sheet already draws it. Only grouped content counts, as `build` and the
 * export write it: lines a person draws on these layers are measured like any other.
 */
internal class AuthoredDimensions(private val bodies: List<SceneElement>) {
    private val arrows = bodies.filterIsInstance<ArrowElement>().filter { it.base.layerId.value == DimensionExport.LAYER_ID }
    private val markGroups = arrows.flatMapTo(HashSet()) { it.base.groupIds }
    private val boxedOpenings = bodies.filter { it is RectElement && it.base.layerId.value == OPENINGS_LAYER_ID }.flatMapTo(HashSet()) { it.base.groupIds }

    /** False for the parts of a dimension mark, and for the strokes of a door or window symbol whose box gives its width. */
    fun isMeasured(element: SceneElement): Boolean = when (element.base.layerId.value) {
        DimensionExport.LAYER_ID -> element.base.groupIds.none { it in markGroups }
        OPENINGS_LAYER_ID -> element is RectElement || element.base.groupIds.none { it in boxedOpenings }
        else -> true
    }

    /**
     * The measurements a mark already shows. A mark is a headed two-point arrow with a text in its group; it states the one
     * measurement it is drawn beside whose value that text gives, the nearest shape winning (the larger one on a tie).
     */
    fun stated(canvas: AnnotationCanvas, format: LengthFormat, spansOf: (SceneElement) -> List<AnnotationSpan>): Set<MeasureRef> {
        val marks = arrows.filter { it.points.size == TWO_POINT_ARROW && (it.startArrowhead != Arrowhead.None || it.endArrowhead != Arrowhead.None) }
        if (marks.isEmpty()) return emptySet()
        val texts = bodies.filterIsInstance<TextElement>().filter { it.base.layerId.value == DimensionExport.LAYER_ID }
        val spans = bodies.filter(::isMeasured).flatMap { e -> spansOf(e).map { MeasureRef(e.id, it.label, it.tag) to it } }
        val slack = SLACK_UNITS * canvas.dpPerUnit
        return marks.mapNotNullTo(HashSet()) { mark ->
            val shown = texts.filter { t -> t.base.groupIds.any { it in mark.base.groupIds } }.mapTo(HashSet()) { it.originalText.trim() }
            val (a, b) = mark.points.map { canvas.toScreenDp(it.x.value, it.y.value) }.map { AnnotationPoint(it.first, it.second) }
            spans.filter { (_, span) -> listOf(format, LengthFormat.FEET_INCHES).any { AnnotationText.value(span.label, span.sixteenths, it, span.exact) in shown } }
                .mapNotNull { (ref, span) -> gapBeside(span, a, b, slack)?.let { Triple(ref, it, span.reach) } }
                .minWithOrNull(compareBy<Triple<MeasureRef, Double, Double>>({ it.second }, { -it.third }, { it.first.element.value }))
                ?.first
        }
    }

    /**
     * How far outside the shape the line [a]-[b] runs, or null unless it is [span] moved sideways: square to it, or straight
     * across (up or down for a mostly horizontal span), which is how `build` offsets a mark.
     */
    private fun gapBeside(span: AnnotationSpan, a: AnnotationPoint, b: AnnotationPoint, slack: Double): Double? {
        val dx = span.q.x - span.p.x
        val dy = span.q.y - span.p.y
        val length = hypot(dx, dy).takeIf { it > 0.0 } ?: return null
        val forward = (b.x - a.x) * dx + (b.y - a.y) * dy >= 0.0
        val start = if (forward) a else b
        val end = if (forward) b else a
        if (abs(end.x - start.x - dx) > slack || abs(end.y - start.y - dy) > slack) return null
        val ox = start.x - span.p.x
        val oy = start.y - span.p.y
        val square = abs(ox * dx + oy * dy) / length <= slack
        val straightAcross = if (abs(dx) >= abs(dy)) abs(ox) <= slack else abs(oy) <= slack
        if (!square && !straightAcross) return null
        return max(0.0, abs(ox * dy - oy * dx) / length - span.reach)
    }

    companion object {
        /** Door and window symbols live here. */
        const val OPENINGS_LAYER_ID = "openings"

        private const val SLACK_UNITS = 1.0
        private const val TWO_POINT_ARROW = 2
    }
}
