// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.annotation

import dev.stapler.stelekit.sketch.MetricScene
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import dev.stapler.stelekit.units.MetricLength
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class AnnotationPoint(val x: Double, val y: Double)

data class AnnotationSegment(val a: AnnotationPoint, val b: AnnotationPoint)

/** A rectangle in the canvas's dp space. */
data class AnnotationRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centreX: Double get() = (left + right) / 2
    val centreY: Double get() = (top + bottom) / 2

    fun overlaps(o: AnnotationRect): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun shifted(dx: Double, dy: Double): AnnotationRect = AnnotationRect(left + dx, top + dy, right + dx, bottom + dy)

    fun inflated(d: Double): AnnotationRect = AnnotationRect(left - d, top - d, right + d, bottom + d)

    fun contains(o: AnnotationRect): Boolean = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom

    /** True when [s] passes through this rectangle's interior (Liang-Barsky clip against the box). */
    fun crosses(s: AnnotationSegment): Boolean = inside(s) != null

    /** The pieces of [s] outside this rectangle: all of it when it misses, else what is left on either side of the part inside. */
    fun clipOutside(s: AnnotationSegment): List<AnnotationSegment> {
        val (t0, t1) = inside(s) ?: return listOf(s)
        val at = { t: Double -> AnnotationPoint(s.a.x + (s.b.x - s.a.x) * t, s.a.y + (s.b.y - s.a.y) * t) }
        return listOfNotNull(
            if (t0 > 0.0) AnnotationSegment(s.a, at(t0)) else null,
            if (t1 < 1.0) AnnotationSegment(at(t1), s.b) else null,
        )
    }

    /** The parameter range of [s] inside this rectangle's interior, or null when it does not cross. */
    private fun inside(s: AnnotationSegment): Pair<Double, Double>? {
        val dx = s.b.x - s.a.x
        val dy = s.b.y - s.a.y
        var t0 = 0.0
        var t1 = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(s.a.x - left, right - s.a.x, s.a.y - top, bottom - s.a.y)
        for (i in p.indices) {
            if (p[i] == 0.0) {
                if (q[i] <= 0.0) return null
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0.0) t0 = max(t0, r) else t1 = min(t1, r)
                if (t0 >= t1) return null
            }
        }
        return t0 to t1
    }
}

/**
 * The canvas a layout runs on, in dp: [toScreenDp] maps a scene point to it (the editor passes its viewport, an export a fixed
 * fit of the sheet), [dpPerUnit] is the zoom in dp per scene unit, and [width] by [height] is the visible area.
 */
class AnnotationCanvas(val toScreenDp: (Double, Double) -> Pair<Double, Double>, val dpPerUnit: Double, val width: Double, val height: Double)

enum class MeasureKind { Width, Height, Diameter, Radius, Length }

/** One measurable of a shape: [sixteenths] is its rounded size (a scene unit is a sixteenth of an inch), [exact] the unrounded one. */
data class Measure(val kind: MeasureKind, val sixteenths: Long, val exact: Double = sixteenths.toDouble())

/** What a shape measures: the one rule the on-canvas pills, the annotations and every export share. */
object Measurements {
    private const val TWO_POINT_LINE = 2
    private const val HALF_UNIT = 0.5

    /** The measurables in edit order (Width then Height); none for kinds with no single size. */
    fun of(element: SceneElement): List<Measure> = when (element) {
        is RectElement -> box(element.width.value, element.height.value)
        is DiamondElement -> box(element.width.value, element.height.value)
        is EllipseElement -> if (element.isCircle) listOf(Measure(MeasureKind.Diameter, sixteenths(2 * element.rx.value), 2 * element.rx.value), Measure(MeasureKind.Radius, sixteenths(element.rx.value), element.rx.value)) else box(2 * element.rx.value, 2 * element.ry.value)
        is LineElement -> lineLength(element)?.let { listOf(Measure(MeasureKind.Length, sixteenths(it), it)) }.orEmpty()
        else -> emptyList()
    }

