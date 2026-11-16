package dev.stapler.stelekit.sketch.constraints.linalg

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Householder QR with column pivoting of an m x n matrix (clean-room, textbook).
 *
 * After [factor]: [r] holds the upper-triangular factor, [perm] maps pivot position to original
 * column, and [rank] counts leading diagonals above `relTol * |R[0,0]|`. Pivoting picks the largest
 * remaining column norm, and on a tie the earliest column, so a duplicated column is the one reported
 * as dependent. All working storage is owned by the instance and reused across calls.
 */
class PivotedQr(val m: Int, val n: Int) {
    val r = DenseMatrix(m, n)
    val perm = IntArray(n)
    var rank = 0
        private set
    private val v = DoubleArray(m)

    /** Factors [input] (m x n, not modified) and returns the numerical rank. */
    fun factor(input: DenseMatrix, relTol: Double = 1e-9): Int {
        require(input.rows == m && input.cols == n) { "expected ${m}x$n" }
        r.copyFrom(input)
        for (c in 0 until n) perm[c] = c
        rank = 0
        // Scale to unit max-abs so squared norms cannot overflow or underflow (rank is scale-invariant).
        var maxAbs = 0.0
        for (x in r.data) if (abs(x) > maxAbs) maxAbs = abs(x)
        if (!maxAbs.isFinite()) return 0
        if (maxAbs > 0.0) for (i in r.data.indices) r.data[i] /= maxAbs
        var firstDiag = 0.0
        for (k in 0 until minOf(m, n)) {
            var best = k
            var bestNorm = -1.0
            for (c in k until n) {
                var s = 0.0
                for (i in k until m) s += r[i, c] * r[i, c]
                if (s > bestNorm * (1.0 + 1e-12) + 1e-300) { best = c; bestNorm = s }
            }
            if (best != k) {
                for (i in 0 until m) { val t = r[i, k]; r[i, k] = r[i, best]; r[i, best] = t }
                val tp = perm[k]; perm[k] = perm[best]; perm[best] = tp
            }
            var norm = 0.0
            for (i in k until m) norm += r[i, k] * r[i, k]
            norm = sqrt(norm)
            if (norm == 0.0 || norm.isNaN()) break
            val alpha = if (r[k, k] > 0.0) -norm else norm
            for (i in k until m) v[i - k] = r[i, k]
            v[0] -= alpha
            var vv = 0.0
            for (i in 0 until m - k) vv += v[i] * v[i]
            if (vv > 0.0) {
                for (c in k until n) {
                    var dot = 0.0
                    for (i in k until m) dot += v[i - k] * r[i, c]
                    val f = 2.0 * dot / vv
                    for (i in k until m) r[i, c] -= f * v[i - k]
                }
            }
            if (k == 0) firstDiag = abs(r[0, 0])
            if (abs(r[k, k]) > relTol * firstDiag && abs(r[k, k]) > 1e-300) rank++ else break
        }
        return rank
    }
}
