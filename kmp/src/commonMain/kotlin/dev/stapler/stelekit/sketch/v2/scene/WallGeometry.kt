// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A plain 2-D vector of the wall geometry (scene units, y grows downward). */
data class WallVec(val x: Double, val y: Double) {
    operator fun plus(o: WallVec) = WallVec(x + o.x, y + o.y)
    operator fun minus(o: WallVec) = WallVec(x - o.x, y - o.y)
    operator fun times(k: Double) = WallVec(x * k, y * k)
    fun dot(o: WallVec) = x * o.x + y * o.y
    fun distanceTo(o: WallVec) = hypot(x - o.x, y - o.y)
}

/** One centreline segment: [u] is the unit direction and [n] the left normal (a segment drawn rightwards has its left side up the screen). */
class WallSegment(val index: Int, val a: WallVec, val b: WallVec) {
    val length: Double = hypot(b.x - a.x, b.y - a.y)
    val u: WallVec = if (length > 0.0) WallVec((b.x - a.x) / length, (b.y - a.y) / length) else WallVec(1.0, 0.0)
    val n: WallVec = WallVec(u.y, -u.x)

    /** The point [s] units along the segment and [across] to its left. */
    fun at(s: Double, across: Double = 0.0): WallVec = a + u * s + n * across
}

/** One side of a wall between two joints: [start] and [end] lie on the face, [sStart] and [sEnd] are their distances along the centreline segment from its start vertex. */
class FaceEdge(val segment: WallSegment, val start: WallVec, val end: WallVec, val sStart: Double, val sEnd: Double)

/** A stroked polyline of a wall drawing; [closed] joins the last point back to the first. */
data class WallStroke(val points: List<WallVec>, val closed: Boolean = false)

/** What a wall is drawn as: filled quads (the body) and stroked polylines (faces, end caps, jambs). */
data class WallLayout(val fills: List<List<WallVec>>, val strokes: List<WallStroke>)

/** A span cut out of the wall's body: segment [segment], from [s0] to [s1] along it. */
data class WallCut(val segment: Int, val s0: Double, val s1: Double)

/** Where an opening sits: the centre of its footprint, the wall direction, its width along the wall and the wall's full thickness. */
data class OpeningFootprint(val centre: WallVec, val angle: Double, val width: Double, val thickness: Double)

/** The point of a wall nearest a probe: [segment], the distance [s] along it and [distance] from the centreline. */
data class WallProbe(val segment: Int, val s: Double, val distance: Double)

/** Face distances from the drawn line, in the direction of travel. */
data class WallOffsets(val left: Double, val right: Double)

/** The free span of one centreline segment (between the joint mitres) and what an opening may do inside it. */
data class WallSpan(val lo: Double, val hi: Double) {
    val length: Double get() = hi - lo
}

/**
 * The pure geometry of walls (no rendering, no scene): double-line faces with mitred joints, where an opening may sit,
 * and the area of a room. A joint whose mitre would be longer than [MITRE_RATIO] times the face offset (a very sharp turn)
 * is bevelled instead, so a hairpin never throws a spike across the sheet.
 */
@Suppress("TooManyFunctions")
object WallGeometry {
    const val MITRE_RATIO = 3.0
    private const val ARC_STEPS = 12
    private const val HALF = 0.5
    private const val NEAR_REVERSAL = 1e-9

    fun vec(p: ScenePoint) = WallVec(p.x.value, p.y.value)

    fun segments(points: List<ScenePoint>, closed: Boolean): List<WallSegment> {
        val count = if (closed) points.size else points.size - 1
        return (0 until count).map { WallSegment(it, vec(points[it]), vec(points[(it + 1) % points.size])) }
    }

    fun segments(wall: WallElement): List<WallSegment> = segments(wall.points, wall.closed)

    /** True when no segment has zero length (it would have no direction). */
    fun hasDistinctVertices(points: List<ScenePoint>, closed: Boolean): Boolean = segments(points, closed).none { it.length == 0.0 }

    fun length(wall: WallElement): Double = segments(wall).sumOf { it.length }

