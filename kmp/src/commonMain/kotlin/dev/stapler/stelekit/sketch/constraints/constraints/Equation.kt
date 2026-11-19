package dev.stapler.stelekit.sketch.constraints.constraints

import dev.stapler.stelekit.sketch.constraints.linalg.DenseMatrix
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sqrt

/**
 * One constraint of the v1 set as residual rows with an analytic Jacobian (Story 4.1.2). Clean-room
 * (ADR-003, OD-3): textbook vector calculus only.
 *
 * Variable layout is the caller's: a point is two consecutive variables `x[p]`, `x[p + 1]` and is passed as
 * the index `p` of its x coordinate; a scalar (a circle radius) is one variable. [residual] writes [rowCount]
 * values at `out[off ..]`; [jacobian] ADDS its entries into rows `row0 ..` of a zeroed matrix, so a variable
 * shared by two roles in one constraint accumulates correctly. Both stay finite for degenerate geometry
 * (zero-length line, zero radius): a length below [LENGTH_FLOOR] contributes a zero gradient, never a NaN.
 */
interface ConstraintEquation {
    val id: String
    val rowCount: Int
    fun residual(x: DoubleArray, out: DoubleArray, off: Int)
    fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int)
}

/** Length below which a direction is treated as degenerate (gradient dropped). Scene units. */
internal const val LENGTH_FLOOR = 1e-12

/** Squared-magnitude floor below which an angle's atan2 gradient is dropped (degenerate direction). */
internal const val ANGLE_DENOMINATOR_FLOOR = 1e-24

internal const val TWO_PI = 2.0 * PI

internal fun DenseMatrix.addAt(row: Int, col: Int, v: Double) { data[row * cols + col] += v }

internal fun dist(x: DoubleArray, a: Int, b: Int): Double {
    val dx = x[b] - x[a]
    val dy = x[b + 1] - x[a + 1]
    return sqrt(dx * dx + dy * dy)
}

/** Adds `s * d|b - a|` to [row]; a degenerate (shorter than [LENGTH_FLOOR]) segment adds nothing. */
internal fun addLengthGradient(x: DoubleArray, a: Int, b: Int, s: Double, j: DenseMatrix, row: Int) {
    val dx = x[b] - x[a]
    val dy = x[b + 1] - x[a + 1]
    val l = sqrt(dx * dx + dy * dy)
    if (l < LENGTH_FLOOR) return
    val gx = dx / l * s
    val gy = dy / l * s
    j.addAt(row, b, gx); j.addAt(row, a, -gx)
    j.addAt(row, b + 1, gy); j.addAt(row, a + 1, -gy)
}

/**
 * Where a circle-like object gets its radius: a scalar variable (a circle, `EllipseElement` with equal radii)
 * or the distance from a center to a rim point (an arc, whose start point defines the radius).
 */
class RadiusRef private constructor(private val scalar: Int, private val center: Int, private val rim: Int) {
    fun value(x: DoubleArray): Double = if (scalar >= 0) x[scalar] else dist(x, center, rim)

    /** Adds `s * dRadius` into [row] of [j]. */
    fun addGradient(x: DoubleArray, s: Double, j: DenseMatrix, row: Int) {
        if (scalar >= 0) j.addAt(row, scalar, s) else addLengthGradient(x, center, rim, s, j, row)
    }

    companion object {
        fun scalar(variable: Int) = RadiusRef(variable, -1, -1)
        fun arc(center: Int, rim: Int) = RadiusRef(-1, center, rim)
    }
}

/** Wraps [a] into `[-period/2, period/2]`. */
internal fun wrap(a: Double, period: Double): Double = a - period * round(a / period)

internal fun signOf(v: Double): Double = if (v >= 0.0) 1.0 else -1.0

/** Two directed segments u = a2 - a1 and v = b2 - b1 sharing their point-index bookkeeping. */
internal class TwoSegments(val a1: Int, val a2: Int, val b1: Int, val b2: Int) {
    fun ux(x: DoubleArray) = x[a2] - x[a1]
    fun uy(x: DoubleArray) = x[a2 + 1] - x[a1 + 1]
    fun vx(x: DoubleArray) = x[b2] - x[b1]
    fun vy(x: DoubleArray) = x[b2 + 1] - x[b1 + 1]

    /** Adds the partials w.r.t. (ux, uy, vx, vy) onto the four points they are differences of. */
    fun addPartials(j: DenseMatrix, row: Int, dux: Double, duy: Double, dvx: Double, dvy: Double) {
        j.addAt(row, a2, dux); j.addAt(row, a1, -dux)
        j.addAt(row, a2 + 1, duy); j.addAt(row, a1 + 1, -duy)
        j.addAt(row, b2, dvx); j.addAt(row, b1, -dvx)
        j.addAt(row, b2 + 1, dvy); j.addAt(row, b1 + 1, -dvy)
    }
}
