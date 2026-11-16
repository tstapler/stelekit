package dev.stapler.stelekit.sketch.constraints.spike

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Spike 0.1.2 (throwaway until Epic 4.1): row-major dense matrix over a flat [DoubleArray].
 * Clean-room: written from textbook numerical linear algebra; no planegcs/SolveSpace source consulted.
 */
class DenseMatrix(val rows: Int, val cols: Int, val data: DoubleArray = DoubleArray(rows * cols)) {
    operator fun get(r: Int, c: Int): Double = data[r * cols + c]
    operator fun set(r: Int, c: Int, v: Double) { data[r * cols + c] = v }
    fun copy(): DenseMatrix = DenseMatrix(rows, cols, data.copyOf())

    /** Returns A^T A (cols x cols), exploiting symmetry. */
    fun gram(): DenseMatrix {
        val g = DenseMatrix(cols, cols)
        for (i in 0 until rows) {
            val base = i * cols
            for (a in 0 until cols) {
                val va = data[base + a]
                if (va == 0.0) continue
                for (b in a until cols) {
                    val vb = data[base + b]
                    if (vb != 0.0) g.data[a * cols + b] += va * vb
                }
            }
        }
        for (a in 0 until cols) for (b in 0 until a) g.data[a * cols + b] = g.data[b * cols + a]
        return g
    }

    /** Returns A^T v. */
    fun transposeTimes(v: DoubleArray): DoubleArray {
        val out = DoubleArray(cols)
        for (i in 0 until rows) {
            val vi = v[i]
            if (vi == 0.0) continue
            val base = i * cols
            for (c in 0 until cols) out[c] += data[base + c] * vi
        }
        return out
    }
}

/**
 * In-place Cholesky solve of the symmetric positive-definite system [a] x = [b].
 * Returns null when a pivot is not strictly positive (matrix not SPD, or NaN present).
 * [a] is overwritten with its lower factor.
 */
fun choleskySolve(a: DenseMatrix, b: DoubleArray): DoubleArray? {
    val n = a.rows
    for (j in 0 until n) {
        var d = a[j, j]
        for (k in 0 until j) d -= a[j, k] * a[j, k]
        if (!(d > 0.0) || d.isNaN()) return null
        val ljj = sqrt(d)
        a[j, j] = ljj
        for (i in j + 1 until n) {
            var s = a[i, j]
            for (k in 0 until j) s -= a[i, k] * a[j, k]
            a[i, j] = s / ljj
        }
    }
    val y = DoubleArray(n)
    for (i in 0 until n) {
        var s = b[i]
        for (k in 0 until i) s -= a[i, k] * y[k]
        y[i] = s / a[i, i]
    }
    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) {
        var s = y[i]
        for (k in i + 1 until n) s -= a[k, i] * x[k]
        x[i] = s / a[i, i]
    }
    return x
}

internal fun hasNonFinite(v: DoubleArray): Boolean {
    for (x in v) if (x.isNaN() || abs(x) == Double.POSITIVE_INFINITY) return true
    return false
}
