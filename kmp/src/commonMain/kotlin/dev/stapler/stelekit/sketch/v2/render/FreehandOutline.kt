// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
//
// Clean-room Kotlin port of perfect-freehand 1.2.3 (getStrokePoints + getStrokeOutlinePoints, without tapers),
// MIT License, Copyright (c) 2021 Stephen Ruiz Ltd. The license text is in the repository NOTICE file.

package dev.stapler.stelekit.sketch.v2.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One input sample; [pressure] 0..1 (the upstream default 0.5 stands in for a missing reading). */
data class FreehandPoint(val x: Double, val y: Double, val pressure: Double = DEFAULT_PRESSURE) {
    companion object {
        const val DEFAULT_PRESSURE = 0.5
    }
}

/** One vertex of a stroke outline polygon. */
data class OutlinePoint(val x: Double, val y: Double)

/** perfect-freehand options; the defaults are the library's, Excalidraw's own are [excalidraw]. */
data class FreehandOptions(
    val size: Double = 16.0,
    val thinning: Double = 0.5,
    val smoothing: Double = 0.5,
    val streamline: Double = 0.5,
    val simulatePressure: Boolean = true,
    /** True when the stroke is finished: the last input point is kept exactly, not eased toward. */
    val last: Boolean = false,
    val easing: (Double) -> Double = { it },
) {
    companion object {
        private const val EXCALIDRAW_SIZE_PER_STROKE_WIDTH = 4.25
        private const val EXCALIDRAW_THINNING = 0.6

        /** The options Excalidraw 0.18 draws a freedraw element with, for a stroke of [strokeWidth]. */
        fun excalidraw(strokeWidth: Double, simulatePressure: Boolean) = FreehandOptions(
            size = strokeWidth * EXCALIDRAW_SIZE_PER_STROKE_WIDTH,
            thinning = EXCALIDRAW_THINNING,
            simulatePressure = simulatePressure,
            last = true,
            easing = FreehandOutline::easeOutSine,
        )
    }
}

object FreehandOutline {
    private const val RATE_OF_PRESSURE_CHANGE = 0.275
    private const val FIXED_PI = PI + 0.0001
    private const val START_CAP_SEGMENTS = 13.0
    private const val CORNER_CAP_SEGMENTS = 13.0
    private const val END_CAP_SEGMENTS = 29.0
    private const val MIN_STREAMLINE_T = 0.15
    private const val STREAMLINE_T_RANGE = 0.85
    private const val SHORT_INPUT_FILL = 4
    private const val TWO_POINT_INPUT = 2
    private const val PRESSURE_WARMUP_POINTS = 10
    private const val TAIL_SKIP_DISTANCE = 3.0
    private const val MIN_RADIUS = 0.01
    private const val END_CAP_TURNS = 3.0
    private const val ABSENT_PRESSURE = -1.0
    private const val FIRST_PRESSURE_FALLBACK = 0.25
    private const val MID = 0.5
    private const val QUAD_TO_CUBIC = 2.0 / 3.0

    fun easeOutSine(t: Double): Double = sin(t * PI / 2.0)

    private class StrokePoint(val point: Pt, val pressure: Double, var vector: Pt, val distance: Double, val runningLength: Double)

    private class Sample(val x: Double, val y: Double, val pressure: Double)

    /** The closed outline polygon of the stroke through [points]; empty for no points or a non-positive size. */
    fun build(points: List<FreehandPoint>, options: FreehandOptions): List<OutlinePoint> =
        buildPts(points, options).map { OutlinePoint(it.x, it.y) }

    internal fun buildPts(points: List<FreehandPoint>, options: FreehandOptions): List<Pt> =
        outline(strokePoints(points, options), options)

    private fun lrp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    private fun dist2(a: Pt, b: Pt): Double = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
    private fun dist(a: Pt, b: Pt): Double = sqrt(dist2(a, b))
    private fun uni(a: Pt): Pt {
        val len = a.length()
        return Pt(a.x / len, a.y / len)
    }
    private fun per(a: Pt) = Pt(a.y, -a.x)
    private fun dpr(a: Pt, b: Pt): Double = a.x * b.x + a.y * b.y
    private fun neg(a: Pt) = Pt(-a.x, -a.y)

