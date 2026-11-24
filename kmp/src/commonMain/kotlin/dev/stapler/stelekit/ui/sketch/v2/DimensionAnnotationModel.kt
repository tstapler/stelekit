// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.ui.sketch.lengthTextOf
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings as S
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Which shapes carry a persistent dimension annotation: only the selected ones, or every visible one. */
enum class DimensionScope { Selected, All }

data class AnnotationPoint(val x: Double, val y: Double)

data class AnnotationSegment(val a: AnnotationPoint, val b: AnnotationPoint)

/** A rectangle in the canvas's dp space. */
data class AnnotationRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centreX: Double get() = (left + right) / 2
    val centreY: Double get() = (top + bottom) / 2

    fun overlaps(o: AnnotationRect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun inflated(d: Double): AnnotationRect = AnnotationRect(left - d, top - d, right + d, bottom + d)

    fun contains(o: AnnotationRect): Boolean = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom

    /** True when [s] passes through this rectangle's interior (Liang-Barsky clip against the box). */
    fun crosses(s: AnnotationSegment): Boolean {
        val dx = s.b.x - s.a.x
        val dy = s.b.y - s.a.y
        var t0 = 0.0
        var t1 = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(s.a.x - left, right - s.a.x, s.a.y - top, bottom - s.a.y)
        for (i in p.indices) {
            if (p[i] == 0.0) {
                if (q[i] <= 0.0) return false
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0.0) t0 = max(t0, r) else t1 = min(t1, r)
                if (t0 >= t1) return false
            }
        }
        return true
    }
}

/**
 * One persistent dimension drawn on a shape of a technical sheet, in dp: the dimension line with its two extension lines, the
 * value text's rectangle (axis-aligned, centred on the line) and the finger-sized [tapRect] that selects the shape and opens its
 * editor. [alpha] fades the annotation in as the zoom makes its text fit.
 */
data class DimensionAnnotation(
    val elementId: ElementId,
    val elementName: String,
    val kind: PillKind,
    val sixteenths: Long,
    val text: String,
    val line: AnnotationSegment,
    val extensions: List<AnnotationSegment>,
    val textRect: AnnotationRect,
    val tapRect: AnnotationRect,
    val alpha: Double,
)

/**
 * Pure rules for the dimension annotations of a technical sheet drawn like an elevation: which measurables of a shape are drawn,
 * which side of it (and how many rows out) the line sits so no two annotations touch, and which are dropped (too small to
 * read, too many, outside the canvas). A function of the shapes, the viewport and the canvas size; the value text is never
 * smaller than [TEXT_SP], so a shape too small to carry it loses its annotation instead of shrinking the text.
 */
object DimensionAnnotationLayout {
    /** The text size in sp; never scaled down with the zoom, and at least [MIN_TEXT_SP]. */
    const val TEXT_SP = 11.0
    const val MIN_TEXT_SP = 10.0

    /** The most shapes annotated in one layout, nearest the canvas centre first. */
    const val MAX_ANNOTATED_SHAPES = 200

    /** How many rows out from the shape a line may be stacked before the annotation is dropped. */
    const val MAX_ROWS = 3

    /** A line shorter than this many text widths is hidden; it is fully drawn from [FADE_FULL_RATIO] on and fades in between. */
    const val FADE_START_RATIO = 1.3
    const val FADE_FULL_RATIO = 1.8

    /** An annotation fainter than this is dropped, so nothing nearly invisible keeps a target or a screen-reader node. */
    const val MIN_ALPHA = 0.2

    const val TEXT_GAP_DP = 2.0
    const val EXT_GAP_DP = 3.0
    const val EXT_OVERSHOOT_DP = 4.0
    const val ROW_GAP_DP = 3.0
    const val EDGE_DP = DimensionPillModel.EDGE_DP

    private const val CHAR_EM = 0.6
    private const val PAD_H_DP = 4.0
    private const val PAD_V_DP = 2.0
    private const val LINE_FACTOR = 1.2
    private const val TWO_POINT_LINE = 2
    internal const val HALF = 2.0
    internal val TEXT_POSITIONS = doubleArrayOf(0.5, 0.3, 0.7, 0.15, 0.85)