    private fun box(w: Double, h: Double) = listOf(Measure(MeasureKind.Width, sixteenths(w), w), Measure(MeasureKind.Height, sixteenths(h), h))

    /** The nearest sixteenth, halves up (not to even: a radius of 8.5 reads 9, not 8). */
    private fun sixteenths(v: Double): Long = max(1L, floor(v + HALF_UNIT).toLong())

    fun lineLength(e: LineElement): Double? {
        if (e.polygon || e.points.size != TWO_POINT_LINE) return null
        val (a, b) = e.points
        return hypot(b.x.value - a.x.value, b.y.value - a.y.value).takeIf { it > 0.0 }
    }
}

/** A shape's own box: position, unrotated size and angle in radians. A box or ellipse reports the box a resize writes, any other kind its bounds; null for an empty one. */
data class ElementFrame(val x: Double, val y: Double, val width: Double, val height: Double, val angle: Double) {
    companion object {
        fun of(element: SceneElement): ElementFrame? = when (element) {
            is BoxElement -> ElementFrame(element.x.value, element.y.value, element.width.value, element.height.value, element.angle)
            is EllipseElement -> ElementFrame(element.cx.value - element.rx.value, element.cy.value - element.ry.value, 2 * element.rx.value, 2 * element.ry.value, element.angle)
            else -> SceneBounds.of(element)?.let { ElementFrame(it.minX, it.minY, it.width, it.height, 0.0) }
        }
    }
}

/** The text of a dimension: the one place a value becomes words, shared by the editor and every export. */
object AnnotationText {
    const val DIAMETER_SYMBOL = "\u00D8"

    /** [exact] is the unrounded size in scene units: a metric display shows it to 0.1 mm instead of the rounded sixteenth. */
    fun value(kind: MeasureKind, sixteenths: Long, format: LengthFormat = LengthFormat.FEET_INCHES, exact: Double = sixteenths.toDouble()): String =
        (if (kind == MeasureKind.Diameter) "$DIAMETER_SYMBOL " else "") +
            if (MetricLength.isMetric(format)) MetricScene.formatUnits(abs(exact), format) else LengthFormatter.format(Inches(abs(sixteenths)), format)
}

/**
 * One dimension drawn on a shape of a technical sheet, in the layout canvas's dp: the dimension line with its extension lines,
 * the value text's rectangle (axis-aligned, centred on the line) and the finger-sized [tapRect]. [alpha] fades the annotation in as
 * the zoom makes its text fit.
 */
data class PlacedDimension(
    val elementId: ElementId,
    val elementName: String,
    val kind: MeasureKind,
    val sixteenths: Long,
    val text: String,
    val line: AnnotationSegment,
    val extensions: List<AnnotationSegment>,
    val textRect: AnnotationRect,
    val tapRect: AnnotationRect,
    val alpha: Double,
    /** Which of an element's several annotations this is: a wall's segment (`s0`, `s1`, ...) or its room area ([DimensionAnnotationEngine.AREA_TAG]); empty for a shape's only one of its kind. */
    val tag: String = "",
)

/**
 * Pure rules for the dimension annotations of a technical sheet drawn like an elevation: which measurables of a shape are drawn,
 * which side of it (and how many rows out) the line sits so no two annotations touch, and which are dropped (too small to
 * read, too many, outside the canvas). A function of the shapes and the canvas; the editor and every export run this same code,
 * so what an export draws is what the editor shows. The value text is never smaller than [TEXT_SP], so a shape too small to
 * carry it loses its annotation instead of shrinking the text.
 */
object DimensionAnnotationEngine {
    /** The text size in sp; never scaled down with the zoom, and at least 10. */
    const val TEXT_SP = 11.0

    /** The most shapes annotated in one layout, nearest the canvas centre first. */
    const val MAX_ANNOTATED_SHAPES = 200

    /** How many rows out from the shape a line may be stacked before the annotation is dropped. */
    const val MAX_ROWS = 3

