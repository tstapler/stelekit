package dev.stapler.stelekit.sketch

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.units.ArithmeticError
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.Rational
import kotlin.math.abs

/** Snap kinds in descending priority: an earlier entry beats a later one even if farther away. */
enum class SnapKind { ENDPOINT, MIDPOINT, INTERSECTION, GRID, AXIS_LOCK, NONE }

/**
 * [point] is the snapped position. [candidateCount] is how many primitives passed the AABB prune
 * (the work counter asserted instead of wall time).
 */
data class SnapResult(val point: SketchPoint, val kind: SnapKind, val candidateCount: Int)

data class HitResult(val primitive: SketchPrimitive?, val candidateCount: Int)

/** Pure snap and hit-test engine over a [SketchScene]. All geometry is exact; no floating point. */
object SnapEngine {

    /** Axis lock engages within 3 degrees; 524/10000 is just under tan(3 deg) = 0.052408. */
    private val AXIS_LOCK_TAN = Rational(524, 10_000)

    /**
     * Snaps [cursor] within [tolerance] inches (Chebyshev distance). Endpoint, midpoint and
     * intersection come from scene geometry; grid is the nearest multiple of [gridStep] (skipped
     * when null); axis lock applies only when [axisAnchor] is set and ignores tolerance.
     */
    fun snap(
        scene: SketchScene,
        cursor: SketchPoint,
        tolerance: Rational,
        gridStep: Inches?,
        axisAnchor: SketchPoint? = null,
    ): Either<ArithmeticError, SnapResult> = either {
        val box = toleranceBox(cursor, tolerance).bind()
        val candidates = scene.candidatesIn(box)
        fun within(p: SketchPoint) = chebyshevWithin(cursor, p, tolerance)
        fun nearest(points: Sequence<SketchPoint>): SketchPoint? =
            points.filter(::within).minByOrNull { chebyshev(cursor, it) }
        fun result(p: SketchPoint, kind: SnapKind) = SnapResult(p, kind, candidates.size)

        nearest(candidates.asSequence().flatMap { it.vertices })?.let { return@either result(it, SnapKind.ENDPOINT) }
        val segments = candidates.flatMap { it.segments }
        nearest(segments.asSequence().map(::midpoint))?.let {
            return@either result(it, SnapKind.MIDPOINT)
        }
        nearest(intersections(segments).bind().asSequence())?.let {
            return@either result(it, SnapKind.INTERSECTION)
        }
        if (gridStep != null && gridStep.sixteenths > 0) {
            val g = SketchPoint(roundToStep(cursor.x, gridStep), roundToStep(cursor.y, gridStep))
            if (within(g)) return@either result(g, SnapKind.GRID)
        }
        axisAnchor?.let { anchor -> axisLock(cursor, anchor)?.let { return@either result(it, SnapKind.AXIS_LOCK) } }
        result(cursor, SnapKind.NONE)
    }

    /** The primitive whose outline is nearest [point] within [tolerance] inches (Euclidean), if any. */
    fun hitTest(scene: SketchScene, point: SketchPoint, tolerance: Rational): Either<ArithmeticError, HitResult> =
        either {
            val candidates = scene.candidatesIn(toleranceBox(point, tolerance).bind())
            val tolSixteenths = tolerance.multiply(Rational(Inches.SIXTEENTHS_PER_INCH)).bind()
            val tolSq = tolSixteenths.multiply(tolSixteenths).bind()
            var best: SketchPrimitive? = null
            var bestSq: Rational? = null
            for (prim in candidates) {
                for (seg in prim.segments) {
                    val d = distanceSquared(point, seg).bind()
                    if (d <= tolSq && (bestSq == null || d < bestSq)) {
                        best = prim
                        bestSq = d
                    }
                }
            }
            HitResult(best, candidates.size)
        }

    private fun toleranceBox(p: SketchPoint, tolerance: Rational): Either<ArithmeticError, Aabb> = either {
        val t = tolerance.multiply(Rational(Inches.SIXTEENTHS_PER_INCH)).bind()
        val pad = ceilToLong(t)
        Aabb(p.x.sixteenths - pad, p.y.sixteenths - pad, p.x.sixteenths + pad, p.y.sixteenths + pad)
    }

    private fun ceilToLong(r: Rational): Long = -((-r.num).floorDiv(r.den))

