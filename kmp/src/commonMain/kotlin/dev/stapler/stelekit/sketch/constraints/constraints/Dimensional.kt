package dev.stapler.stelekit.sketch.constraints.constraints

import dev.stapler.stelekit.sketch.constraints.linalg.DenseMatrix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * A dimensional constraint: a measured quantity driven to [value]. In reference mode ([reference] true)
 * the dimension only reports [measure] and contributes no rows, so it never constrains the geometry.
 */
abstract class DimensionalEquation(final override val id: String, val reference: Boolean, var value: Double) : ConstraintEquation {
    override val rowCount: Int get() = if (reference) 0 else 1

    /** The current measured value (also what a reference dimension displays). */
    abstract fun measure(x: DoubleArray): Double

    /** Adds the gradient of [measure] into [row]. */
    protected abstract fun addMeasureGradient(x: DoubleArray, j: DenseMatrix, row: Int)

    /** The row value; angles override this to wrap. */
    protected open fun error(x: DoubleArray): Double = measure(x) - value

    final override fun residual(x: DoubleArray, out: DoubleArray, off: Int) {
        if (!reference) out[off] = error(x)
    }

    final override fun jacobian(x: DoubleArray, j: DenseMatrix, row0: Int) {
        if (!reference) addMeasureGradient(x, j, row0)
    }
}

/** `|q - p| = value`. */
class DistanceDimension(id: String, private val p: Int, private val q: Int, value: Double, reference: Boolean = false) :
    DimensionalEquation(id, reference, value) {
    override fun measure(x: DoubleArray): Double = dist(x, p, q)
    override fun addMeasureGradient(x: DoubleArray, j: DenseMatrix, row: Int) = addLengthGradient(x, p, q, 1.0, j, row)
}

/**
 * Perpendicular distance from point [p] to the infinite line through [a] and [b] equals [value] (UX-115 centre-to-edge).
 * The measure is `|cross| / |b - a|`; it is not differentiable at zero, where the positive-side one-sided gradient is used, and a line shorter
 * than [LENGTH_FLOOR] measures zero with no gradient, so the rows stay finite.
 */
class PointLineDistance(id: String, private val p: Int, private val a: Int, private val b: Int, value: Double, reference: Boolean = false) :
    DimensionalEquation(id, reference, value) {
    private fun cross(x: DoubleArray): Double = (x[b] - x[a]) * (x[p + 1] - x[a + 1]) - (x[b + 1] - x[a + 1]) * (x[p] - x[a])

    override fun measure(x: DoubleArray): Double {
        val l = dist(x, a, b)
        return if (l < LENGTH_FLOOR) 0.0 else abs(cross(x)) / l
    }

    override fun addMeasureGradient(x: DoubleArray, j: DenseMatrix, row: Int) {
        val dx = x[b] - x[a]
        val dy = x[b + 1] - x[a + 1]
        val wx = x[p] - x[a]
        val wy = x[p + 1] - x[a + 1]
        val l = dist(x, a, b)
        val cr = cross(x)
        if (l < LENGTH_FLOOR) return
        // Exactly on the line the measure |cross| / l has a kink: take the one-sided derivative toward the positive side so the solve can leave it.
        val s = if (cr >= 0.0) 1.0 else -1.0
        val m = abs(cr) / l
        // d(cross) per variable, then d(m) = s * d(cross) / l - m * d(l) / l, with d(l) = (+-dx, +-dy) / l.
        j.addAt(row, p, s * -dy / l)
        j.addAt(row, p + 1, s * dx / l)
        j.addAt(row, a, s * (dy - wy) / l + m * dx / (l * l))
        j.addAt(row, a + 1, s * (wx - dx) / l + m * dy / (l * l))
        j.addAt(row, b, s * wy / l - m * dx / (l * l))
        j.addAt(row, b + 1, s * -wx / l - m * dy / (l * l))
    }
}

