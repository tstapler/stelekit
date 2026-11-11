// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.units.Rational
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round

/** Geometry rule names (Story 4.8); [GeometryExtractor] maps them to registry `svgGeom` aliases. */
object GeometryRule {
    const val BBOX_WIDTH = "bbox-width"
    const val BBOX_HEIGHT = "bbox-height"
    const val CIRCLE_CENTRE = "circle-centre"
    const val CIRCLE_DIAMETER = "circle-diameter"
    const val BAND_PITCH = "band-pitch"
    const val STRIP_POSITIONS = "strip-positions"

    /** The registry alias suffix a rule is published under (`<sheet>.<suffix>`). */
    fun aliasSuffix(rule: String): String = when (rule) {
        BAND_PITCH -> "shelf-pitch"
        STRIP_POSITIONS -> "standards"
        else -> rule
    }
}

/**
 * One value a rule read off the sheet, in exact inches. [tag] tells values of one rule apart (`""`
 * when the rule yields one value). [precision] is the half-width of the quantisation, or of the
 * polygon's own deviation for circles.
 */
data class GeometryMeasurement(val rule: String, val tag: String, val value: Rational, val precision: Rational)

/** Quantisation grain: values within this of a 1/64 step snap to it, others keep six decimals. */
const val GEOMETRY_EPSILON = 1e-6

private const val KEY_SCALE = 10_000.0
private const val SNAP_DENOMINATOR = 64L
private const val DECIMAL_DENOMINATOR = 1_000_000L
private const val TICK_MAX = 1_250L // a tick or strip is at most 1/8" thick
private const val STRIP_MIN_LENGTH = 100_000L // 10"
private const val MIN_CIRCLE_SEGMENTS = 8
private const val CIRCLE_TOLERANCE = 0.02
private const val JOIN_TOLERANCE = 1e-3
private const val MAX_ABS_INCHES = 1e9

/** Exact inches for [v]: a 1/64 step when within [GEOMETRY_EPSILON] of one, else six decimals; null if absurd. */
fun exactInches(v: Double): Rational? {
    if (!v.isFinite() || abs(v) > MAX_ABS_INCHES) return null
    val steps = round(v * SNAP_DENOMINATOR)
    if (abs(v - steps / SNAP_DENOMINATOR) <= GEOMETRY_EPSILON) return Rational(steps.toLong(), SNAP_DENOMINATOR)
    return Rational(round(v * DECIMAL_DENOMINATOR).toLong(), DECIMAL_DENOMINATOR)
}

private val EPSILON_PRECISION = Rational(1, DECIMAL_DENOMINATOR)

