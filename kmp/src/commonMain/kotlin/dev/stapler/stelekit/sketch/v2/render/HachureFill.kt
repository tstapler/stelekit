// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
//
// Own Kotlin port of the pattern fills of rough.js 4.6.6 (hachure, cross-hatch, zigzag) and of the scan-line
// algorithm of hachure-fill 0.5.2: MIT License, Copyright (c) 2019 Preet Shihn (rough.js) and (c) 2023 Preet Shihn
// (hachure-fill), https://github.com/rough-stuff/rough (see NOTICE). The order in which random numbers are drawn is
// part of the algorithm and is kept exactly (checked against kmp/src/commonTest/resources/excalidraw/hachure-golden.json).

package dev.stapler.stelekit.sketch.v2.render

import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A non-solid fill to generate for one stroke: [gap] is the line spacing in scene units, [angleDegrees] rough.js `hachureAngle`. */
data class FillRequest(val style: FillStyle, val gap: Double, val angleDegrees: Double = HachureFill.DEFAULT_ANGLE_DEGREES) {
    init {
        require(style != FillStyle.Solid) { "solid fills are not generated" }
        require(gap.isFinite() && angleDegrees.isFinite()) { "fill gap and angle must be finite" }
    }
}

/** One hatch line from [from] to [to]. */
data class HatchLine(val from: ScenePt, val to: ScenePt)

/** Scan-line hatch lines clipped to a polygon (hachure-fill 0.5.2 `hachureLines`). */
object HachureFill {
    const val DEFAULT_ANGLE_DEGREES = -41.0
    private const val MIN_GAP = 0.1
    private const val QUARTER_TURN = 90.0
    private const val SKIP_THRESHOLD = 0.7
    private const val FLAT_STEP = 1
    private const val HALF = 0.5
    private const val STRAIGHT_ANGLE = 180.0
    private const val MIN_RING = 2
    private const val CURVE_STEPS = 8

    private class Edge(val yMin: Double, val yMax: Double, var x: Double, val inverseSlope: Double)

    /** Polygons (any winding, not necessarily closed) of the outline [clean], curves flattened to short chords. */
    fun polygonsOf(clean: List<PathSegment>): List<List<ScenePt>> {
        val rings = ArrayList<List<ScenePt>>()
        var current = ArrayList<ScenePt>()
        var cx = 0.0
        var cy = 0.0
        fun flush() {
            if (current.size > MIN_RING) rings += current
            current = ArrayList()
        }
        for (seg in clean) {
            when (seg) {
                is PathSegment.MoveTo -> {
                    flush()
                    current.add(ScenePt(seg.x, seg.y)); cx = seg.x; cy = seg.y
                }
                is PathSegment.LineTo -> {
                    current.add(ScenePt(seg.x, seg.y)); cx = seg.x; cy = seg.y
                }
                is PathSegment.CubicTo -> {
                    for (i in 1..CURVE_STEPS) current.add(cubicAt(cx, cy, seg, i.toDouble() / CURVE_STEPS))
                    cx = seg.x; cy = seg.y
                }
                PathSegment.Close -> flush()
            }
        }
        flush()
        return rings
    }

    private fun cubicAt(x0: Double, y0: Double, c: PathSegment.CubicTo, t: Double): ScenePt {
        val u = 1.0 - t
        val a = u * u * u
        val b = 3.0 * u * u * t
        val d = 3.0 * u * t * t
        val e = t * t * t
        return ScenePt(a * x0 + b * c.c1x + d * c.c2x + e * c.x, a * y0 + b * c.c1y + d * c.c2y + e * c.y)
    }

    /**
     * rough.js `polygonHachureLines`: [angleDegrees] is `hachureAngle` (the lines run at that angle plus 90 degrees).
     * Draws one number from [skipDraw] when [roughness] is at least 1, which decides whether every scan line is used.
     */
    fun lines(polygons: List<List<ScenePt>>, gap: Double, angleDegrees: Double, roughness: Double, skipDraw: () -> Double): List<HatchLine> {
        // A non-finite coordinate would make the scan position NaN and the scan loop never end.
        if (polygons.any { ring -> ring.any { !it.x.isFinite() || !it.y.isFinite() } }) return emptyList()
        val roundedGap = max(gap, MIN_GAP).roundToJsInt().toDouble()
        val skipOffset = if (roughness >= 1.0 && skipDraw() > SKIP_THRESHOLD) roundedGap.toInt() else FLAT_STEP
        return hachureLines(polygons, max(roundedGap, MIN_GAP), angleDegrees + QUARTER_TURN, if (skipOffset == 0) FLAT_STEP else skipOffset)
    }