/** `q.x - p.x = value` (signed, so the direction of the dimension is part of the constraint). */
class DistanceX(id: String, private val p: Int, private val q: Int, value: Double, reference: Boolean = false) :
    DimensionalEquation(id, reference, value) {
    override fun measure(x: DoubleArray): Double = x[q] - x[p]
    override fun addMeasureGradient(x: DoubleArray, j: DenseMatrix, row: Int) {
        j.addAt(row, q, 1.0); j.addAt(row, p, -1.0)
    }
}

/** `q.y - p.y = value`. */
class DistanceY(id: String, private val p: Int, private val q: Int, value: Double, reference: Boolean = false) :
    DimensionalEquation(id, reference, value) {
    override fun measure(x: DoubleArray): Double = x[q + 1] - x[p + 1]
    override fun addMeasureGradient(x: DoubleArray, j: DenseMatrix, row: Int) {
        j.addAt(row, q + 1, 1.0); j.addAt(row, p + 1, -1.0)
    }
}

/** Circle or arc radius equals [value]. */
class RadiusDimension(id: String, private val radius: RadiusRef, value: Double, reference: Boolean = false) :
    DimensionalEquation(id, reference, value) {
    override fun measure(x: DoubleArray): Double = radius.value(x)
    override fun addMeasureGradient(x: DoubleArray, j: DenseMatrix, row: Int) = radius.addGradient(x, 1.0, j, row)
}

/** Circle or arc diameter (twice the radius) equals [value]. */
class DiameterDimension(id: String, private val radius: RadiusRef, value: Double, reference: Boolean = false) :
    DimensionalEquation(id, reference, value) {
    override fun measure(x: DoubleArray): Double = 2.0 * radius.value(x)
    override fun addMeasureGradient(x: DoubleArray, j: DenseMatrix, row: Int) = radius.addGradient(x, 2.0, j, row)
}

/**
 * Which of the equivalent angles the dimension means, so the solver never jumps between them.
 * [Directed]: the counter-clockwise angle from segment u to segment v, modulo 2 pi (reversing either segment
 * changes the angle by pi, as the user sees it). [Lines]: the angle between the two infinite lines, signed
 * and modulo pi (reversing a segment does not matter).
 */
enum class AnglePolicy(internal val period: Double) {
    Directed(2.0 * PI),
    Lines(PI),
}

/** Angle from segment a1-a2 to segment b1-b2 equals [value] radians under [policy]. The row wraps into half a period. */
class AngleDimension(
    id: String,
    a1: Int,
    a2: Int,
    b1: Int,
    b2: Int,
    value: Double,
    private val policy: AnglePolicy = AnglePolicy.Directed,
    reference: Boolean = false,
) : DimensionalEquation(id, reference, value) {
    private val s = TwoSegments(a1, a2, b1, b2)

    override fun measure(x: DoubleArray): Double {
        val ux = s.ux(x); val uy = s.uy(x); val vx = s.vx(x); val vy = s.vy(x)
        return atan2(ux * vy - uy * vx, ux * vx + uy * vy)
    }

    override fun error(x: DoubleArray): Double = wrap(measure(x) - value, policy.period)

    override fun addMeasureGradient(x: DoubleArray, j: DenseMatrix, row: Int) {
        val ux = s.ux(x); val uy = s.uy(x); val vx = s.vx(x); val vy = s.vy(x)
        val cr = ux * vy - uy * vx
        val dt = ux * vx + uy * vy
        val den = cr * cr + dt * dt
        if (den < ANGLE_DENOMINATOR_FLOOR) return
        // d atan2(cr, dt) = (dt * dcr - cr * ddt) / den
        s.addPartials(
            j, row,
            (dt * vy - cr * vx) / den,
            (dt * -vx - cr * vy) / den,
            (dt * -uy - cr * ux) / den,
            (dt * ux - cr * uy) / den,
        )
    }
}