    private fun rotAround(a: Pt, c: Pt, r: Double): Pt {
        val s = sin(r)
        val co = cos(r)
        val px = a.x - c.x
        val py = a.y - c.y
        return Pt(px * co - py * s + c.x, px * s + py * co + c.y)
    }

    private fun strokeRadius(size: Double, thinning: Double, pressure: Double, easing: (Double) -> Double): Double =
        size * easing(MID - thinning * (MID - pressure))

    private fun strokePoints(input: List<FreehandPoint>, options: FreehandOptions): List<StrokePoint> {
        if (input.isEmpty()) return emptyList()
        val t = MIN_STREAMLINE_T + (1.0 - options.streamline) * STREAMLINE_T_RANGE
        var pts = input.map { Sample(it.x, it.y, it.pressure) }
        if (pts.size == TWO_POINT_INPUT) {
            val first = pts[0]
            val last = Pt(pts[1].x, pts[1].y)
            val filled = ArrayList<Sample>()
            filled.add(first)
            for (i in 1..SHORT_INPUT_FILL) {
                val p = lrp(Pt(first.x, first.y), last, i / SHORT_INPUT_FILL.toDouble())
                filled.add(Sample(p.x, p.y, ABSENT_PRESSURE))
            }
            pts = filled
        }
        if (pts.size == 1) pts = listOf(pts[0], Sample(pts[0].x + 1.0, pts[0].y + 1.0, pts[0].pressure))
        val first = pts[0]
        val out = ArrayList<StrokePoint>()
        var prev = StrokePoint(
            Pt(first.x, first.y), if (first.pressure >= 0.0) first.pressure else FIRST_PRESSURE_FALLBACK, Pt(1.0, 1.0), 0.0, 0.0,
        )
        out.add(prev)
        var reachedMinimum = false
        var running = 0.0
        val lastIndex = pts.size - 1
        for (i in 1 until pts.size) {
            val s = pts[i]
            val point = if (options.last && i == lastIndex) Pt(s.x, s.y) else lrp(prev.point, Pt(s.x, s.y), t)
            if (prev.point.x == point.x && prev.point.y == point.y) continue
            val distance = dist(point, prev.point)
            running += distance
            if (i < lastIndex && !reachedMinimum) {
                if (running < options.size) continue
                reachedMinimum = true
            }
            prev = StrokePoint(point, if (s.pressure >= 0.0) s.pressure else FreehandPoint.DEFAULT_PRESSURE, uni(prev.point - point), distance, running)
            out.add(prev)
        }
        out[0].vector = if (out.size > 1) out[1].vector else Pt(0.0, 0.0)
        return out
    }

