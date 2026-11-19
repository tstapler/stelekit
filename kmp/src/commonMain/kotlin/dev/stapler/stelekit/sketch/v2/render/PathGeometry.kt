// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.render

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Double) = Pt(x * k, y * k)
    fun length(): Double = sqrt(x * x + y * y)
}

/**
 * Collects [PathSegment]s, rotating every emitted point about ([pivotX], [pivotY]) when an angle is set. The
 * angle-zero case does no trigonometry at all, so unrotated geometry is bit-identical on every target.
 */
internal class PathBuilder(angle: Double = 0.0, private val pivotX: Double = 0.0, private val pivotY: Double = 0.0) {
    private val rotated = angle != 0.0
    private val sinA = if (rotated) sin(angle) else 0.0
    private val cosA = if (rotated) cos(angle) else 1.0
    private val out = ArrayList<PathSegment>()

    private fun tx(x: Double, y: Double): Double = if (rotated) pivotX + (x - pivotX) * cosA - (y - pivotY) * sinA else x
    private fun ty(x: Double, y: Double): Double = if (rotated) pivotY + (x - pivotX) * sinA + (y - pivotY) * cosA else y

    fun moveTo(p: Pt) = apply { out.add(PathSegment.MoveTo(tx(p.x, p.y), ty(p.x, p.y))) }
    fun lineTo(p: Pt) = apply { out.add(PathSegment.LineTo(tx(p.x, p.y), ty(p.x, p.y))) }
    fun cubicTo(c1: Pt, c2: Pt, p: Pt) = apply {
        out.add(PathSegment.CubicTo(tx(c1.x, c1.y), ty(c1.x, c1.y), tx(c2.x, c2.y), ty(c2.x, c2.y), tx(p.x, p.y), ty(p.x, p.y)))
    }
    fun close() = apply { out.add(PathSegment.Close) }
    fun build(): List<PathSegment> = out.toList()
}

internal object PathGeometry {
    /** Control-point distance for a quarter circle drawn with one cubic. */
    const val KAPPA = 0.5522847498307936
    private const val TANGENT_DIVISOR = 6.0
    private const val QUARTER_TURN = PI / 2
    private const val FULL_TURN = 2 * PI
    private const val QUARTER_DIVISOR = 4.0 / 3.0
    private const val FOURTH = 4.0

    /** Fewer points than this stay straight; a smooth curve needs a bend. */
    const val MIN_CURVE_POINTS = 3

    fun polygon(b: PathBuilder, pts: List<Pt>, radius: Double) {
        if (pts.isEmpty()) return
        if (radius <= 0.0) {
            b.moveTo(pts[0])
            for (i in 1 until pts.size) b.lineTo(pts[i])
            b.close()
            return
        }
        val n = pts.size
        for (i in 0 until n) {
            val prev = pts[(i + n - 1) % n]
            val cur = pts[i]
            val next = pts[(i + 1) % n]
            val toPrev = prev - cur
            val toNext = next - cur
            val r = min(radius, min(toPrev.length(), toNext.length()) / 2.0)
            val d1 = toPrev * (1.0 / toPrev.length())
            val d2 = toNext * (1.0 / toNext.length())
            val start = cur + d1 * r
            val end = cur + d2 * r
            if (i == 0) b.moveTo(start) else b.lineTo(start)
            b.cubicTo(cur + d1 * (r * (1.0 - KAPPA)), cur + d2 * (r * (1.0 - KAPPA)), end)
        }
        b.close()
    }

    fun ellipse(b: PathBuilder, cx: Double, cy: Double, rx: Double, ry: Double) {
        val kx = rx * KAPPA
        val ky = ry * KAPPA
        b.moveTo(Pt(cx + rx, cy))
        b.cubicTo(Pt(cx + rx, cy + ky), Pt(cx + kx, cy + ry), Pt(cx, cy + ry))
        b.cubicTo(Pt(cx - kx, cy + ry), Pt(cx - rx, cy + ky), Pt(cx - rx, cy))
        b.cubicTo(Pt(cx - rx, cy - ky), Pt(cx - kx, cy - ry), Pt(cx, cy - ry))
        b.cubicTo(Pt(cx + kx, cy - ry), Pt(cx + rx, cy - ky), Pt(cx + rx, cy))
        b.close()
    }

    /** Counter-clockwise (increasing angle) arc; a zero sweep draws nothing but the start point. */
    fun arc(b: PathBuilder, cx: Double, cy: Double, radius: Double, startAngle: Double, endAngle: Double) {
        var sweep = (endAngle - startAngle) % FULL_TURN
        if (sweep < 0.0) sweep += FULL_TURN
        b.moveTo(Pt(cx + radius * cos(startAngle), cy + radius * sin(startAngle)))
        if (sweep == 0.0) return
        val pieces = ceil(sweep / QUARTER_TURN).toInt()
        val step = sweep / pieces
        val handle = QUARTER_DIVISOR * tan(step / FOURTH) * radius
        for (i in 0 until pieces) {
            val a0 = startAngle + step * i
            val a1 = a0 + step
            val p0 = Pt(cx + radius * cos(a0), cy + radius * sin(a0))
            val p1 = Pt(cx + radius * cos(a1), cy + radius * sin(a1))
            b.cubicTo(
                Pt(p0.x - handle * sin(a0), p0.y + handle * cos(a0)),
                Pt(p1.x + handle * sin(a1), p1.y - handle * cos(a1)),
                p1,
            )
        }
    }

    fun polyline(b: PathBuilder, pts: List<Pt>, closed: Boolean) {
        if (pts.isEmpty()) return
        b.moveTo(pts[0])
        for (i in 1 until pts.size) b.lineTo(pts[i])
        if (closed) b.close()
    }

    /** Catmull-Rom spline through [pts] as cubics (Excalidraw's rounded lines and arrows); end points reuse themselves. */
    fun smoothLine(b: PathBuilder, pts: List<Pt>, closed: Boolean) {
        if (pts.size < MIN_CURVE_POINTS) {
            polyline(b, pts, closed)
            return
        }
        val last = pts.size - 1
        b.moveTo(pts[0])
        for (i in 0 until last) {
            val before = pts[if (i == 0) 0 else i - 1]
            val after = pts[if (i + 2 > last) last else i + 2]
            val c1 = pts[i] + (pts[i + 1] - before) * (1.0 / TANGENT_DIVISOR)
            val c2 = pts[i + 1] - (after - pts[i]) * (1.0 / TANGENT_DIVISOR)
            b.cubicTo(c1, c2, pts[i + 1])
        }
        if (closed) b.close()
    }
}
