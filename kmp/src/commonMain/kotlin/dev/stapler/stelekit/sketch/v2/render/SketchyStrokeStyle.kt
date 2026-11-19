// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
//
// Own Kotlin port of the stroke subset of rough.js 4.6.6 (line, rectangle/polygon, ellipse, curve, path):
// MIT License, Copyright (c) 2019 Preet Shihn, https://github.com/rough-stuff/rough (see NOTICE). The order in
// which random numbers are drawn is part of the algorithm and is kept exactly, so a stored seed gives the output
// of rough.js (checked against kmp/src/commonTest/resources/excalidraw/rough-golden.json). The pattern fills
// (hachure, cross-hatch, zigzag) are in HachureFill.kt.

package dev.stapler.stelekit.sketch.v2.render

import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Seeded double-stroke, bowed, hand-drawn geometry (ADR-004). Roughness 0 returns the Clean outline unchanged. */
object SketchyStrokeStyle : StrokeStyleProvider {
    override val style: RenderStyle = RenderStyle.Sketchy

    override fun outline(request: StrokeRequest, clean: List<PathSegment>): List<PathSegment> = stroke(request, clean).outline

    override fun stroke(request: StrokeRequest, clean: List<PathSegment>): StyledStroke {
        if (request.roughness <= 0) return StyledStroke(clean, null)
        val rough = RoughStrokes(request.seed, request.roughness.toDouble())
        val drawn = when (val g = request.geometry) {
            is StrokeGeometry.Polyline -> rough.polyline(g.points, g.closed)
            is StrokeGeometry.Curve -> rough.curve(g.points)
            is StrokeGeometry.Ellipse -> rough.ellipse(g.cx, g.cy, g.rx * 2.0, g.ry * 2.0)
            StrokeGeometry.Outline -> rough.path(clean)
        }
        if (drawn.isEmpty()) return StyledStroke(clean, null)
        val hatch = request.fill?.let { fill -> hatchPolygons(request.geometry, clean, rough)?.let { rough.fill(fill, it) } }
        return StyledStroke(
            rotate(drawn, request.angle, request.pivotX, request.pivotY),
            hatch?.let { rotate(it, request.angle, request.pivotX, request.pivotY) },
        )
    }

    /** The polygons rough.js hatches for [geometry]; open lines and smooth curves have none. */
    private fun hatchPolygons(geometry: StrokeGeometry, clean: List<PathSegment>, rough: RoughStrokes): List<List<ScenePt>>? = when (geometry) {
        is StrokeGeometry.Polyline -> if (geometry.closed) listOf(geometry.points) else null
        is StrokeGeometry.Ellipse -> listOf(rough.ellipseCorePoints)
        StrokeGeometry.Outline -> HachureFill.polygonsOf(clean)
        is StrokeGeometry.Curve -> null
    }

    private fun rotate(segments: List<PathSegment>, angle: Double, px: Double, py: Double): List<PathSegment> {
        if (angle == 0.0) return segments
        val s = sin(angle)
        val c = cos(angle)
        fun x(x: Double, y: Double) = px + (x - px) * c - (y - py) * s
        fun y(x: Double, y: Double) = py + (x - px) * s + (y - py) * c
        return segments.map {
            when (it) {
                is PathSegment.MoveTo -> PathSegment.MoveTo(x(it.x, it.y), y(it.x, it.y))
                is PathSegment.LineTo -> PathSegment.LineTo(x(it.x, it.y), y(it.x, it.y))
                is PathSegment.CubicTo -> PathSegment.CubicTo(
                    x(it.c1x, it.c1y), y(it.c1x, it.c1y), x(it.c2x, it.c2y), y(it.c2x, it.c2y), x(it.x, it.y), y(it.x, it.y),
                )
                PathSegment.Close -> PathSegment.Close
            }
        }
    }
}

/** One element's worth of rough.js generation: owns the PRNG, so strokes of one request draw one sequence. */
internal class RoughStrokes(private val seed: Int, private val roughness: Double) {
    internal val random = SeededRandom(seed)

    /** The jittered first-pass ring points of the last [ellipse] call, which rough.js hatches (`estimatedPoints`). */
    var ellipseCorePoints: List<ScenePt> = emptyList()
        private set