private data class Seg(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/** Integer box in key units (1/10 000"), so floating noise never splits a group. */
private data class Box(val x0: Long, val y0: Long, val x1: Long, val y1: Long) {
    val width get() = x1 - x0
    val height get() = y1 - y0
}

private fun key(v: Double): Long = round(v * KEY_SCALE).toLong()

private fun inches(keys: Double): Double = keys / KEY_SCALE

/** Segments of one drawn polyline, in y-up inches from the calibrated transform. */
private fun sheetPolylines(doc: SvgDoc, scale: Double): Either<CalibrationError, List<List<Seg>>> {
    val out = ArrayList<List<Seg>>()
    fun place(chain: List<String>, lines: List<SvgLine>): CalibrationError? {
        val m = composeTransforms(chain).fold({ return CalibrationError.Transform(it) }, { it })
        out += lines.map { l ->
            val (ax, ay) = m.apply(l.x1, l.y1)
            val (bx, by) = m.apply(l.x2, l.y2)
            Seg(ax / scale, -ay / scale, bx / scale, -by / scale)
        }
        return null
    }
    for (p in doc.paths) place(p.transforms, p.segments)?.let { return it.left() }
    for (l in doc.lines) place(l.transforms, listOf(l.line))?.let { return it.left() }
    for (r in doc.rects) {
        val edges = listOf(
            SvgLine(r.x, r.y, r.x + r.width, r.y), SvgLine(r.x + r.width, r.y, r.x + r.width, r.y + r.height),
            SvgLine(r.x + r.width, r.y + r.height, r.x, r.y + r.height), SvgLine(r.x, r.y + r.height, r.x, r.y),
        )
        place(r.transforms, edges)?.let { return it.left() }
    }
    return out.right()
}

private data class HKey(val y: Long, val x0: Long, val x1: Long)
private data class VKey(val x: Long, val y0: Long, val y1: Long)

private fun axisSegments(polylines: List<List<Seg>>): Pair<Set<HKey>, Set<VKey>> {
    val hs = HashSet<HKey>()
    val vs = HashSet<VKey>()
    for (s in polylines.flatten()) {
        if (abs(s.y1 - s.y2) < GEOMETRY_EPSILON && abs(s.x1 - s.x2) >= GEOMETRY_EPSILON) {
            hs += HKey(key((s.y1 + s.y2) / 2), minOf(key(s.x1), key(s.x2)), maxOf(key(s.x1), key(s.x2)))
        } else if (abs(s.x1 - s.x2) < GEOMETRY_EPSILON && abs(s.y1 - s.y2) >= GEOMETRY_EPSILON) {
            vs += VKey(key((s.x1 + s.x2) / 2), minOf(key(s.y1), key(s.y2)), maxOf(key(s.y1), key(s.y2)))
        }
    }
    return hs to vs
}

/** Axis-aligned closed four-sided boxes, found with one hash lookup per bottom edge (O(n)). */
private fun findBoxes(hs: Set<HKey>, vs: Set<VKey>): List<Box> {
    val verticalsFrom = vs.groupBy({ it.x to it.y0 }, { it.y1 })
    val boxes = ArrayList<Box>()
    for (bottom in hs) {
        for (top in verticalsFrom[bottom.x0 to bottom.y].orEmpty()) {
            if (HKey(top, bottom.x0, bottom.x1) in hs && VKey(bottom.x1, bottom.y, top) in vs) {
                boxes += Box(bottom.x0, bottom.y, bottom.x1, top)
            }
        }
    }
    return boxes.distinct().sortedWith(compareBy({ it.x0 }, { it.y0 }, { it.x1 }, { it.y1 }))
}

private fun quantised(rule: String, tag: String, v: Double): GeometryMeasurement? =
    exactInches(v)?.let { GeometryMeasurement(rule, tag, it, EPSILON_PRECISION) }

/**
 * Reads the six geometry rules off [doc] through its calibrated transform. Heights are measured
 * from the datum (the bottom of the longest vertical line); circle centres from the lower-left
 * corner of the drawn extent; both y-up. A rule that finds nothing yields nothing.
 *
 * - `bbox-width`: width of the widest closed box.
 * - `bbox-height`: tick marks (thin boxes sharing an x extent, at least two), heights above the
 *   datum, tallest first; tag `<group>.<index>`, groups by x descending.
 * - `band-pitch`: boxes of one size and x extent, at least three, evenly spaced; the spacing.
 * - `strip-positions`: the most numerous set of equal-height thin vertical boxes, at least three;
 *   offsets of their centres from the first strip on the view direction's origin side.
 * - `circle-centre` (tags `<i>.x`, `<i>.y`) and `circle-diameter` (`<i>`, radius times two):
 *   closed polylines of at least eight corners whose corners lie on a circle.
 */
fun measureGeometry(doc: SvgDoc, calibration: SheetCalibration): Either<CalibrationError, List<GeometryMeasurement>> {
    val scale = calibration.pxPerInchTransform
    if (!(scale > 0.0)) return CalibrationError.NoGeometry.left()
    val polylines = sheetPolylines(doc, scale).fold({ return it.left() }, { it })
    val (hs, vs) = axisSegments(polylines)
    val boxes = findBoxes(hs, vs)
    val out = ArrayList<GeometryMeasurement>()
    out += widthRule(boxes)
    out += heightRule(boxes, vs)
    out += pitchRule(boxes)
    out += stripRule(boxes, calibration.viewDirection)
    out += circleRules(polylines)
    return out.right()
}

private fun widthRule(boxes: List<Box>): List<GeometryMeasurement> {
    val widest = boxes.maxOfOrNull { it.width } ?: return emptyList()
    return listOfNotNull(quantised(GeometryRule.BBOX_WIDTH, "", inches(widest.toDouble())))
}

private fun heightRule(boxes: List<Box>, vs: Set<VKey>): List<GeometryMeasurement> {
    val longest = vs.maxWithOrNull(compareBy<VKey>({ it.y1 - it.y0 }).thenByDescending { it.y0 }) ?: return emptyList()
    val datum = longest.y0
    val ticks = boxes.filter { it.height <= TICK_MAX && it.width > 3 * it.height }.groupBy { it.x0 to it.x1 }
    return ticks.entries.filter { it.value.size >= 2 }.sortedByDescending { it.key.first }
        .flatMapIndexed { g, (_, group) ->
            group.map { (it.y0 + it.y1) / 2.0 - datum }.sortedDescending().mapIndexedNotNull { i, h ->
                quantised(GeometryRule.BBOX_HEIGHT, "$g.$i", inches(h))
            }
        }
}

private fun pitchRule(boxes: List<Box>): List<GeometryMeasurement> {
    val groups = boxes.groupBy { Triple(it.x0, it.x1, it.height) }.entries
        .map { it.key to it.value.map { b -> b.y0 }.distinct().sorted() }
        .filter { (_, ys) -> ys.size >= 3 && isEvenlySpaced(ys) }
        .sortedByDescending { it.first.first }
    return groups.mapIndexedNotNull { i, (_, ys) ->
        val pitch = (ys.last() - ys.first()).toDouble() / (ys.size - 1)
        quantised(GeometryRule.BAND_PITCH, if (groups.size == 1) "" else "$i", inches(pitch))
    }
}

private fun isEvenlySpaced(sorted: List<Long>): Boolean {
    val gaps = sorted.zipWithNext { a, b -> b - a }
    return gaps.all { abs(it - gaps.first()) <= 2 }
}

private fun stripRule(boxes: List<Box>, direction: ViewDirection): List<GeometryMeasurement> {
    val strips = boxes.filter { it.width <= TICK_MAX && it.height >= STRIP_MIN_LENGTH }.groupBy { it.y0 to it.y1 }
    val best = strips.entries.filter { it.value.size >= 3 }
        .sortedWith(compareBy({ -it.value.size }, { -(it.key.second - it.key.first) }, { it.key.first }))
        .firstOrNull() ?: return emptyList()
    val centres = best.value.map { (it.x0 + it.x1) / 2.0 }.sorted()
    val ordered = if (direction == ViewDirection.RIGHT_TO_LEFT) centres.reversed() else centres
    return ordered.mapIndexedNotNull { i, x ->
        quantised(GeometryRule.STRIP_POSITIONS, "$i", inches(abs(x - ordered.first())))
    }
}

private data class Circle(val cx: Double, val cy: Double, val radius: Double, val deviation: Double)

private fun asCircle(poly: List<Seg>): Circle? {
    if (poly.size < MIN_CIRCLE_SEGMENTS) return null
    val joined = poly.zipWithNext().all { (a, b) -> hypot(a.x2 - b.x1, a.y2 - b.y1) <= JOIN_TOLERANCE }
    val closed = hypot(poly.last().x2 - poly.first().x1, poly.last().y2 - poly.first().y1) <= JOIN_TOLERANCE
    if (!joined || !closed) return null
    val cx = poly.sumOf { it.x1 } / poly.size
    val cy = poly.sumOf { it.y1 } / poly.size
    val radii = poly.map { hypot(it.x1 - cx, it.y1 - cy) }
    val mean = radii.sum() / radii.size
    val deviation = radii.maxOf { abs(it - mean) }
    return if (mean > 0.0 && deviation <= CIRCLE_TOLERANCE * mean) Circle(cx, cy, mean, deviation) else null
}

private fun circleRules(polylines: List<List<Seg>>): List<GeometryMeasurement> {
    val circles = polylines.mapNotNull(::asCircle).sortedWith(compareBy({ it.cx }, { it.cy }))
    if (circles.isEmpty()) return emptyList()
    val allSegs = polylines.flatten()
    val left = allSegs.minOf { minOf(it.x1, it.x2) }
    val bottom = allSegs.minOf { minOf(it.y1, it.y2) }
    return circles.flatMapIndexed { i, c ->
        val grain = max(GEOMETRY_EPSILON, ceil(c.deviation * DECIMAL_DENOMINATOR) / DECIMAL_DENOMINATOR)
        val precision = Rational(round(grain * DECIMAL_DENOMINATOR).toLong(), DECIMAL_DENOMINATOR)
        val radius = Rational(round(c.radius * DECIMAL_DENOMINATOR).toLong(), DECIMAL_DENOMINATOR)
        val diameter = radius.multiply(Rational(2)).getOrNull()
        listOfNotNull(
            exactInches(c.cx - left)?.let { GeometryMeasurement(GeometryRule.CIRCLE_CENTRE, "$i.x", it, precision) },
            exactInches(c.cy - bottom)?.let { GeometryMeasurement(GeometryRule.CIRCLE_CENTRE, "$i.y", it, precision) },
            diameter?.let { GeometryMeasurement(GeometryRule.CIRCLE_DIAMETER, "$i", it, precision) },
        )
    }
}