    /** The size of the text rectangle for [text] at the user's font scale. */
    fun textSize(text: String, fontScale: Double): Pair<Double, Double> =
        (text.length * CHAR_EM * TEXT_SP + 2 * PAD_H_DP) * fontScale to (TEXT_SP * LINE_FACTOR + 2 * PAD_V_DP) * fontScale

    /** The one place a value becomes text: it calls `lengthTextOf`, the formatter the pills and size fields use, so a change there applies here. */
    fun valueText(kind: PillKind, sixteenths: Long): String =
        (if (kind == PillKind.Diameter) S.Pill.DIAMETER_SYMBOL + " " else "") + lengthTextOf(sixteenths)

    /**
     * The annotations of [candidates] on [canvas]. [bodies] are every visible shape (the candidates included): an annotation is kept
     * off other shapes when it can be. [reserved] are rectangles that stay free (the selected shape's pills). [tapMinDp] is the
     * touch floor for each annotation's [DimensionAnnotation.tapRect].
     */
    fun layout(
        candidates: List<SceneElement>,
        bodies: List<SceneElement>,
        canvas: PillCanvas,
        reserved: List<AnnotationRect>,
        fontScale: Double,
        tapMinDp: Double,
    ): List<DimensionAnnotation> {
        if (canvas.width <= 0.0 || canvas.height <= 0.0) return emptyList()
        val centreX = canvas.width / 2
        val centreY = canvas.height / 2
        val bodyBoxes = bodies.filter { it !is FrameElement }.map { it.id to screenBox(it, canvas) }
        val ordered = candidates
            .map { it to screenBox(it, canvas) }
            .sortedWith(compareBy({ hypot(it.second.centreX - centreX, it.second.centreY - centreY) }, { it.first.id.value }))
        val placed = ArrayList<AnnotationPlaced>()
        val out = ArrayList<DimensionAnnotation>()
        var shapes = 0
        for ((element, _) in ordered) {
            if (shapes >= MAX_ANNOTATED_SHAPES) break
            val name = ElementNames.nameOf(element)
            var annotated = false
            for (span in spansOf(element, canvas)) {
                val text = valueText(span.label, span.sixteenths)
                val (tw, th) = textSize(text, fontScale)
                val extent = span.textExtent(tw, th)
                val alpha = ((span.lengthDp() / extent - FADE_START_RATIO) / (FADE_FULL_RATIO - FADE_START_RATIO)).coerceIn(0.0, 1.0)
                if (alpha < MIN_ALPHA) continue
                val at = AnnotationPlacer.place(span, AnnotationPlacer.Fit(canvas, reserved, placed, bodyBoxes, element.id, tw, th, extent)) ?: continue
                placed += at
                annotated = true
                out += DimensionAnnotation(element.id, name, span.label, span.sixteenths, text, at.line, at.extensions, at.text, at.text, alpha)
            }
            if (annotated) shapes++
        }
        return withTapRects(out, reserved, canvas, tapMinDp)
    }

    private fun screenBox(e: SceneElement, canvas: PillCanvas): AnnotationRect {
        val b = ElementBounds.geometry(e)
        val (x0, y0) = canvas.toScreenDp(b.minX, b.minY)
        val (x1, y1) = canvas.toScreenDp(b.maxX, b.maxY)
        return AnnotationRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    }

    private fun spansOf(element: SceneElement, canvas: PillCanvas): List<AnnotationSpan> {
        if (element is LineElement) return lineSpan(element, canvas)?.let { listOf(it) }.orEmpty()
        val s = ElementStats.of(element) ?: return emptyList()
        val specs = DimensionPillModel.specs(element).filter { it.kind != PillKind.Radius }
        if (specs.isEmpty()) return emptyList()
        val (cx, cy) = canvas.toScreenDp(s.x + s.width / 2, s.y + s.height / 2)
        val angle = (element as? BoxElement)?.angle ?: (element as? EllipseElement)?.angle ?: 0.0
        val hw = s.width / 2 * canvas.dpPerUnit
        val hh = s.height / 2 * canvas.dpPerUnit
        val u = AnnotationPoint(cos(angle), sin(angle))
        val v = AnnotationPoint(-sin(angle), cos(angle))
        val rect = element is RectElement
        return specs.map { spec ->
            if (spec.kind == PillKind.Height) {
                val p = AnnotationPoint(cx - v.x * hh, cy - v.y * hh)
                val q = AnnotationPoint(cx + v.x * hh, cy + v.y * hh)
                AnnotationSpan(spec.kind, spec.sixteenths, p, q, preferred(listOf(u, neg(u)), wantRight = true), hw, if (rect) hw else 0.0)
            } else {
                val p = AnnotationPoint(cx - u.x * hw, cy - u.y * hw)
                val q = AnnotationPoint(cx + u.x * hw, cy + u.y * hw)
                AnnotationSpan(spec.kind, spec.sixteenths, p, q, preferred(listOf(neg(v), v), wantRight = false), hh, if (rect) hh else 0.0)
            }
        }
    }