    fun polyline(points: List<ScenePt>, closed: Boolean): List<PathSegment> {
        val n = points.size
        if (n > MIN_POLYGON_POINTS) {
            val out = ArrayList<PathSegment>()
            for (i in 0 until n - 1) out += doubleLine(points[i], points[i + 1])
            if (closed) out += doubleLine(points[n - 1], points[0])
            return out
        }
        return if (n == MIN_POLYGON_POINTS) doubleLine(points[0], points[1]) else emptyList()
    }

    /** rough.js `curve` for one point list; the second pass draws from its own generator seeded with `seed + 1`. */
    fun curve(points: List<ScenePt>): List<PathSegment> {
        if (points.isEmpty()) return emptyList()
        val first = curvePass(points, 1.0 * (1.0 + AMPLITUDE_PER_ROUGHNESS * roughness), random)
        val second = curvePass(points, SECOND_PASS_SCALE * (1.0 + SECOND_PASS_PER_ROUGHNESS * roughness), SeededRandom(seed + 1))
        return first + second
    }

    fun ellipse(cx: Double, cy: Double, width: Double, height: Double): List<PathSegment> {
        val avg = sqrt(2.0 * PI * sqrt(((width / 2.0).sq() + (height / 2.0).sq()) / 2.0))
        val steps = ceil(max(STEP_COUNT, STEP_COUNT / sqrt(STEP_REFERENCE) * avg))
        val increment = 2.0 * PI / steps
        val fitting = 1.0 - CURVE_FITTING
        var rx = abs(width / 2.0)
        var ry = abs(height / 2.0)
        rx += offset(rx * fitting, random)
        ry += offset(ry * fitting, random)
        val overlap = increment * between(OVERLAP_LOW, between(OVERLAP_INNER_LOW, 1.0, random.next()), random.next())
        val core = ArrayList<ScenePt>()
        val firstPass = ellipsePoints(increment, cx, cy, rx, ry, 1.0, overlap, core)
        ellipseCorePoints = core
        val out = ArrayList<PathSegment>(through(firstPass))
        val secondPass = ellipsePoints(increment, cx, cy, rx, ry, SECOND_PASS_SCALE, 0.0, null)
        out += through(secondPass)
        return out
    }

    /** rough.js `path` stroke: lines doubled, cubics wobbled, close drawn back to the sub-path start. */
    fun path(clean: List<PathSegment>): List<PathSegment> {
        val out = ArrayList<PathSegment>()
        var curX = 0.0
        var curY = 0.0
        var startX = 0.0
        var startY = 0.0
        for (seg in clean) {
            when (seg) {
                is PathSegment.MoveTo -> {
                    curX = seg.x; curY = seg.y; startX = seg.x; startY = seg.y
                }
                is PathSegment.LineTo -> {
                    out += doubleLine(ScenePt(curX, curY), ScenePt(seg.x, seg.y))
                    curX = seg.x; curY = seg.y
                }
                is PathSegment.CubicTo -> {
                    out += wobbledCubic(seg, curX, curY)
                    curX = seg.x; curY = seg.y
                }
                PathSegment.Close -> {
                    out += doubleLine(ScenePt(curX, curY), ScenePt(startX, startY))
                    curX = startX; curY = startY
                }
            }
        }
        return out
    }

    /** rough.js pattern fill of [polygons]: hatch lines drawn as double lines from the same random sequence as the stroke. */
    fun fill(request: FillRequest, polygons: List<List<ScenePt>>): List<PathSegment> {
        val out = ArrayList<PathSegment>()
        fun render(lines: List<HatchLine>) {
            for (l in lines) out += doubleLine(l.from, l.to)
        }
        fun pass(angle: Double): List<HatchLine> = HachureFill.lines(polygons, request.gap, angle, roughness) { random.next() }
        when (request.style) {
            FillStyle.Hachure -> render(pass(request.angleDegrees))
            FillStyle.CrossHatch -> {
                render(pass(request.angleDegrees))
                render(pass(request.angleDegrees + QUARTER_TURN))
            }
            FillStyle.Zigzag -> render(HachureFill.zigzag(pass(request.angleDegrees), max(request.gap, MIN_FILL_GAP), request.angleDegrees))
            FillStyle.Solid -> Unit
        }
        return out
    }

    // -- rough.js `$`, `R`: two strokes per line, each a bowed cubic with a jittered start ----------------------

    internal fun doubleLine(a: ScenePt, b: ScenePt): List<PathSegment> =
        lineStroke(a, b, second = false) + lineStroke(a, b, second = true)

