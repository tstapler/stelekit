package dev.stapler.stelekit.sketch.constraints.spike

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Householder QR with column pivoting of an m x n matrix (clean-room, textbook).
 * [r] holds the upper-triangular factor (in the upper triangle), [perm] maps pivot position to
 * original column, [rank] counts diagonals above [relTol] times the first diagonal.
 */
class PivotedQr(val r: DenseMatrix, val perm: IntArray, val rank: Int)

fun pivotedQr(input: DenseMatrix, relTol: Double = 1e-9): PivotedQr {
    val m = input.rows
    val n = input.cols
    val a = input.copy()
    // Scale to unit max-abs so squared norms cannot overflow/underflow (rank is scale-invariant here).
    var maxAbs = 0.0
    for (v in a.data) if (abs(v) > maxAbs) maxAbs = abs(v)
    if (maxAbs.isNaN() || maxAbs == Double.POSITIVE_INFINITY) return PivotedQr(a, IntArray(n) { it }, 0)
    if (maxAbs > 0.0) for (i in a.data.indices) a.data[i] /= maxAbs
    val perm = IntArray(n) { it }
    val colNorm2 = DoubleArray(n)
    for (c in 0 until n) { var s = 0.0; for (i in 0 until m) s += a[i, c] * a[i, c]; colNorm2[c] = s }
    val steps = minOf(m, n)
    var firstDiag = 0.0
    var rank = 0
    for (k in 0 until steps) {
        // Pivot: largest remaining column norm; strict ">" keeps the earliest column on ties,
        // so a duplicate constraint is the one reported as dependent.
        var best = k
        var bestNorm = -1.0
        for (c in k until n) {
            var s = 0.0
            for (i in k until m) s += a[i, c] * a[i, c]
            colNorm2[c] = s
            if (s > bestNorm * (1.0 + 1e-12) + 1e-300) { best = c; bestNorm = s }
        }
        if (best != k) {
            for (i in 0 until m) { val t = a[i, k]; a[i, k] = a[i, best]; a[i, best] = t }
            val tp = perm[k]; perm[k] = perm[best]; perm[best] = tp
        }
        var norm = 0.0
        for (i in k until m) norm += a[i, k] * a[i, k]
        norm = sqrt(norm)
        if (norm == 0.0 || norm.isNaN()) break
        val alpha = if (a[k, k] > 0.0) -norm else norm
        val v = DoubleArray(m - k)
        for (i in k until m) v[i - k] = a[i, k]
        v[0] -= alpha
        var vv = 0.0
        for (x in v) vv += x * x
        if (vv > 0.0) {
            for (c in k until n) {
                var dot = 0.0
                for (i in k until m) dot += v[i - k] * a[i, c]
                val f = 2.0 * dot / vv
                for (i in k until m) a[i, c] -= f * v[i - k]
            }
        }
        if (k == 0) firstDiag = abs(a[0, 0])
        if (abs(a[k, k]) > relTol * firstDiag && abs(a[k, k]) > 1e-300) rank++ else break
    }
    return PivotedQr(a, perm, rank)
}