    /** A line shorter than this many text widths is hidden; it is fully drawn from [FADE_FULL_RATIO] on and fades in between. */
    const val FADE_START_RATIO = 1.3
    const val FADE_FULL_RATIO = 1.8

    /** At most this many times the shape cap of candidates are tried, so a crowded sheet where most fail stays cheap. */
    const val ATTEMPT_FACTOR = 3

    /** A value fainter than this gets no finger-sized target (it is still read out). */
    const val TAP_MIN_ALPHA = 0.6

    /** An annotation fainter than this is dropped, so nothing nearly invisible keeps a target or a screen-reader node. */
    const val MIN_ALPHA = 0.2

    const val TEXT_GAP_DP = 2.0

    /** The [PlacedDimension.tag] of a closed room's floor-area label. */
    const val AREA_TAG = "area"
    const val EXT_GAP_DP = 3.0
    const val EXT_OVERSHOOT_DP = 4.0
    const val ROW_GAP_DP = 3.0
    const val EDGE_DP = 4.0

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
    fun valueText(kind: MeasureKind, sixteenths: Long, format: LengthFormat = LengthFormat.FEET_INCHES, exact: Double = sixteenths.toDouble()): String =
        AnnotationText.value(kind, sixteenths, format, exact)

    /**
     * The annotations of [candidates] on [canvas]. [bodies] are every visible shape (the candidates included): an annotation never
     * covers another shape. [reserved] are rectangles that stay free (the selected shape's pills); [textOnlyReserved] only keep text out (lines may cross). [tapMinDp] is the
     * touch floor for each annotation's [PlacedDimension.tapRect].
     */
    fun layout(
        candidates: List<SceneElement>,
        bodies: List<SceneElement>,
        canvas: AnnotationCanvas,
        reserved: List<AnnotationRect>,
        fontScale: Double,
        tapMinDp: Double,
        format: LengthFormat = LengthFormat.FEET_INCHES,
        textOnlyReserved: List<AnnotationRect> = emptyList(),
    ): List<PlacedDimension> {
        if (canvas.width <= 0.0 || canvas.height <= 0.0) return emptyList()
        val centreX = canvas.width / 2
        val centreY = canvas.height / 2
        val bodyBoxes = bodies.filter { it !is FrameElement }.map { AnnotationBody(it.id, screenBox(it, canvas), (it as? LineElement)?.let { l -> lineSegment(l, canvas) }) }
        val authored = AuthoredDimensions(bodies)
        val stated = authored.stated(canvas, format) { spansOf(it, canvas) }
        val ordered = candidates
            .filter(authored::isMeasured)
            .map { it to screenBox(it, canvas) }
            .sortedWith(compareBy({ hypot(it.second.centreX - centreX, it.second.centreY - centreY) }, { it.first.id.value }))
        val placed = ArrayList<AnnotationPlaced>()
        val out = ArrayList<PlacedDimension>()
        var shapes = 0
        var attempts = 0
        for ((element, selfBox) in ordered) {
            if (shapes >= MAX_ANNOTATED_SHAPES || attempts++ >= MAX_ANNOTATED_SHAPES * ATTEMPT_FACTOR) break
            val name = ElementNames.nameOf(element)
            var annotated = false
            for (span in spansOf(element, canvas)) {
                if (MeasureRef(element.id, span.label, span.tag) in stated) continue
                val text = valueText(span.label, span.sixteenths, format, span.exact)
                val (tw, th) = textSize(text, fontScale)
                val extent = span.textExtent(tw, th)
                val alpha = ((span.lengthDp() / extent - FADE_START_RATIO) / (FADE_FULL_RATIO - FADE_START_RATIO)).coerceIn(0.0, 1.0)
                if (alpha < MIN_ALPHA) continue
                val at = AnnotationPlacer.place(span, AnnotationPlacer.Fit(canvas, reserved, textOnlyReserved, placed, bodyBoxes, element.id, selfBox, tw, th, extent)) ?: continue
                placed += at
                annotated = true
                out += PlacedDimension(element.id, name, span.label, span.sixteenths, text, at.line, at.extensions, at.text, at.text, alpha, span.tag)
            }
            if (annotated) shapes++
        }
        val taken = placed.map { it.text } + reserved + textOnlyReserved
        out += RoomAreaLabels.layout(candidates, canvas, fontScale, format, taken)
        return withTapRects(out, reserved, canvas, tapMinDp)
    }