    private fun lineSpan(line: LineElement, canvas: PillCanvas): AnnotationSpan? {
        val length = DimensionPillModel.lineLength(line) ?: return null
        if (line.polygon || line.points.size != TWO_POINT_LINE) return null
        val (a, b) = line.points.map { canvas.toScreenDp(it.x.value, it.y.value) }
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len = hypot(dx, dy).takeIf { it > 0.0 } ?: return null
        val n = AnnotationPoint(dy / len, -dx / len)
        val sixteenths = max(1L, kotlin.math.round(length).toLong())
        return AnnotationSpan(PillKind.Length, sixteenths, AnnotationPoint(a.first, a.second), AnnotationPoint(b.first, b.second), preferred(listOf(n, neg(n)), wantRight = true), 0.0, 0.0)
    }

    private fun neg(p: AnnotationPoint) = AnnotationPoint(-p.x, -p.y)

    /** [normals] ordered with the side that reads best first: up the screen for a horizontal run, to the right for a vertical one. */
    private fun preferred(normals: List<AnnotationPoint>, wantRight: Boolean): List<AnnotationPoint> {
        val first = normals.first()
        val second = normals.last()
        val score = { n: AnnotationPoint -> if (wantRight) n.x - n.y * TIE else -n.y + n.x * TIE }
        return if (score(first) >= score(second)) listOf(first, second) else listOf(second, first)
    }

    private const val TIE = 0.001

    internal fun extensionsOf(span: AnnotationSpan, line: AnnotationSegment): List<AnnotationSegment> {
        val dir = unit(line.a.x - span.p.x, line.a.y - span.p.y)
        return listOf(span.p to line.a, span.q to line.b).map { (from, to) ->
            val start = EXT_GAP_DP + span.extStart
            AnnotationSegment(AnnotationPoint(from.x + dir.x * start, from.y + dir.y * start), AnnotationPoint(to.x + dir.x * EXT_OVERSHOOT_DP, to.y + dir.y * EXT_OVERSHOOT_DP))
        }
    }

    private fun unit(x: Double, y: Double): AnnotationPoint {
        val len = hypot(x, y).takeIf { it > 0.0 } ?: return AnnotationPoint(0.0, 0.0)
        return AnnotationPoint(x / len, y / len)
    }

    /**
     * Grows each text rectangle to the finger-sized target and cuts it back where it would meet a neighbour's, the canvas edge
     * or a reserved rectangle, so targets never overlap and each still contains its own text.
     */
    private fun withTapRects(annotations: List<DimensionAnnotation>, reserved: List<AnnotationRect>, canvas: PillCanvas, tapMinDp: Double): List<DimensionAnnotation> {
        val taps = annotations.map { a ->
            val t = a.textRect
            val halfW = max(t.width, tapMinDp) / HALF
            val halfH = max(t.height, tapMinDp) / HALF
            AnnotationRect(
                max(0.0, min(t.left, t.centreX - halfW)), max(0.0, min(t.top, t.centreY - halfH)),
                min(canvas.width, max(t.right, t.centreX + halfW)), min(canvas.height, max(t.bottom, t.centreY + halfH)),
            )
        }.toMutableList()
        for (i in taps.indices) {
            for (j in i + 1 until taps.size) {
                if (!taps[i].overlaps(taps[j])) continue
                val (ci, cj) = cut(taps[i], annotations[i].textRect, taps[j], annotations[j].textRect)
                taps[i] = ci
                taps[j] = cj
            }
            for (r in reserved) if (taps[i].overlaps(r)) taps[i] = cut(taps[i], annotations[i].textRect, r, r).first
        }
        return annotations.mapIndexed { i, a -> a.copy(tapRect = taps[i]) }
    }

