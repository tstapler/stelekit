// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.render

import dev.stapler.stelekit.sketch.v2.scene.Arrowhead

/** One piece of an arrowhead: a path, painted with the stroke colour and, when [filled], filled with it. */
internal data class HeadShape(val segments: List<PathSegment>, val filled: Boolean)

/**
 * Geometry of the 8 drawn arrowheads (`Arrowhead.None` draws nothing), scene space, no trigonometry: the flank
 * direction is the shaft direction rotated by a fixed 20 degrees with precomputed constants, so the result is
 * bit-identical on every target.
 */
internal object ArrowheadGeometry {
    private const val COS_FLANK = 0.9396926207859084
    private const val SIN_FLANK = 0.3420201433256687
    private const val ARROW_SIZE = 25.0
    private const val BAR_SIZE = 15.0
    private const val TRIANGLE_SIZE = 15.0
    private const val CIRCLE_SIZE = 15.0
    private const val DIAMOND_SIZE = 12.0
    private const val HALF = 0.5

    /**
     * Shapes for [kind] with its tip at [tip]; [direction] is the unit vector along the shaft pointing at the tip.
     * The head never exceeds [reach], the length of the last shaft segment, so a short arrow keeps a readable shaft.
     */
    fun shapes(kind: Arrowhead, tip: Pt, direction: Pt, reach: Double): List<HeadShape> {
        val normal = Pt(-direction.y, direction.x)
        return when (kind) {
            Arrowhead.None -> emptyList()
            Arrowhead.Arrow -> {
                val s = sized(ARROW_SIZE, reach)
                listOf(HeadShape(open(tip, flank(direction, s, -1.0), flank(direction, s, 1.0)), false))
            }
            Arrowhead.Bar -> {
                val h = sized(BAR_SIZE, reach) * HALF
                listOf(HeadShape(listOf(PathSegment.MoveTo((tip + normal * h).x, (tip + normal * h).y), PathSegment.LineTo((tip - normal * h).x, (tip - normal * h).y)), false))
            }
            Arrowhead.Triangle -> listOf(HeadShape(triangle(tip, direction, sized(TRIANGLE_SIZE, reach)), true))
            Arrowhead.TriangleOutline -> listOf(HeadShape(triangle(tip, direction, sized(TRIANGLE_SIZE, reach)), false))
            Arrowhead.Diamond -> listOf(HeadShape(diamond(tip, direction, normal, sized(DIAMOND_SIZE, reach)), true))
            Arrowhead.DiamondOutline -> listOf(HeadShape(diamond(tip, direction, normal, sized(DIAMOND_SIZE, reach)), false))
            Arrowhead.Circle -> listOf(HeadShape(circle(tip, direction, sized(CIRCLE_SIZE, reach)), true))
            Arrowhead.CircleOutline -> listOf(HeadShape(circle(tip, direction, sized(CIRCLE_SIZE, reach)), false))
        }
    }

    private fun sized(base: Double, reach: Double): Double = if (reach < base) reach else base

    /** The back-pointing flank of the head: -[direction] rotated by [side] x 20 degrees, scaled to [size]. */
    private fun flank(direction: Pt, size: Double, side: Double): Pt {
        val sin = SIN_FLANK * side
        val rx = direction.x * COS_FLANK - direction.y * sin
        val ry = direction.x * sin + direction.y * COS_FLANK
        return Pt(-rx * size, -ry * size)
    }

    private fun open(tip: Pt, left: Pt, right: Pt): List<PathSegment> = listOf(
        PathSegment.MoveTo(tip.x + left.x, tip.y + left.y),
        PathSegment.LineTo(tip.x, tip.y),
        PathSegment.LineTo(tip.x + right.x, tip.y + right.y),
    )

    private fun triangle(tip: Pt, direction: Pt, size: Double): List<PathSegment> {
        val left = flank(direction, size, -1.0)
        val right = flank(direction, size, 1.0)
        return listOf(
            PathSegment.MoveTo(tip.x, tip.y),
            PathSegment.LineTo(tip.x + left.x, tip.y + left.y),
            PathSegment.LineTo(tip.x + right.x, tip.y + right.y),
            PathSegment.Close,
        )
    }

    private fun diamond(tip: Pt, direction: Pt, normal: Pt, size: Double): List<PathSegment> {
        val mid = tip - direction * (size * HALF)
        val tail = tip - direction * size
        val wing = normal * (size * HALF)
        return listOf(
            PathSegment.MoveTo(tip.x, tip.y),
            PathSegment.LineTo((mid + wing).x, (mid + wing).y),
            PathSegment.LineTo(tail.x, tail.y),
            PathSegment.LineTo((mid - wing).x, (mid - wing).y),
            PathSegment.Close,
        )
    }

    private fun circle(tip: Pt, direction: Pt, size: Double): List<PathSegment> {
        val r = size * HALF
        val c = tip - direction * r
        val b = PathBuilder()
        PathGeometry.ellipse(b, c.x, c.y, r, r)
        return b.build()
    }
}