    private fun lineSegment(line: LineElement, canvas: AnnotationCanvas): AnnotationSegment? {
        if (line.points.size != TWO_POINT_LINE) return null
        val pts = line.points.map { canvas.toScreenDp(it.x.value, it.y.value) }
        return AnnotationSegment(AnnotationPoint(pts[0].first, pts[0].second), AnnotationPoint(pts[1].first, pts[1].second))
    }

    private fun screenBox(e: SceneElement, canvas: AnnotationCanvas): AnnotationRect {
        val b = ElementBounds.geometry(e)
        val (x0, y0) = canvas.toScreenDp(b.minX, b.minY)
        val (x1, y1) = canvas.toScreenDp(b.maxX, b.maxY)
        return AnnotationRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    }

    private fun spansOf(element: SceneElement, canvas: AnnotationCanvas): List<AnnotationSpan> {
        if (element is LineElement) return lineSpan(element, canvas)?.let { listOf(it) }.orEmpty()
        if (element is WallElement) return wallSpans(element, canvas)
        val s = ElementFrame.of(element) ?: return emptyList()
        val specs = Measurements.of(element).filter { it.kind != MeasureKind.Radius }
        if (specs.isEmpty()) return emptyList()
        val (cx, cy) = canvas.toScreenDp(s.x + s.width / 2, s.y + s.height / 2)
        val angle = s.angle
        val hw = s.width / 2 * canvas.dpPerUnit
        val hh = s.height / 2 * canvas.dpPerUnit
        val u = AnnotationPoint(cos(angle), sin(angle))
        val v = AnnotationPoint(-sin(angle), cos(angle))
        val rect = element is RectElement
        return specs.map { spec ->
            if (spec.kind == MeasureKind.Height) {
                val p = AnnotationPoint(cx - v.x * hh, cy - v.y * hh)
                val q = AnnotationPoint(cx + v.x * hh, cy + v.y * hh)
                AnnotationSpan(spec.kind, spec.sixteenths, p, q, preferred(listOf(u, neg(u)), wantRight = true), hw, if (rect) hw else 0.0, spec.exact)
            } else {
                val p = AnnotationPoint(cx - u.x * hw, cy - u.y * hw)
                val q = AnnotationPoint(cx + u.x * hw, cy + u.y * hw)
                AnnotationSpan(spec.kind, spec.sixteenths, p, q, preferred(listOf(neg(v), v), wantRight = false), hh, if (rect) hh else 0.0, spec.exact)
            }
        }
    }

    /**
     * One length per segment of [wall], measured along its drawn line and placed on the outer side (the side away from the room) so the
     * value does not sit inside the room; its extension lines start beyond the wall body.
     */
    private fun wallSpans(wall: WallElement, canvas: AnnotationCanvas): List<AnnotationSpan> {
        val offsets = WallGeometry.offsets(wall)
        val interiorLeft = WallGeometry.interiorIsLeft(wall.points, wall.closed)
        val reach = max(offsets.left, offsets.right) * canvas.dpPerUnit
        return WallGeometry.segments(wall).mapNotNull { seg ->
            val a = canvas.toScreenDp(seg.a.x, seg.a.y)
            val b = canvas.toScreenDp(seg.b.x, seg.b.y)
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len = hypot(dx, dy).takeIf { it > 0.0 } ?: return@mapNotNull null
            val left = AnnotationPoint(dy / len, -dx / len)
            val outer = if (interiorLeft) neg(left) else left
            val sixteenths = max(1L, kotlin.math.round(seg.length).toLong())
            AnnotationSpan(
                MeasureKind.Length, sixteenths, AnnotationPoint(a.first, a.second), AnnotationPoint(b.first, b.second), listOf(outer, neg(outer)),
                reach, reach, seg.length, "s${seg.index}",
            )
        }
    }