    /** Splits two overlapping targets along the axis their texts are separated on, at the middle of the gap. */
    private fun cut(a: AnnotationRect, textA: AnnotationRect, b: AnnotationRect, textB: AnnotationRect): Pair<AnnotationRect, AnnotationRect> {
        val gapX = max(textB.left - textA.right, textA.left - textB.right)
        val gapY = max(textB.top - textA.bottom, textA.top - textB.bottom)
        return if (gapX >= gapY) {
            val aLeft = textA.centreX <= textB.centreX
            val mid = if (aLeft) (textA.right + textB.left) / HALF else (textB.right + textA.left) / HALF
            if (aLeft) a.copy(right = min(a.right, mid)) to b.copy(left = max(b.left, mid)) else a.copy(left = max(a.left, mid)) to b.copy(right = min(b.right, mid))
        } else {
            val aAbove = textA.centreY <= textB.centreY
            val mid = if (aAbove) (textA.bottom + textB.top) / HALF else (textB.bottom + textA.top) / HALF
            if (aAbove) a.copy(bottom = min(a.bottom, mid)) to b.copy(top = max(b.top, mid)) else a.copy(top = max(a.top, mid)) to b.copy(bottom = min(b.bottom, mid))
        }
    }

    /** "Width of Rectangle 1: 3 feet 7 inches" for a screen reader. */
    fun description(a: DimensionAnnotation): String = S.Annotation.description(DimensionPillModel.label(a.kind), a.elementName, SpokenLength.of(a.sixteenths))
}

/** A placed annotation before its target is sized: the text rectangle, the dimension line, and whether it sits inside its own shape. */
internal class AnnotationPlaced(val text: AnnotationRect, val line: AnnotationSegment, val extensions: List<AnnotationSegment>)

internal class AnnotationSpan(val label: PillKind, val sixteenths: Long, val p: AnnotationPoint, val q: AnnotationPoint, val normals: List<AnnotationPoint>, val reach: Double, val extStart: Double) {
    fun lengthDp(): Double = hypot(q.x - p.x, q.y - p.y)

    /** How much of the line the text covers: its width along a horizontal run, its height along a vertical one. */
    fun textExtent(tw: Double, th: Double): Double {
        val len = lengthDp().takeIf { it > 0.0 } ?: return tw
        return abs(q.x - p.x) / len * tw + abs(q.y - p.y) / len * th
    }
}

/** Chooses where one span's line and text go: outside on the preferred side in stacked rows, else inside a rectangle, else onto a neighbour. */
internal object AnnotationPlacer {
    private const val HALF = DimensionAnnotationLayout.HALF

    /** What a placement is checked against: the canvas, the free-space reservations, the annotations already placed and the other shapes' boxes. */
    internal class Fit(val canvas: PillCanvas, val reserved: List<AnnotationRect>, val placed: List<AnnotationPlaced>, val bodies: List<Pair<ElementId, AnnotationRect>>, val self: ElementId, val tw: Double, val th: Double, val extent: Double)

    /**
     * The spot for [span]'s line and text: outside the shape on the preferred side, then the other, stacking outward in rows; else,
     * for a rectangle, just inside its own edge. A spot that lands on another shape is never used, so a value cannot read as that
     * shape's; null means the annotation is dropped.
     */
    internal fun place(span: AnnotationSpan, fit: Fit): AnnotationPlaced? {
        val outside = span.normals.flatMap { n -> (0 until DimensionAnnotationLayout.MAX_ROWS).map { row -> offsetOf(span, n, row, fit) to false } }
        val inner = if (span.extStart > 0.0) listOf(innerOffset(span, fit) to true) else emptyList()
        return (outside + inner).firstNotNullOfOrNull { (line, isInner) -> firstFree(span, line, isInner, fit) }
    }

    private fun offsetOf(span: AnnotationSpan, n: AnnotationPoint, row: Int, fit: Fit): AnnotationSegment {
        val support = abs(n.x) * fit.tw / HALF + abs(n.y) * fit.th / HALF
        return shifted(span, n, span.reach + DimensionAnnotationLayout.EXT_GAP_DP + support + row * (2 * support + DimensionAnnotationLayout.ROW_GAP_DP))
    }

