package dev.stapler.stelekit.sketch.constraints.constraints

import dev.stapler.stelekit.sketch.constraints.linalg.DenseMatrix
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Case 1 and 3 of the four tangent cases: line [a]-[b] tangent to the circle at [center] with [radius]
 * (a scalar radius for a circle, [RadiusRef.arc] for the circle of an arc). One row: the signed distance
 * from the center to the line minus `side * r`. [side] (+1 or -1) is the side of the line the center was on
 * when the constraint was made ([sideOf]); freezing it keeps the solver from flipping to the mirror
 * tangent. The row is `cross(u, w) / |u| - side * r` with `u = b - a`, `w = center - a`.
 */
class TangentLineCircle(
    override val id: String,
    private val a: Int,
    private val b: Int,
    private val center: Int,
    private val radius: RadiusRef,
    private val side: Double,
) : ConstraintEquation {
    override val rowCount = 1

    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) {
        out[off] = signedDistance(x, a, b, center) - side * radius.value(x)
    }

    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        val ux = x[b] - x[a]; val uy = x[b + 1] - x[a + 1]
        val wx = x[center] - x[a]; val wy = x[center + 1] - x[a + 1]
        val l = sqrt(ux * ux + uy * uy)
        if (l >= LENGTH_FLOOR) {
            val cr = ux * wy - uy * wx
            val l3 = l * l * l
            val dux = wy / l - cr * ux / l3
            val duy = -wx / l - cr * uy / l3
            val dwx = -uy / l
            val dwy = ux / l
            j.addAt(row0, b, dux); j.addAt(row0, a, -dux)
            j.addAt(row0, b + 1, duy); j.addAt(row0, a + 1, -duy)
            j.addAt(row0, center, dwx); j.addAt(row0, a, -dwx)
            j.addAt(row0, center + 1, dwy); j.addAt(row0, a + 1, -dwy)
        }
        radius.addGradient(x, -side, j, row0)
    }

    companion object {
        /** Signed distance from [center] to the line [a]-[b]; 0 for a degenerate line. */
        fun signedDistance(x: DoubleArray, a: Int, b: Int, center: Int): Double {
            val ux = x[b] - x[a]; val uy = x[b + 1] - x[a + 1]
            val l = sqrt(ux * ux + uy * uy)
            if (l < LENGTH_FLOOR) return 0.0
            return (ux * (x[center + 1] - x[a + 1]) - uy * (x[center] - x[a])) / l
        }

        /** The side to freeze for the start configuration [x]. */
        fun sideOf(x: DoubleArray, a: Int, b: Int, center: Int): Double = signOf(signedDistance(x, a, b, center))
    }
}

/**
 * Case 2: circles (or arc circles) tangent. One row: `|c1 - c2| - (s1 * r1 + s2 * r2)`, with `(s1, s2)`
 * `(1, 1)` for external tangency and `(1, -1)` or `(-1, 1)` for internal tangency (the larger circle's
 * radius positive). [infer] picks the branch nearest to the start configuration so it cannot flip.
 */
class TangentCircleCircle(
    override val id: String,
    private val c1: Int,
    private val r1: RadiusRef,
    private val c2: Int,
    private val r2: RadiusRef,
    private val s1: Double,
    private val s2: Double,
) : ConstraintEquation {
    override val rowCount = 1

    override fun residual(x: DoubleArray, out: DoubleArray, off: Int) {
        out[off] = dist(x, c1, c2) - (s1 * r1.value(x) + s2 * r2.value(x))
    }

    override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        addLengthGradient(x, c1, c2, 1.0, j, row0)
        r1.addGradient(x, -s1, j, row0)
        r2.addGradient(x, -s2, j, row0)
    }

    companion object {
        /** The tangency branch (external or the matching internal one) closest to the configuration [x]. */
        fun infer(id: String, x: DoubleArray, c1: Int, r1: RadiusRef, c2: Int, r2: RadiusRef): TangentCircleCircle {
            val d = dist(x, c1, c2)
            val ra = r1.value(x)
            val rb = r2.value(x)
            val external = abs(d - (ra + rb))
            val internal = abs(d - abs(ra - rb))
            return when {
                external <= internal -> TangentCircleCircle(id, c1, r1, c2, r2, 1.0, 1.0)
                ra >= rb -> TangentCircleCircle(id, c1, r1, c2, r2, 1.0, -1.0)
                else -> TangentCircleCircle(id, c1, r1, c2, r2, -1.0, 1.0)
            }
        }
    }
}

/**
 * Case 4: tangent at a shared endpoint. A line [a]-[b] meets an arc at the arc's end [p] (arc centered at
 * [center]) tangentially when the line is perpendicular to the radius `p - center`: one row,
 * `dot(b - a, p - center)`. The endpoint coincidence itself is a separate [Coincident]. Delegates to
 * [Perpendicular] (the radius segment is `center`-`p`).
 */
class TangentLineArcEndpoint(id: String, a: Int, b: Int, center: Int, p: Int) :
    ConstraintEquation by Perpendicular(id, a, b, center, p)

/**
 * Case 4, two arcs: arcs centered at [c1] and [c2] share the endpoint [p] and are tangent there when the
 * two radii are collinear: `cross(p - c1, p - c2) = 0`. Delegates to [Parallel].
 */
class TangentArcArcEndpoint(id: String, c1: Int, c2: Int, p: Int) :
    ConstraintEquation by Parallel(id, c1, p, c2, p)