    private fun outline(points: List<StrokePoint>, o: FreehandOptions): List<Pt> {
        if (points.isEmpty() || o.size <= 0.0) return emptyList()
        val totalLength = points.last().runningLength
        val minDistance = (o.size * o.smoothing) * (o.size * o.smoothing)
        val left = ArrayList<Pt>()
        val right = ArrayList<Pt>()
        var prevPressure = points.take(PRESSURE_WARMUP_POINTS).fold(points[0].pressure) { acc, p ->
            var pressure = p.pressure
            if (o.simulatePressure) {
                val sp = min(1.0, p.distance / o.size)
                val rp = min(1.0, 1.0 - sp)
                pressure = min(1.0, acc + (rp - acc) * (sp * RATE_OF_PRESSURE_CHANGE))
            }
            (acc + pressure) / 2.0
        }
        var radius = strokeRadius(o.size, o.thinning, points.last().pressure, o.easing)
        var firstRadius = Double.NaN
        var prevVector = points[0].vector
        var pl = points[0].point
        var pr = pl
        var tl = pl
        var tr = pr
        var prevWasSharp = false
        val lastIndex = points.size - 1
        for (i in points.indices) {
            val sp = points[i]
            var pressure = sp.pressure
            val point = sp.point
            val vector = sp.vector
            if (i < lastIndex && totalLength - sp.runningLength < TAIL_SKIP_DISTANCE) continue
            if (o.thinning != 0.0) {
                if (o.simulatePressure) {
                    val s = min(1.0, sp.distance / o.size)
                    val rp = min(1.0, 1.0 - s)
                    pressure = min(1.0, prevPressure + (rp - prevPressure) * (s * RATE_OF_PRESSURE_CHANGE))
                }
                radius = strokeRadius(o.size, o.thinning, pressure, o.easing)
            } else {
                radius = o.size / 2.0
            }
            if (firstRadius.isNaN()) firstRadius = radius
            radius = max(MIN_RADIUS, radius)
            val nextVector = (if (i < lastIndex) points[i + 1] else sp).vector
            val nextDpr = if (i < lastIndex) dpr(vector, nextVector) else 1.0
            val prevDpr = dpr(vector, prevVector)
            val sharp = prevDpr < 0.0 && !prevWasSharp
            val nextSharp = nextDpr < 0.0
            if (sharp || nextSharp) {
                val offset = per(prevVector) * radius
                val step = 1.0 / CORNER_CAP_SEGMENTS
                var t = 0.0
                while (t <= 1.0) {
                    tl = rotAround(point - offset, point, FIXED_PI * t)
                    left.add(tl)
                    tr = rotAround(point + offset, point, FIXED_PI * -t)
                    right.add(tr)
                    t += step
                }
                pl = tl
                pr = tr
                if (nextSharp) prevWasSharp = true
                continue
            }
            prevWasSharp = false
            if (i == lastIndex) {
                val offset = per(vector) * radius
                left.add(point - offset)
                right.add(point + offset)
                continue
            }
            val offset = per(lrp(nextVector, vector, nextDpr)) * radius
            tl = point - offset
            if (i <= 1 || dist2(pl, tl) > minDistance) {
                left.add(tl)
                pl = tl
            }
            tr = point + offset
            if (i <= 1 || dist2(pr, tr) > minDistance) {
                right.add(tr)
                pr = tr
            }
            prevPressure = pressure
            prevVector = vector
        }
        val firstPoint = points[0].point
        val lastPoint = if (points.size > 1) points.last().point else Pt(firstPoint.x + 1.0, firstPoint.y + 1.0)
        val step = 1.0 / START_CAP_SEGMENTS
        var t = step
        if (points.size == 1) {
            val start = firstPoint + uni(per(firstPoint - lastPoint)) * (-(if (firstRadius.isNaN()) radius else firstRadius))
            val dot = ArrayList<Pt>()
            while (t <= 1.0) {
                dot.add(rotAround(start, firstPoint, FIXED_PI * 2.0 * t))
                t += step
            }
            return dot
        }
        val startCap = ArrayList<Pt>()
        while (t <= 1.0) {
            startCap.add(rotAround(right[0], firstPoint, FIXED_PI * t))
            t += step
        }
        val endCap = ArrayList<Pt>()
        val endStart = lastPoint + per(neg(points.last().vector)) * radius
        val endStep = 1.0 / END_CAP_SEGMENTS
        t = endStep
        while (t < 1.0) {
            endCap.add(rotAround(endStart, lastPoint, FIXED_PI * END_CAP_TURNS * t))
            t += endStep
        }
        return left + endCap + right.asReversed() + startCap
    }

    /**
     * The outline as a closed path of midpoint-joined quadratic curves (Excalidraw's `getSvgPathFromStroke`), each
     * quadratic written as the equivalent cubic because [PathSegment] has no quadratic.
     */
    internal fun toPath(outline: List<Pt>): List<PathSegment> {
        if (outline.isEmpty()) return emptyList()
        val b = PathBuilder()
        b.moveTo(outline[0])
        var from = outline[0]
        for (i in outline.indices) {
            val control = outline[i]
            val next = outline[(i + 1) % outline.size]
            val end = Pt((control.x + next.x) * MID, (control.y + next.y) * MID)
            b.cubicTo(from + (control - from) * QUAD_TO_CUBIC, end + (control - end) * QUAD_TO_CUBIC, end)
            from = end
        }
        return b.close().build()
    }
}