    /** A line just inside the shape's edge on the preferred side; empty-width when the shape is too small to hold its text. */
    private fun innerOffset(span: AnnotationSpan, fit: Fit): AnnotationSegment {
        val n = span.normals.first()
        val support = abs(n.x) * fit.tw / HALF + abs(n.y) * fit.th / HALF
        val fits = span.reach >= support + DimensionAnnotationLayout.EXT_GAP_DP
        return shifted(span, n, if (fits) span.reach - support - DimensionAnnotationLayout.EXT_GAP_DP else Double.NaN)
    }

    private fun shifted(span: AnnotationSpan, n: AnnotationPoint, off: Double) =
        AnnotationSegment(AnnotationPoint(span.p.x + n.x * off, span.p.y + n.y * off), AnnotationPoint(span.q.x + n.x * off, span.q.y + n.y * off))

    private fun firstFree(span: AnnotationSpan, line: AnnotationSegment, isInner: Boolean, fit: Fit): AnnotationPlaced? {
        if (line.a.x.isNaN()) return null
        val length = span.lengthDp()
        val extensions = if (isInner) emptyList() else DimensionAnnotationLayout.extensionsOf(span, line)
        for (t in DimensionAnnotationLayout.TEXT_POSITIONS) {
            if (t * length < fit.extent / HALF || (1 - t) * length < fit.extent / HALF) continue
            val cx = line.a.x + (line.b.x - line.a.x) * t
            val cy = line.a.y + (line.b.y - line.a.y) * t
            val text = AnnotationRect(cx - fit.tw / HALF, cy - fit.th / HALF, cx + fit.tw / HALF, cy + fit.th / HALF)
            if (inside(text, fit.canvas) && clear(text, listOf(line) + extensions, fit)) return AnnotationPlaced(text, line, extensions)
        }
        return null
    }

    private fun inside(text: AnnotationRect, canvas: PillCanvas): Boolean =
        text.left >= DimensionAnnotationLayout.EDGE_DP && text.top >= DimensionAnnotationLayout.EDGE_DP && text.right <= canvas.width - DimensionAnnotationLayout.EDGE_DP && text.bottom <= canvas.height - DimensionAnnotationLayout.EDGE_DP

    private fun clear(text: AnnotationRect, strokes: List<AnnotationSegment>, fit: Fit): Boolean {
        val padded = text.inflated(DimensionAnnotationLayout.TEXT_GAP_DP)
        if (fit.bodies.any { (id, box) -> id != fit.self && box.overlaps(text) }) return false
        if (fit.reserved.any { r -> r.overlaps(padded) || strokes.any { r.crosses(it) } }) return false
        return fit.placed.none { p ->
            val theirs = listOf(p.line) + p.extensions
            p.text.overlaps(padded) || theirs.any { padded.crosses(it) } || strokes.any { p.text.inflated(DimensionAnnotationLayout.TEXT_GAP_DP).crosses(it) }
        }
    }
}

/** A length in words ("3 feet 7 and 3 eighths inches") for a screen reader; a scene unit is a sixteenth of an inch. */
object SpokenLength {
    private const val SIXTEENTHS_PER_INCH = 16L
    private const val INCHES_PER_FOOT = 12L

    fun of(sixteenths: Long): String {
        val total = abs(sixteenths)
        val feet = total / (SIXTEENTHS_PER_INCH * INCHES_PER_FOOT)
        val rest = total % (SIXTEENTHS_PER_INCH * INCHES_PER_FOOT)
        val inches = rest / SIXTEENTHS_PER_INCH
        val frac = rest % SIXTEENTHS_PER_INCH
        val parts = ArrayList<String>()
        if (feet > 0) parts += "$feet ${if (feet == 1L) S.Spoken.FOOT else S.Spoken.FEET}"
        if (frac == 0L) {
            if (inches > 0 || feet == 0L) parts += "$inches ${if (inches == 1L) S.Spoken.INCH else S.Spoken.INCHES}"
        } else {
            val g = gcd(frac, SIXTEENTHS_PER_INCH)
            val n = frac / g
            val d = SIXTEENTHS_PER_INCH / g
            val fraction = "$n ${S.Spoken.denominator(d, plural = n > 1)}"
            parts += if (inches == 0L) "$fraction ${S.Spoken.OF_AN_INCH}" else "$inches ${S.Spoken.AND} $fraction ${S.Spoken.INCHES}"
        }
        return parts.joinToString(" ")
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
}