    private fun lineStroke(a: ScenePt, b: ScenePt, second: Boolean): List<PathSegment> {
        val t = a.x
        val e = a.y
        val s = b.x
        val n = b.y
        val squared = (t - s).sq() + (e - n).sq()
        val length = sqrt(squared)
        val scale = when {
            length < SHORT_LINE -> 1.0
            length > LONG_LINE -> LONG_LINE_SCALE
            else -> LINE_SCALE_SLOPE * length + LINE_SCALE_BASE
        }
        var spread = OFFSET_AMPLITUDE
        if (spread * spread * SPREAD_GUARD > squared) spread = length / SHORT_LINE_DIVISOR
        val half = spread / 2.0
        val p = LOW_FRACTION + LOW_FRACTION * random.next()
        var bowY = BOWING * OFFSET_AMPLITUDE * (n - e) / BOW_DIVISOR
        var bowX = BOWING * OFFSET_AMPLITUDE * (t - s) / BOW_DIVISOR
        bowY = offset(bowY, random, scale)
        bowX = offset(bowX, random, scale)
        val amplitude = if (second) half else spread
        fun jitter() = offset(amplitude, random, scale)
        val startX = t + jitter()
        val startY = e + jitter()
        val c1x = bowY + t + (s - t) * p + jitter()
        val c1y = bowX + e + (n - e) * p + jitter()
        val c2x = bowY + t + 2.0 * (s - t) * p + jitter()
        val c2y = bowX + e + 2.0 * (n - e) * p + jitter()
        val endX = s + jitter()
        val endY = n + jitter()
        return listOf(PathSegment.MoveTo(startX, startY), PathSegment.CubicTo(c1x, c1y, c2x, c2y, endX, endY))
    }

    // -- rough.js `Z`: a cubic drawn twice with jittered control points -----------------------------------------

    private fun wobbledCubic(c: PathSegment.CubicTo, fromX: Double, fromY: Double): List<PathSegment> {
        val out = ArrayList<PathSegment>()
        val amplitudes = doubleArrayOf(OFFSET_AMPLITUDE, OFFSET_AMPLITUDE + CUBIC_SECOND_EXTRA)
        for (pass in 0 until CUBIC_PASSES) {
            if (pass == 0) {
                out += PathSegment.MoveTo(fromX, fromY)
            } else {
                val mx = fromX + offset(amplitudes[0], random)
                val my = fromY + offset(amplitudes[0], random)
                out += PathSegment.MoveTo(mx, my)
            }
            val ex = c.x + offset(amplitudes[pass], random)
            val ey = c.y + offset(amplitudes[pass], random)
            val x1 = c.c1x + offset(amplitudes[pass], random)
            val y1 = c.c1y + offset(amplitudes[pass], random)
            val x2 = c.c2x + offset(amplitudes[pass], random)
            val y2 = c.c2y + offset(amplitudes[pass], random)
            out += PathSegment.CubicTo(x1, y1, x2, y2, ex, ey)
        }
        return out
    }

    // -- rough.js `j`, `q`: a smooth curve through jittered points ----------------------------------------------

    private fun curvePass(points: List<ScenePt>, amplitude: Double, rng: SeededRandom): List<PathSegment> {
        fun jittered(p: ScenePt) = ScenePt(p.x + offset(amplitude, rng), p.y + offset(amplitude, rng))
        val jit = ArrayList<ScenePt>()
        jit += jittered(points[0])
        jit += jittered(points[0])
        for (i in 1 until points.size) {
            jit += jittered(points[i])
            if (i == points.size - 1) jit += jittered(points[i])
        }
        return through(jit)
    }

    /** rough.js `q` with the tightness fixed at 0 and no trailing point. */
    private fun through(pts: List<ScenePt>): List<PathSegment> {
        val n = pts.size
        val out = ArrayList<PathSegment>()
        when {
            n > CATMULL_MIN_POINTS -> {
                val h = 1.0 - CURVE_TIGHTNESS
                out += PathSegment.MoveTo(pts[1].x, pts[1].y)
                var i = 1
                while (i + 2 < n) {
                    val s = pts[i]
                    val c1x = s.x + (h * pts[i + 1].x - h * pts[i - 1].x) / TANGENT_DIVISOR
                    val c1y = s.y + (h * pts[i + 1].y - h * pts[i - 1].y) / TANGENT_DIVISOR
                    val c2x = pts[i + 1].x + (h * pts[i].x - h * pts[i + 2].x) / TANGENT_DIVISOR
                    val c2y = pts[i + 1].y + (h * pts[i].y - h * pts[i + 2].y) / TANGENT_DIVISOR
                    out += PathSegment.CubicTo(c1x, c1y, c2x, c2y, pts[i + 1].x, pts[i + 1].y)
                    i++
                }
            }
            n == CATMULL_MIN_POINTS -> {
                out += PathSegment.MoveTo(pts[1].x, pts[1].y)
                out += PathSegment.CubicTo(pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[2].x, pts[2].y)
            }
            n == MIN_POLYGON_POINTS -> out += lineStroke(pts[0], pts[1], second = true)
        }
        return out
    }

