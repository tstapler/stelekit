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
import kotlin.math.roundToLong

/** Which computed annotation: an element's measurement, and for a wall which segment. */
internal data class MeasureRef(val element: ElementId, val kind: MeasureKind, val tag: String)

/**
 * What the computed annotations leave alone because the sheet already draws it. Only grouped content counts, as `build` and the
 * export write it: lines a person draws on these layers are measured like any other.
 */
internal class AuthoredDimensions(private val bodies: List<SceneElement>) {
    private val arrows = bodies.filterIsInstance<ArrowElement>().filter { it.base.layerId.value == DimensionExport.LAYER_ID }
    private val texts = bodies.filterIsInstance<TextElement>().filter { it.base.layerId.value == DimensionExport.LAYER_ID }
    private val markGroups = (arrows + texts).flatMapTo(HashSet()) { it.base.groupIds }
    private val boxedOpenings = bodies.filter { it is RectElement && it.base.layerId.value == OPENINGS_LAYER_ID }.flatMapTo(HashSet()) { it.base.groupIds }

    /** False for the parts of a dimension mark, and for the strokes of a door or window symbol whose box gives its width. */
    fun isMeasured(element: SceneElement): Boolean = when (element.base.layerId.value) {
        DimensionExport.LAYER_ID -> element.base.groupIds.none { it in markGroups }
        OPENINGS_LAYER_ID -> element is RectElement || element.base.groupIds.none { it in boxedOpenings }
        else -> true
    }

    /**
     * The measurements a mark already shows. A mark is a headed two-point arrow with a text in its group; it states the one
     * measurement it is drawn beside whose value that text gives. Shapes it is just outside of come before shapes it lies in
     * (an adjacent room), which come before distant ones; then the nearest wins, then the larger one. A mark drawn inward
     * next to a same-sized neighbour is ambiguous and goes to the neighbour.
     */
    fun stated(canvas: AnnotationCanvas, format: LengthFormat, spansOf: (SceneElement) -> List<AnnotationSpan>): Set<MeasureRef> {
        val marks = arrows.filter { it.points.size == TWO_POINT_ARROW && (it.startArrowhead != Arrowhead.None || it.endArrowhead != Arrowhead.None) }
        if (marks.isEmpty()) return emptySet()
        val spans = bodies.filter(::isMeasured).flatMap { e -> spansOf(e).map { Candidate(MeasureRef(e.id, it.label, it.tag), it, writtenAs(it, format)) } }
        val slack = SLACK_UNITS * canvas.dpPerUnit
        val near = NEAR_UNITS * canvas.dpPerUnit
        fun tier(gap: Double) = if (gap < -slack) 1 else if (gap <= near) 0 else 2
        return marks.mapNotNullTo(HashSet()) { mark ->
            val shown = texts.filter { t -> t.base.groupIds.any { it in mark.base.groupIds } }.mapTo(HashSet()) { it.originalText.trim() }
            val (a, b) = mark.points.map { canvas.toScreenDp(it.x.value, it.y.value) }.map { AnnotationPoint(it.first, it.second) }
            spans.filter { c -> c.written.any { it in shown } }
                .mapNotNull { c -> gapBeside(c.span, a, b, slack)?.let { c to it } }
                .minWithOrNull(compareBy<Pair<Candidate, Double>>({ tier(it.second) }, { abs(it.second) }, { -it.first.span.reach }, { it.first.ref.element.value }))
                ?.first?.ref
        }
    }

    private class Candidate(val ref: MeasureRef, val span: AnnotationSpan, val written: Set<String>)

    /** How a mark may write [span]'s value: as the annotation would, in feet and inches, or as a diagram's bare number. */
    private fun writtenAs(span: AnnotationSpan, format: LengthFormat): Set<String> {
        // A circle's mark gives the diameter without the symbol.
        val kind = if (span.label == MeasureKind.Diameter) MeasureKind.Length else span.label
        val written = hashSetOf<String>()
        for (f in listOf(format, LengthFormat.FEET_INCHES)) {
            written += AnnotationText.value(kind, span.sixteenths, f, span.exact)
            written += AnnotationText.value(span.label, span.sixteenths, f, span.exact)
        }
        val whole = span.exact.roundToLong()
        if (abs(span.exact - whole) < WHOLE_SLACK) written += whole.toString()
        return written
    }

    /**
     * How far outside the shape the line [a]-[b] runs (negative inside it), or null unless it is [span] moved sideways: square to it, or straight
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
        return abs(ox * dy - oy * dx) / length - span.reach
    }

    companion object {
        /** Door and window symbols live here. */
        const val OPENINGS_LAYER_ID = "openings"

        private const val SLACK_UNITS = 1.0
        /** Six feet: how far outside a shape a mark still plainly belongs to it. */
        private const val NEAR_UNITS = 1152.0
        private const val WHOLE_SLACK = 0.01
        private const val TWO_POINT_ARROW = 2
    }
}
