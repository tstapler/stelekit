package dev.stapler.stelekit.sketch.constraints.spike

import kotlin.math.abs
import kotlin.math.sqrt

/** Spike 0.1.2: variable layout. Point i owns variables 2i (x) and 2i+1 (y); a scalar var holds a circle radius. */
class SpikeSketch {
    private val values = ArrayList<Double>()
    fun addPoint(x: Double, y: Double): Int { values += x; values += y; return values.size / 2 - 1 }
    fun addScalar(v: Double): Int { values += v; return values.size - 1 }
    fun px(p: Int) = 2 * p
    fun py(p: Int) = 2 * p + 1
    fun initial(): DoubleArray = values.toDoubleArray()
    val variableCount get() = values.size
}

/** One constraint contributes [count] residual rows. Jacobian entries are written densely into row [row0]. */
sealed class SpikeConstraint(val id: String) {
    abstract val count: Int
    abstract fun eval(x: DoubleArray, out: DoubleArray, off: Int)
    abstract fun jac(x: DoubleArray, j: DenseMatrix, row0: Int)
}

private const val LEN_EPS = 1e-12

/** Points p and q coincide (2 rows). */
class Coincident(id: String, private val p: Int, private val q: Int) : SpikeConstraint(id) {
    override val count = 2
    override fun eval(x: DoubleArray, out: DoubleArray, off: Int) {
        out[off] = x[2 * p] - x[2 * q]; out[off + 1] = x[2 * p + 1] - x[2 * q + 1]
    }
    override fun jac(x: DoubleArray, j: DenseMatrix, row0: Int) {
        j[row0, 2 * p] = 1.0; j[row0, 2 * q] = -1.0
        j[row0 + 1, 2 * p + 1] = 1.0; j[row0 + 1, 2 * q + 1] = -1.0
    }
}

/** Line a-b horizontal: ay = by. */
class Horizontal(id: String, private val a: Int, private val b: Int) : SpikeConstraint(id) {
    override val count = 1
    override fun eval(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = x[2 * a + 1] - x[2 * b + 1] }
    override fun jac(x: DoubleArray, j: DenseMatrix, row0: Int) { j[row0, 2 * a + 1] = 1.0; j[row0, 2 * b + 1] = -1.0 }
}

/** Line a-b vertical: ax = bx. */
class Vertical(id: String, private val a: Int, private val b: Int) : SpikeConstraint(id) {
    override val count = 1
    override fun eval(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = x[2 * a] - x[2 * b] }
    override fun jac(x: DoubleArray, j: DenseMatrix, row0: Int) { j[row0, 2 * a] = 1.0; j[row0, 2 * b] = -1.0 }
}

private fun len(x: DoubleArray, a: Int, b: Int): Double {
    val dx = x[2 * b] - x[2 * a]; val dy = x[2 * b + 1] - x[2 * a + 1]
    return sqrt(dx * dx + dy * dy)
}

/** Writes d|b-a|/d(vars) scaled by [s] into row [row]. */
private fun lengthGrad(x: DoubleArray, a: Int, b: Int, s: Double, j: DenseMatrix, row: Int) {
    val dx = x[2 * b] - x[2 * a]; val dy = x[2 * b + 1] - x[2 * a + 1]
    val l = maxOf(sqrt(dx * dx + dy * dy), LEN_EPS)
    val gx = dx / l * s; val gy = dy / l * s
    j[row, 2 * b] += gx; j[row, 2 * a] -= gx
    j[row, 2 * b + 1] += gy; j[row, 2 * a + 1] -= gy
}

/** |b-a| = d. */
class Distance(id: String, private val a: Int, private val b: Int, private val d: Double) : SpikeConstraint(id) {
    override val count = 1
    override fun eval(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = len(x, a, b) - d }
    override fun jac(x: DoubleArray, j: DenseMatrix, row0: Int) = lengthGrad(x, a, b, 1.0, j, row0)
}

/** |a1-a2| = |b1-b2|. */
class EqualLength(id: String, private val a1: Int, private val a2: Int, private val b1: Int, private val b2: Int) : SpikeConstraint(id) {
    override val count = 1
    override fun eval(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = len(x, a1, a2) - len(x, b1, b2) }
    override fun jac(x: DoubleArray, j: DenseMatrix, row0: Int) {
        lengthGrad(x, a1, a2, 1.0, j, row0); lengthGrad(x, b1, b2, -1.0, j, row0)
    }
}

/** Pin point p to (tx, ty): the spike's stand-in for "Fixed" and for a pointer drag target (2 rows). */
class Pin(id: String, private val p: Int, var tx: Double, var ty: Double) : SpikeConstraint(id) {
    override val count = 2
    override fun eval(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = x[2 * p] - tx; out[off + 1] = x[2 * p + 1] - ty }
    override fun jac(x: DoubleArray, j: DenseMatrix, row0: Int) { j[row0, 2 * p] = 1.0; j[row0 + 1, 2 * p + 1] = 1.0 }
}

/** Scalar variable (a radius) equals [v]. */
class ScalarEquals(id: String, private val v: Int, private val value: Double) : SpikeConstraint(id) {
    override val count = 1
    override fun eval(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = x[v] - value }
    override fun jac(x: DoubleArray, j: DenseMatrix, row0: Int) { j[row0, v] = 1.0 }
}

/**
 * Line a-b tangent to the circle (center c, radius var r). The signed centre-to-line distance
 * minus side*r, with `side` frozen from the starting configuration, so the tangent side cannot flip.
 */
class TangentLineCircle(id: String, private val a: Int, private val b: Int, private val c: Int, private val r: Int, initial: DoubleArray) : SpikeConstraint(id) {
    override val count = 1
    private val side: Double = if (signedDistance(initial) >= 0.0) 1.0 else -1.0
    fun signedDistance(x: DoubleArray): Double {
        val dx = x[2 * b] - x[2 * a]; val dy = x[2 * b + 1] - x[2 * a + 1]
        val l = maxOf(sqrt(dx * dx + dy * dy), LEN_EPS)
        return (dx * (x[2 * c + 1] - x[2 * a + 1]) - dy * (x[2 * c] - x[2 * a])) / l
    }
    override fun eval(x: DoubleArray, out: DoubleArray, off: Int) { out[off] = signedDistance(x) - side * x[r] }
    override fun jac(x: DoubleArray, j: DenseMatrix, row0: Int) {
        // Central differences on the 7 involved variables (spike shortcut; production uses analytic rows).
        val vars = intArrayOf(2 * a, 2 * a + 1, 2 * b, 2 * b + 1, 2 * c, 2 * c + 1, r)
        val tmp = DoubleArray(1)
        for (v in vars) {
            val h = 1e-6 * maxOf(1.0, abs(x[v]))
            val save = x[v]
            x[v] = save + h; eval(x, tmp, 0); val fp = tmp[0]
            x[v] = save - h; eval(x, tmp, 0); val fm = tmp[0]
            x[v] = save
            j[row0, v] = (fp - fm) / (2 * h)
        }
    }
}
