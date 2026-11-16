package dev.stapler.stelekit.sketch.constraints.spike

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.TimeSource

/** Spike 0.1.2 result contract (mirrors ADR-003 item 1). */
sealed class SpikeResult {
    abstract val x: DoubleArray
    abstract val dof: Int
    abstract val residualInf: Double
    abstract val iterations: Int
    class Solved(override val x: DoubleArray, override val dof: Int, override val residualInf: Double, override val iterations: Int) : SpikeResult()
    class Redundant(val ids: List<String>, override val x: DoubleArray, override val dof: Int, override val residualInf: Double, override val iterations: Int) : SpikeResult()
    class Conflict(val ids: List<String>, override val x: DoubleArray, override val dof: Int, override val residualInf: Double, override val iterations: Int) : SpikeResult()
    class NoConvergence(override val x: DoubleArray, override val dof: Int, override val residualInf: Double, override val iterations: Int) : SpikeResult()
}

/** Clean-room Levenberg-Marquardt (Marquardt diagonal scaling, gain-ratio damping) with QR-pivot rank diagnosis. */
class LmSolver(
    private val stopTolerance: Double = 1e-9,
    private val maxIterations: Int = 100,
    val trace: MutableList<DoubleArray>? = null,
) {
    private fun residuals(cs: List<SpikeConstraint>, x: DoubleArray, m: Int): DoubleArray {
        val r = DoubleArray(m); var off = 0
        for (c in cs) { c.eval(x, r, off); off += c.count }
        return r
    }
    private fun jacobian(cs: List<SpikeConstraint>, x: DoubleArray, m: Int): DenseMatrix {
        val j = DenseMatrix(m, x.size); var off = 0
        for (c in cs) { c.jac(x, j, off); off += c.count }
        return j
    }
    private fun infNorm(v: DoubleArray): Double { var m = 0.0; for (a in v) m = max(m, abs(a)); return m }
    private fun norm2(v: DoubleArray): Double { var s = 0.0; for (a in v) s += a * a; return s }

    fun solve(constraints: List<SpikeConstraint>, start: DoubleArray): SpikeResult {
        val n = start.size
        val m = constraints.sumOf { it.count }
        val x = start.copyOf()
        val scale = max(1.0, start.maxOfOrNull { abs(it) } ?: 1.0)
        val tol = stopTolerance * scale
        var r = residuals(constraints, x, m)
        var cost = norm2(r)
        var lambda = 1e-3
        var iter = 0
        while (iter < maxIterations && infNorm(r) > tol && !hasNonFinite(r)) {
            iter++
            val j = jacobian(constraints, x, m)
            val g = j.transposeTimes(r)
            val gram = j.gram()
            var accepted = false
            var tries = 0
            while (!accepted && tries < 30) {
                tries++
                val a = gram.copy()
                for (i in 0 until n) a[i, i] += lambda * max(gram[i, i], 1e-9)
                val rhs = DoubleArray(n) { -g[it] }
                val dx = choleskySolve(a, rhs)
                if (dx == null || hasNonFinite(dx)) { lambda *= 10.0; continue }
                val xn = DoubleArray(n) { x[it] + dx[it] }
                val rn = residuals(constraints, xn, m)
                val costN = norm2(rn)
                if (!costN.isNaN() && costN < cost) {
                    xn.copyInto(x); r = rn; cost = costN
                    trace?.add(doubleArrayOf(iter.toDouble(), cost, lambda))
                    lambda = max(lambda / 5.0, 1e-12)
                    accepted = true
                } else {
                    lambda *= 4.0
                    if (lambda > 1e12) break
                }
            }
            if (!accepted) break
        }
        val resInf = infNorm(r)
        val finite = !hasNonFinite(x) && !resInf.isNaN()
        return diagnose(constraints, x, m, resInf, iter, tol, finite)
    }

    private fun diagnose(cs: List<SpikeConstraint>, x: DoubleArray, m: Int, resInf: Double, iter: Int, tol: Double, finite: Boolean): SpikeResult {
        val n = x.size
        if (!finite) return SpikeResult.NoConvergence(x, n, resInf, iter)
        // Rank over constraints: columns of J^T with each row normalised, so scale does not hide dependence.
        val j = jacobian(cs, x, m)
        val jt = DenseMatrix(n, m)
        for (row in 0 until m) {
            var s = 0.0; for (c in 0 until n) s += j[row, c] * j[row, c]
            val inv = if (s > 1e-24) 1.0 / sqrt(s) else 0.0
            for (c in 0 until n) jt[c, row] = j[row, c] * inv
        }
        val qr = pivotedQr(jt, 1e-7)
        val dof = n - rank(j)
        val converged = resInf <= tol
        val rowsOfConstraint = ArrayList<Int>() // first row of each constraint
        var off = 0
        for (c in cs) { rowsOfConstraint += off; off += c.count }
        val independentRows = qr.perm.take(qr.rank).toSet()
        val dependentRows = (0 until m).filter { it !in independentRows }
        // Constraint is dependent only when every one of its rows is dependent or zero-gradient.
        val constraintOfRow = IntArray(m).also { arr -> var o = 0; cs.forEachIndexed { i, c -> repeat(c.count) { k -> arr[o + k] = i }; o += c.count } }
        val depConstraintRows = LinkedHashMap<Int, MutableList<Int>>()
        for (row in dependentRows) depConstraintRows.getOrPut(constraintOfRow[row]) { ArrayList() } += row
        val depConstraints = depConstraintRows.filter { (ci, rows) -> rows.size == cs[ci].count }.keys.sorted()
        return when {
            !converged && depConstraints.isNotEmpty() -> {
                val ids = LinkedHashSet<Int>()
                for (ci in depConstraints) {
                    ids += ci
                    val firstRow = rowsOfConstraint[ci]
                    for (partner in partnersOf(qr, firstRow)) ids += constraintOfRow[partner]
                }
                SpikeResult.Conflict(ids.sorted().map { cs[it].id }, x, dof, resInf, iter)
            }
            !converged -> SpikeResult.NoConvergence(x, dof, resInf, iter)
            depConstraints.isNotEmpty() -> SpikeResult.Redundant(depConstraints.map { cs[it].id }, x, dof, resInf, iter)
            else -> SpikeResult.Solved(x, dof, resInf, iter)
        }
    }

    /** Pivot rows that carry weight in expressing dependent row [row] (|coefficient| above 1e-6). */
    private fun partnersOf(qr: PivotedQr, row: Int): List<Int> {
        val k = qr.rank
        val pos = qr.perm.indexOf(row)
        if (pos < 0 || k == 0) return emptyList()
        val y = DoubleArray(k)
        for (i in k - 1 downTo 0) {
            var s = qr.r[i, pos]
            for (c in i + 1 until k) s -= qr.r[i, c] * y[c]
            y[i] = s / qr.r[i, i]
        }
        return (0 until k).filter { abs(y[it]) > 1e-6 }.map { qr.perm[it] }
    }

    private fun rank(j: DenseMatrix): Int = pivotedQr(j, 1e-9).rank
}

/** Wall-clock helper usable on every target (monotonic; coarse in browsers). */
inline fun <T> timedMillis(block: () -> T): Pair<T, Double> {
    val t0 = TimeSource.Monotonic.markNow()
    val v = block()
    return v to t0.elapsedNow().inWholeMicroseconds / 1000.0
}