    /** Shoelace area; positive when the polygon runs clockwise on a y-down screen. */
    fun signedArea(points: List<WallVec>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val p = points[i]
            val q = points[(i + 1) % points.size]
            sum += p.x * q.y - q.x * p.y
        }
        return sum * HALF
    }

    fun area(points: List<WallVec>): Double = abs(signedArea(points))

    /** The room side: left of travel for an open wall, the polygon interior for a closed one. */
    fun interiorIsLeft(points: List<ScenePoint>, closed: Boolean): Boolean = !closed || signedArea(points.map(::vec)) < 0.0

    fun offsets(wall: WallElement): WallOffsets {
        val t = wall.thickness.value
        val (interior, exterior) = when (wall.justification) {
            WallJustification.Inside -> 0.0 to t
            WallJustification.Centre -> t * HALF to t * HALF
            WallJustification.Outside -> t to 0.0
        }
        return if (interiorIsLeft(wall.points, wall.closed)) WallOffsets(interior, exterior) else WallOffsets(exterior, interior)
    }

    fun leftFace(wall: WallElement): List<FaceEdge> = face(segments(wall), wall.closed, offsets(wall).left, side = 1.0)

    fun rightFace(wall: WallElement): List<FaceEdge> = face(segments(wall), wall.closed, offsets(wall).right, side = -1.0)

    /** The edges of one face, [distance] from the drawn line on [side] (+1 left, -1 right), with mitred joints. */
    fun face(segs: List<WallSegment>, closed: Boolean, distance: Double, side: Double): List<FaceEdge> {
        val d = distance * side
        return segs.indices.map { i ->
            val seg = segs[i]
            val prev = if (i > 0) segs[i - 1] else if (closed) segs.last() else null
            val next = if (i < segs.lastIndex) segs[i + 1] else if (closed) segs.first() else null
            val s0 = prev?.let { mitre(it, seg, d) } ?: seg.at(0.0, d)
            val s1 = next?.let { mitre(seg, it, d) } ?: seg.at(seg.length, d)
            FaceEdge(seg, s0, s1, (s0 - seg.a).dot(seg.u), (s1 - seg.a).dot(seg.u))
        }
    }

    /** The face point at the vertex between [p] and [q], or null when the joint is too sharp and each edge keeps its own plain offset. */
    private fun mitre(p: WallSegment, q: WallSegment, d: Double): WallVec? {
        val c = p.n.dot(q.n)
        if (1.0 + c < NEAR_REVERSAL || sqrt(2.0 / (1.0 + c)) > MITRE_RATIO) return null
        return q.a + (p.n + q.n) * (d / (1.0 + c))
    }

    /** The face points of a mitred wall in order, joints that were bevelled contributing two points; used for the room area and bounds. */
    fun facePoints(edges: List<FaceEdge>): List<WallVec> {
        val out = ArrayList<WallVec>()
        for (e in edges) {
            if (out.isEmpty() || out.last().distanceTo(e.start) > 0.0) out += e.start
            if (out.last().distanceTo(e.end) > 0.0) out += e.end
        }
        if (out.size > 1 && out.first().distanceTo(out.last()) == 0.0) out.removeAt(out.lastIndex)
        return out
    }

    /** The interior face of a closed wall as a polygon (the clear room outline); null for an open wall. */
    fun interiorPolygon(wall: WallElement): List<WallVec>? {
        if (!wall.closed) return null
        val edges = if (interiorIsLeft(wall.points, true)) leftFace(wall) else rightFace(wall)
        return facePoints(edges)
    }

    /** Clear floor area of a closed wall in square scene units, or null for an open wall. */
    fun interiorArea(wall: WallElement): Double? = interiorPolygon(wall)?.let(::area)

    fun bounds(wall: WallElement): DoubleArray {
        val pts = facePoints(leftFace(wall)) + facePoints(rightFace(wall)) + wall.points.map(::vec)
        return doubleArrayOf(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    /** The span of segment [segment] where both faces exist (between the joint mitres); null for a missing segment or a joint swallowing it. */
    fun span(wall: WallElement, segment: Int): WallSpan? = span(leftFace(wall), rightFace(wall), segment)

    private fun span(left: List<FaceEdge>, right: List<FaceEdge>, segment: Int): WallSpan? {
        val l = left.getOrNull(segment) ?: return null
        val r = right.getOrNull(segment) ?: return null
        val lo = max(0.0, max(l.sStart, r.sStart))
        val hi = min(l.segment.length, min(l.sEnd, r.sEnd))
        return if (hi > lo) WallSpan(lo, hi) else null
    }

    /**
     * An opening of [width] at [offset] on [segment], moved and narrowed to lie inside the segment's free span; null when the
     * segment is missing or has no free span. The result always satisfies `lo <= offset` and `offset + width <= hi`.
     */
    fun fit(wall: WallElement, segment: Int, offset: Double, width: Double): Pair<Double, Double>? {
        val s = span(wall, segment) ?: return null
        val w = min(width, s.length)
        return offset.coerceIn(s.lo, max(s.lo, s.hi - w)) to w
    }

    /** Where [opening] sits on [wall]; null when the segment is missing. The opening is fitted first, so it never leaves the wall. */
    fun footprint(wall: WallElement, opening: OpeningElement): OpeningFootprint? {
        val seg = segments(wall).getOrNull(opening.segment) ?: return null
        val (offset, width) = fit(wall, opening.segment, opening.offset.value, opening.width.value) ?: return null
        val o = offsets(wall)
        return OpeningFootprint(seg.at(offset + width * HALF, (o.left - o.right) * HALF), atan2(seg.u.y, seg.u.x), width, o.left + o.right)
    }

    /** The nearest point of [wall]'s centreline to ([x], [y]). */
    fun probe(wall: WallElement, x: Double, y: Double): WallProbe {
        val p = WallVec(x, y)
        return segments(wall).map { seg ->
            val s = (p - seg.a).dot(seg.u).coerceIn(0.0, seg.length)
            WallProbe(seg.index, s, seg.at(s).distanceTo(p))
        }.minBy { it.distance }
    }

    /**
     * The wall as fills and strokes with [cuts] removed from its body (door and plain openings). Each cut is limited to its
     * segment's free span; one that does not overlap it is ignored. Cuts get a jamb line across the wall at each end.
     */
    fun layout(wall: WallElement, cuts: List<WallCut>): WallLayout {
        val left = leftFace(wall)
        val right = rightFace(wall)
        val fills = ArrayList<List<WallVec>>()
        val strokes = ArrayList<WallStroke>()
        val leftPieces = ArrayList<Piece?>()
        val rightPieces = ArrayList<Piece?>()
        for (i in left.indices) {
            val l = left[i]
            val r = right[i]
            val free = span(left, right, i)
            if (free == null) {
                leftPieces += null
                rightPieces += null
                continue
            }
            val here = cuts.filter { it.segment == i }
            val kept = keptIntervals(free, here)
            if (kept.isEmpty()) {
                leftPieces += null
                rightPieces += null
            }
            for (k in kept) {
                val lStart = if (k.startsAtJoint) l.start else l.segment.at(k.a, offsetAcross(l))
                val rStart = if (k.startsAtJoint) r.start else r.segment.at(k.a, offsetAcross(r))
                val lEnd = if (k.endsAtJoint) l.end else l.segment.at(k.b, offsetAcross(l))
                val rEnd = if (k.endsAtJoint) r.end else r.segment.at(k.b, offsetAcross(r))
                fills += listOf(lStart, lEnd, rEnd, rStart)
                leftPieces += Piece(lStart, lEnd, k.startsAtJoint, k.endsAtJoint)
                rightPieces += Piece(rStart, rEnd, k.startsAtJoint, k.endsAtJoint)
            }
            for (s in here.flatMap { listOf(it.s0, it.s1) }.filter { it > free.lo && it < free.hi }) {
                strokes += WallStroke(listOf(l.segment.at(s, offsetAcross(l)), r.segment.at(s, offsetAcross(r))))
            }
        }
        strokes += joinPieces(leftPieces, wall.closed)
        strokes += joinPieces(rightPieces, wall.closed)
        if (!wall.closed) {
            strokes += WallStroke(listOf(left.first().start, right.first().start))
            strokes += WallStroke(listOf(left.last().end, right.last().end))
        }
        return WallLayout(fills, strokes)
    }

    private fun offsetAcross(e: FaceEdge): Double = (e.start - e.segment.a).dot(e.segment.n)

    private class Kept(val a: Double, val b: Double, val startsAtJoint: Boolean, val endsAtJoint: Boolean)

    private fun keptIntervals(free: WallSpan, cuts: List<WallCut>): List<Kept> {
        val merged = ArrayList<Pair<Double, Double>>()
        for (c in cuts.map { max(it.s0, free.lo) to min(it.s1, free.hi) }.filter { it.second > it.first }.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && c.first <= last.second) merged[merged.lastIndex] = last.first to max(last.second, c.second) else merged += c
        }
        val out = ArrayList<Kept>()
        var from = free.lo
        var atJoint = true
        for ((a, b) in merged) {
            if (a > from) out += Kept(from, a, atJoint, false)
            from = b
            atJoint = false
        }
        if (free.hi > from) out += Kept(from, free.hi, atJoint, true)
        return out
    }

    /** One stretch of a face; a stretch that starts or ends at a joint continues into the neighbouring edge. */
    private class Piece(val from: WallVec, val to: WallVec, val startsAtJoint: Boolean, val endsAtJoint: Boolean)

    /** Joins consecutive [pieces] that meet at a joint into one polyline (so strokes get proper line joins); a null entry is a break. */
    private fun joinPieces(pieces: List<Piece?>, closed: Boolean): List<WallStroke> {
        val runs = ArrayList<MutableList<WallVec>>()
        var current: MutableList<WallVec>? = null
        for (piece in pieces) {
            if (piece == null) {
                current?.let { runs += it }
                current = null
                continue
            }
            val run = current?.takeIf { piece.startsAtJoint } ?: arrayListOf(piece.from).also { fresh -> current?.let { runs += it }; current = fresh }
            if (run.last().distanceTo(piece.from) > 0.0) run += piece.from
            run += piece.to
            if (piece.endsAtJoint) {
                current = run
            } else {
                runs += run
                current = null
            }
        }
        current?.let { runs += it }
        val wraps = closed && pieces.firstOrNull()?.startsAtJoint == true && pieces.lastOrNull()?.endsAtJoint == true
        if (wraps && runs.size == 1) return listOf(WallStroke(dropClosingDuplicate(runs.single()), closed = true))
        if (wraps && runs.size > 1) {
            val head = runs.removeAt(0)
            val tail = runs.last()
            tail += if (tail.last().distanceTo(head.first()) > 0.0) head else head.drop(1)
        }
        return runs.map { WallStroke(it) }
    }

    private fun dropClosingDuplicate(points: MutableList<WallVec>): List<WallVec> =
        if (points.size > 1 && points.first().distanceTo(points.last()) == 0.0) points.dropLast(1) else points

    /** The strokes of [opening]'s own symbol on [wall]: a door's leaf and swing arc, a window's frame and pane line. Empty for a plain opening. */
    fun symbol(wall: WallElement, opening: OpeningElement): List<WallStroke> {
        val seg = segments(wall).getOrNull(opening.segment) ?: return emptyList()
        val (offset, width) = fit(wall, opening.segment, opening.offset.value, opening.width.value) ?: return emptyList()
        val o = offsets(wall)
        return when (opening.kind) {
            OpeningKind.Opening -> emptyList()
            OpeningKind.Window -> windowSymbol(seg, o, offset, width)
            OpeningKind.Door -> doorSymbol(seg, o, offset, width, opening)
        }
    }

    /** The two lines across the wall at the opening's jambs. */
    fun jambs(wall: WallElement, opening: OpeningElement): List<WallStroke> {
        val seg = segments(wall).getOrNull(opening.segment) ?: return emptyList()
        val (offset, width) = fit(wall, opening.segment, opening.offset.value, opening.width.value) ?: return emptyList()
        val o = offsets(wall)
        return listOf(offset, offset + width).map { s -> WallStroke(listOf(seg.at(s, o.left), seg.at(s, -o.right))) }
    }

    private fun windowSymbol(seg: WallSegment, o: WallOffsets, offset: Double, width: Double): List<WallStroke> {
        val s1 = offset + width
        val mid = (o.left - o.right) * HALF
        return listOf(
            WallStroke(listOf(seg.at(offset, o.left), seg.at(offset, -o.right))),
            WallStroke(listOf(seg.at(s1, o.left), seg.at(s1, -o.right))),
            WallStroke(listOf(seg.at(offset, o.left), seg.at(s1, o.left))),
            WallStroke(listOf(seg.at(offset, -o.right), seg.at(s1, -o.right))),
            WallStroke(listOf(seg.at(offset, mid), seg.at(s1, mid))),
        )
    }

    private fun doorSymbol(seg: WallSegment, o: WallOffsets, offset: Double, width: Double, opening: OpeningElement): List<WallStroke> {
        val side = if (opening.flipSide) -1.0 else 1.0
        val face = side * (if (opening.flipSide) o.right else o.left)
        val hingeS = if (opening.flipHinge) offset + width else offset
        val otherS = if (opening.flipHinge) offset else offset + width
        val hinge = seg.at(hingeS, face)
        val tip = hinge + seg.n * (side * width)
        val strike = seg.at(otherS, face)
        val a0 = atan2(tip.y - hinge.y, tip.x - hinge.x)
        var sweep = atan2(strike.y - hinge.y, strike.x - hinge.x) - a0
        while (sweep > PI) sweep -= 2.0 * PI
        while (sweep < -PI) sweep += 2.0 * PI
        val arc = (0..ARC_STEPS).map { k ->
            val a = a0 + sweep * k / ARC_STEPS
            WallVec(hinge.x + width * cos(a), hinge.y + width * sin(a))
        }
        return listOf(WallStroke(listOf(hinge, tip)), WallStroke(arc))
    }
}