    // -- rough.js `F`, `D`: the jittered point ring of an ellipse -----------------------------------------------

    private fun ellipsePoints(
        increment: Double, cx: Double, cy: Double, rx: Double, ry: Double, amplitude: Double, overlap: Double,
        core: MutableList<ScenePt>?,
    ): List<ScenePt> {
        val ring = ArrayList<ScenePt>()
        val start = offset(START_JITTER, random) - PI / 2.0
        ring += ScenePt(
            offset(amplitude, random) + cx + INNER_START * rx * cos(start - increment),
            offset(amplitude, random) + cy + INNER_START * ry * sin(start - increment),
        )
        val end = 2.0 * PI + start - END_TRIM
        var angle = start
        while (angle < end) {
            ring += ScenePt(offset(amplitude, random) + cx + rx * cos(angle), offset(amplitude, random) + cy + ry * sin(angle))
            core?.add(ring.last())
            angle += increment
        }
        ring += ScenePt(
            offset(amplitude, random) + cx + rx * cos(start + 2.0 * PI + HALF_OVERLAP * overlap),
            offset(amplitude, random) + cy + ry * sin(start + 2.0 * PI + HALF_OVERLAP * overlap),
        )
        ring += ScenePt(
            offset(amplitude, random) + cx + CLOSE_SCALE * rx * cos(start + overlap),
            offset(amplitude, random) + cy + CLOSE_SCALE * ry * sin(start + overlap),
        )
        ring += ScenePt(
            offset(amplitude, random) + cx + INNER_START * rx * cos(start + HALF_OVERLAP * overlap),
            offset(amplitude, random) + cy + INNER_START * ry * sin(start + HALF_OVERLAP * overlap),
        )
        return ring
    }

    /** `x * v` where v is drawn in [-amplitude, amplitude] scaled by roughness (rough.js `G`). */
    private fun offset(amplitude: Double, rng: SeededRandom, scale: Double = 1.0): Double =
        roughness * scale * (rng.next() * (2.0 * amplitude) - amplitude)

    /** rough.js `E`: a value in [low, high] scaled by roughness; [draw] is the uniform sample. */
    private fun between(low: Double, high: Double, draw: Double): Double = roughness * (draw * (high - low) + low)

    private fun Double.sq(): Double = this * this

    private companion object {
        const val MIN_POLYGON_POINTS = 2
        const val CATMULL_MIN_POINTS = 3
        const val OFFSET_AMPLITUDE = 2.0
        const val BOWING = 1.0
        const val BOW_DIVISOR = 200.0
        const val SHORT_LINE = 200.0
        const val LONG_LINE = 500.0
        const val LONG_LINE_SCALE = 0.4
        const val LINE_SCALE_SLOPE = -0.0016668
        const val LINE_SCALE_BASE = 1.233334
        const val SPREAD_GUARD = 100.0
        const val SHORT_LINE_DIVISOR = 10.0
        const val LOW_FRACTION = 0.2
        const val CUBIC_PASSES = 2
        const val CUBIC_SECOND_EXTRA = 0.3
        const val AMPLITUDE_PER_ROUGHNESS = 0.2
        const val SECOND_PASS_SCALE = 1.5
        const val SECOND_PASS_PER_ROUGHNESS = 0.22
        const val CURVE_TIGHTNESS = 0.0
        const val CURVE_FITTING = 0.95
        const val TANGENT_DIVISOR = 6.0
        const val STEP_COUNT = 9.0
        const val STEP_REFERENCE = 200.0
        const val OVERLAP_LOW = 0.1
        const val OVERLAP_INNER_LOW = 0.4
        const val START_JITTER = 0.5
        const val INNER_START = 0.9
        const val END_TRIM = 0.01
        const val HALF_OVERLAP = 0.5
        const val CLOSE_SCALE = 0.98
        const val QUARTER_TURN = 90.0
        const val MIN_FILL_GAP = 0.1
    }
}
