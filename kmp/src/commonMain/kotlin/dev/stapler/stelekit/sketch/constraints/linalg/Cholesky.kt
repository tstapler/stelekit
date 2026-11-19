package dev.stapler.stelekit.sketch.constraints.linalg

import kotlin.math.sqrt

/**
 * Cholesky factorisation of a symmetric positive-definite n x n matrix, with an optional diagonal
 * damping term (Levenberg-Marquardt normal equations: (J^T J + lambda I) dx = J^T r).
 * The factor lives in a buffer owned by the instance and reused across [factor] calls.
 */
class Cholesky(val n: Int) {
    private val lower = DenseMatrix(n, n)
    private val y = DoubleArray(n)

    /**
     * Factors [a] + [damping] * I (only the lower triangle of [a] is read; [a] is not modified).
     * Returns false when a pivot is not strictly positive or is NaN (not SPD, or NaN input).
     */
    fun factor(a: DenseMatrix, damping: Double = 0.0): Boolean {
        require(a.rows == n && a.cols == n) { "expected ${n}x$n" }
        val l = lower.data
        for (j in 0 until n) {
            var d = a[j, j] + damping
            for (k in 0 until j) d -= l[j * n + k] * l[j * n + k]
            if (!(d > 0.0)) return false
            val ljj = sqrt(d)
            l[j * n + j] = ljj
            for (i in j + 1 until n) {
                var s = a[i, j]
                for (k in 0 until j) s -= l[i * n + k] * l[j * n + k]
                l[i * n + j] = s / ljj
            }
        }
        return true
    }

    /** Solves with the last successful [factor]; writes the solution to [out] (may alias [b]). */
    fun solve(b: DoubleArray, out: DoubleArray) {
        require(b.size >= n && out.size >= n) { "vector too short" }
        val l = lower.data
        for (i in 0 until n) {
            var s = b[i]
            for (k in 0 until i) s -= l[i * n + k] * y[k]
            y[i] = s / l[i * n + i]
        }
        for (i in n - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1 until n) s -= l[k * n + i] * out[k]
            out[i] = s / l[i * n + i]
        }
    }
}