    /** rough.js zigzag: each hatch line becomes two lines meeting at its far end. */
    fun zigzag(lines: List<HatchLine>, gap: Double, angleDegrees: Double): List<HatchLine> {
        val radians = angleDegrees * PI / STRAIGHT_ANGLE
        val dgx = gap * HALF * cos(radians)
        val dgy = gap * HALF * sin(radians)
        val out = ArrayList<HatchLine>()
        for (l in lines) {
            if (hypot(l.to.x - l.from.x, l.to.y - l.from.y) == 0.0) continue
            out += HatchLine(ScenePt(l.from.x - dgx, l.from.y + dgy), l.to)
            out += HatchLine(ScenePt(l.from.x + dgx, l.from.y - dgy), l.to)
        }
        return out
    }

    private fun hachureLines(polygons: List<List<ScenePt>>, gap: Double, angle: Double, stepOffset: Int): List<HatchLine> {
        val rotated = if (angle != 0.0) polygons.map { ring -> ring.map { rotate(it, angle) } } else polygons
        val raw = scan(rotated, max(gap, MIN_GAP), stepOffset)
        if (angle == 0.0) return raw
        return raw.map { HatchLine(rotate(it.from, -angle), rotate(it.to, -angle)) }
    }

    private fun rotate(p: ScenePt, degrees: Double): ScenePt {
        val a = degrees * PI / STRAIGHT_ANGLE
        val c = cos(a)
        val s = sin(a)
        return ScenePt(p.x * c - p.y * s, p.x * s + p.y * c)
    }

    private fun edgesOf(polygons: List<List<ScenePt>>): MutableList<Edge> {
        val edges = ArrayList<Edge>()
        for (ring in polygons) {
            val vertices = if (ring.first() == ring.last()) ring else ring + ring.first()
            if (vertices.size <= MIN_RING) continue
            for (i in 0 until vertices.size - 1) {
                val p1 = vertices[i]
                val p2 = vertices[i + 1]
                if (p1.y == p2.y) continue
                val yMin = min(p1.y, p2.y)
                edges += Edge(yMin, max(p1.y, p2.y), if (yMin == p1.y) p1.x else p2.x, (p2.x - p1.x) / (p2.y - p1.y))
            }
        }
        return edges
    }

    private fun edgeOrder(): Comparator<Edge> = Comparator { a, b ->
        when {
            a.yMin != b.yMin -> a.yMin.compareTo(b.yMin)
            a.x != b.x -> a.x.compareTo(b.x)
            a.yMax == b.yMax -> 0
            else -> if (a.yMax - b.yMax < 0) -1 else 1
        }
    }

    private fun scan(polygons: List<List<ScenePt>>, gap: Double, stepOffset: Int): List<HatchLine> {
        val edges = edgesOf(polygons)
        if (edges.isEmpty()) return emptyList()
        edges.sortWith(edgeOrder())
        val lines = ArrayList<HatchLine>()
        var active = ArrayList<Edge>()
        var y = edges[0].yMin
        var iteration = 0
        val gapSteps = max(1, gap.toInt())
        while (active.isNotEmpty() || edges.isNotEmpty()) {
            var taken = 0
            while (taken < edges.size && edges[taken].yMin <= y) taken++
            if (taken > 0) {
                for (i in 0 until taken) active.add(edges[i])
                edges.subList(0, taken).clear()
            }
            active = ArrayList(active.filter { it.yMax > y })
            active.sortWith { a, b -> if (a.x == b.x) 0 else if (a.x - b.x < 0) -1 else 1 }
            if ((stepOffset != FLAT_STEP || iteration % gapSteps == 0) && active.size > 1) {
                var i = 0
                while (i + 1 < active.size) {
                    lines += HatchLine(ScenePt(active[i].x.roundToJsInt().toDouble(), y), ScenePt(active[i + 1].x.roundToJsInt().toDouble(), y))
                    i += MIN_RING
                }
            }
            y += stepOffset
            for (e in active) e.x += stepOffset * e.inverseSlope
            iteration++
        }
        return lines
    }

    /** JavaScript `Math.round`: halves go up. */
    private fun Double.roundToJsInt(): Int = floor(this + HALF).toInt()
}