    private fun chebyshev(a: SketchPoint, b: SketchPoint): Long =
        maxOf(abs(a.x.sixteenths - b.x.sixteenths), abs(a.y.sixteenths - b.y.sixteenths))

    private fun chebyshevWithin(a: SketchPoint, b: SketchPoint, tolerance: Rational): Boolean =
        Rational(chebyshev(a, b), Inches.SIXTEENTHS_PER_INCH) <= tolerance

    /** Midpoint rounded half up to a whole sixteenth. */
    private fun midpoint(s: SketchSegment): SketchPoint {
        fun mid(a: Inches, b: Inches) = Inches((a.sixteenths + b.sixteenths + 1).floorDiv(2L))
        return SketchPoint(mid(s.a.x, s.b.x), mid(s.a.y, s.b.y))
    }

    private fun roundToStep(v: Inches, step: Inches): Inches {
        val s = step.sixteenths
        val q = (v.sixteenths * 2 + s).floorDiv(2 * s)
        return Inches(q * s)
    }

    private fun axisLock(cursor: SketchPoint, anchor: SketchPoint): SketchPoint? {
        val dx = abs(cursor.x.sixteenths - anchor.x.sixteenths)
        val dy = abs(cursor.y.sixteenths - anchor.y.sixteenths)
        if (dx == 0L && dy == 0L) return null
        val tan = AXIS_LOCK_TAN
        return when {
            Rational(dy) <= Rational(dx).multiply(tan).fold({ return null }, { it }) -> SketchPoint(cursor.x, anchor.y)
            Rational(dx) <= Rational(dy).multiply(tan).fold({ return null }, { it }) -> SketchPoint(anchor.x, cursor.y)
            else -> null
        }
    }

    /** Pairwise segment intersections among pruned candidates (only the pruned set is paired). */
    private fun intersections(segments: List<SketchSegment>): Either<ArithmeticError, List<SketchPoint>> = either {
        val out = mutableListOf<SketchPoint>()
        for (i in segments.indices) for (j in i + 1 until segments.size) {
            intersect(segments[i], segments[j]).bind()?.let { out += it }
        }
        out
    }

    private fun intersect(s1: SketchSegment, s2: SketchSegment): Either<ArithmeticError, SketchPoint?> = either {
        val px = s1.a.x.sixteenths; val py = s1.a.y.sixteenths
        val rx = s1.b.x.sixteenths - px; val ry = s1.b.y.sixteenths - py
        val qx = s2.a.x.sixteenths; val qy = s2.a.y.sixteenths
        val sx = s2.b.x.sixteenths - qx; val sy = s2.b.y.sixteenths - qy
        val denom = rx * sy - ry * sx
        if (denom == 0L) return@either null
        val tNum = (qx - px) * sy - (qy - py) * sx
        val uNum = (qx - px) * ry - (qy - py) * rx
        val t = Rational(tNum, denom)
        val u = Rational(uNum, denom)
        if (!t.isUnit() || !u.isUnit()) return@either null
        val x = Rational(px).add(t.multiply(Rational(rx)).bind()).bind()
        val y = Rational(py).add(t.multiply(Rational(ry)).bind()).bind()
        SketchPoint(Inches(roundHalfUp(x)), Inches(roundHalfUp(y)))
    }

    private fun Rational.isUnit(): Boolean = this >= Rational.ZERO && this <= Rational.ONE

    private fun roundHalfUp(r: Rational): Long = (2 * r.num + r.den).floorDiv(2 * r.den)

    /** Squared Euclidean distance (in sixteenths squared) from [p] to the closed segment. */
    private fun distanceSquared(p: SketchPoint, s: SketchSegment): Either<ArithmeticError, Rational> = either {
        val ax = s.a.x.sixteenths; val ay = s.a.y.sixteenths
        val dx = s.b.x.sixteenths - ax; val dy = s.b.y.sixteenths - ay
        val wx = p.x.sixteenths - ax; val wy = p.y.sixteenths - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0L) Rational.ZERO else {
            val raw = Rational(wx * dx + wy * dy, len2)
            if (raw < Rational.ZERO) Rational.ZERO else if (raw > Rational.ONE) Rational.ONE else raw
        }
        val cx = Rational(wx).subtract(t.multiply(Rational(dx)).bind()).bind()
        val cy = Rational(wy).subtract(t.multiply(Rational(dy)).bind()).bind()
        cx.multiply(cx).bind().add(cy.multiply(cy).bind()).bind()
    }
}