    private fun lineSpan(line: LineElement, canvas: AnnotationCanvas): AnnotationSpan? {
        val length = Measurements.lineLength(line) ?: return null
        if (line.polygon || line.points.size != TWO_POINT_LINE) return null
        val (a, b) = line.points.map { canvas.toScreenDp(it.x.value, it.y.value) }
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len = hypot(dx, dy).takeIf { it > 0.0 } ?: return null
        val n = AnnotationPoint(dy / len, -dx / len)
        val sixteenths = max(1L, kotlin.math.round(length).toLong())
        return AnnotationSpan(MeasureKind.Length, sixteenths, AnnotationPoint(a.first, a.second), AnnotationPoint(b.first, b.second), preferred(listOf(n, neg(n)), wantRight = true), 0.0, 0.0, length)
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
    private fun withTapRects(annotations: List<PlacedDimension>, reserved: List<AnnotationRect>, canvas: AnnotationCanvas, tapMinDp: Double): List<PlacedDimension> {
        val taps = annotations.map { a ->
            val t = a.textRect
            if (a.alpha < TAP_MIN_ALPHA) return@map t
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
                if (annotations[i].alpha >= TAP_MIN_ALPHA) taps[i] = ci
                if (annotations[j].alpha >= TAP_MIN_ALPHA) taps[j] = cj
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
}

/** Another shape an annotation keeps off: a line blocks only along its segment, any other shape by its box unless that box encloses the annotated shape. */
internal class AnnotationBody(val id: ElementId, val box: AnnotationRect, val segment: AnnotationSegment?) {
    fun blocks(text: AnnotationRect, self: AnnotationRect): Boolean = when {
        segment != null -> text.crosses(segment)
        else -> box.overlaps(text) && !box.contains(self)
    }
}

/** A placed annotation before its target is sized: the text rectangle, the dimension line and its extension lines. */
internal class AnnotationPlaced(val text: AnnotationRect, val line: AnnotationSegment, val extensions: List<AnnotationSegment>)

internal class AnnotationSpan(val label: MeasureKind, val sixteenths: Long, val p: AnnotationPoint, val q: AnnotationPoint, val normals: List<AnnotationPoint>, val reach: Double, val extStart: Double, val exact: Double = sixteenths.toDouble(), val tag: String = "") {
    fun lengthDp(): Double = hypot(q.x - p.x, q.y - p.y)

    /** How much of the line the text covers: its width along a horizontal run, its height along a vertical one. */
    fun textExtent(tw: Double, th: Double): Double {
        val len = lengthDp().takeIf { it > 0.0 } ?: return tw
        return abs(q.x - p.x) / len * tw + abs(q.y - p.y) / len * th
    }
}

/** Chooses where one span's line and text go: outside on the preferred side in stacked rows, else inside a rectangle; null when neither is free of other shapes. */
internal object AnnotationPlacer {
    private const val HALF = DimensionAnnotationEngine.HALF

    /** What a placement is checked against: the canvas, the free-space reservations, the annotations already placed and the other shapes' boxes. */
    internal class Fit(val canvas: AnnotationCanvas, val reserved: List<AnnotationRect>, val textOnlyReserved: List<AnnotationRect>, val placed: List<AnnotationPlaced>, val bodies: List<AnnotationBody>, val self: ElementId, val selfBox: AnnotationRect, val tw: Double, val th: Double, val extent: Double)

    /**
     * The spot for [span]'s line and text: outside the shape on the preferred side, then the other, stacking outward in rows; else,
     * for a rectangle, just inside its own edge. A spot that lands on another shape is never used, so a value cannot read as that
     * shape's; null means the annotation is dropped.
     */
    internal fun place(span: AnnotationSpan, fit: Fit): AnnotationPlaced? {
        val outside = span.normals.flatMap { n -> (0 until DimensionAnnotationEngine.MAX_ROWS).map { row -> offsetOf(span, n, row, fit) to false } }
        val inner = if (span.extStart > 0.0) listOf(innerOffset(span, fit) to true) else emptyList()
        return (outside + inner).firstNotNullOfOrNull { (line, isInner) -> firstFree(span, line, isInner, fit) }
    }

    private fun offsetOf(span: AnnotationSpan, n: AnnotationPoint, row: Int, fit: Fit): AnnotationSegment {
        val support = abs(n.x) * fit.tw / HALF + abs(n.y) * fit.th / HALF
        return shifted(span, n, span.reach + DimensionAnnotationEngine.EXT_GAP_DP + support + row * (2 * support + DimensionAnnotationEngine.ROW_GAP_DP))
    }

    /** A line just inside the shape's edge on the preferred side; empty-width when the shape is too small to hold its text. */
    private fun innerOffset(span: AnnotationSpan, fit: Fit): AnnotationSegment {
        val n = span.normals.first()
        val support = abs(n.x) * fit.tw / HALF + abs(n.y) * fit.th / HALF
        val fits = span.reach >= support + DimensionAnnotationEngine.EXT_GAP_DP
        return shifted(span, n, if (fits) span.reach - support - DimensionAnnotationEngine.EXT_GAP_DP else Double.NaN)
    }

    private fun shifted(span: AnnotationSpan, n: AnnotationPoint, off: Double) =
        AnnotationSegment(AnnotationPoint(span.p.x + n.x * off, span.p.y + n.y * off), AnnotationPoint(span.q.x + n.x * off, span.q.y + n.y * off))

    private fun firstFree(span: AnnotationSpan, line: AnnotationSegment, isInner: Boolean, fit: Fit): AnnotationPlaced? {
        if (line.a.x.isNaN()) return null
        val length = span.lengthDp()
        val extensions = if (isInner) emptyList() else DimensionAnnotationEngine.extensionsOf(span, line)
        for (t in DimensionAnnotationEngine.TEXT_POSITIONS) {
            if (t * length < fit.extent / HALF || (1 - t) * length < fit.extent / HALF) continue
            val cx = line.a.x + (line.b.x - line.a.x) * t
            val cy = line.a.y + (line.b.y - line.a.y) * t
            val text = AnnotationRect(cx - fit.tw / HALF, cy - fit.th / HALF, cx + fit.tw / HALF, cy + fit.th / HALF)
            if (inside(text, fit.canvas) && clear(text, listOf(line) + extensions, fit)) return AnnotationPlaced(text, line, extensions)
        }
        return null
    }

    private fun inside(text: AnnotationRect, canvas: AnnotationCanvas): Boolean =
        text.left >= DimensionAnnotationEngine.EDGE_DP && text.top >= DimensionAnnotationEngine.EDGE_DP && text.right <= canvas.width - DimensionAnnotationEngine.EDGE_DP && text.bottom <= canvas.height - DimensionAnnotationEngine.EDGE_DP

    private fun clear(text: AnnotationRect, strokes: List<AnnotationSegment>, fit: Fit): Boolean {
        val padded = text.inflated(DimensionAnnotationEngine.TEXT_GAP_DP)
        if (fit.bodies.any { it.id != fit.self && it.blocks(text, fit.selfBox) }) return false
        if (fit.textOnlyReserved.any { it.overlaps(padded) }) return false
        if (fit.reserved.any { r -> r.overlaps(padded) || strokes.any { r.crosses(it) } }) return false
        return fit.placed.none { p ->
            val theirs = listOf(p.line) + p.extensions
            p.text.overlaps(padded) || theirs.any { padded.crosses(it) } || strokes.any { p.text.inflated(DimensionAnnotationEngine.TEXT_GAP_DP).crosses(it) }
        }
    }
}
