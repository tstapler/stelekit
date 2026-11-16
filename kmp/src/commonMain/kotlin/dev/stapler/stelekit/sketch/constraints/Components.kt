package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.sketch.constraints.constraints.ConstraintEquation
import dev.stapler.stelekit.sketch.constraints.linalg.DenseMatrix
import kotlin.math.abs
import kotlin.random.Random

/**
 * A connected component of the constraint graph: [equations] are indices into the problem's equation list,
 * [variables] the sorted global variable indices they touch. Components share no variable, so each solves alone
 * (Story 4.1.3b); a variable in no component is free and keeps its start value.
 */
internal class Component(val equations: IntArray, val variables: IntArray)

/** Seed of the deterministic probes that reveal which variables an equation depends on. */
private const val SUPPORT_PROBE_SEED = 20_260_116

/** Number of perturbed probe configurations on top of the start (a zero-length line hides its columns at the start only). */
private const val SUPPORT_PROBE_COUNT = 2

private const val SUPPORT_PROBE_SPREAD = 1.0

/**
 * Variable support of each equation: the union of the non-zero Jacobian columns at [x] and at deterministic
 * perturbations of it. The equations expose no variable list, and a degenerate configuration can zero a gradient, so
 * one evaluation is not enough. Limit: a column that is zero at every probe is treated as absent; [Solver] guards this
 * by re-checking the full residual after the per-component solves.
 */
internal fun variableSupport(equations: List<ConstraintEquation>, x: DoubleArray): List<IntArray> {
    val n = x.size
    val rnd = Random(SUPPORT_PROBE_SEED)
    val probes = ArrayList<DoubleArray>(SUPPORT_PROBE_COUNT + 1)
    probes += x
    repeat(SUPPORT_PROBE_COUNT) { probes += DoubleArray(n) { x[it] + (rnd.nextDouble() - 0.5) * SUPPORT_PROBE_SPREAD } }
    return equations.map { eq ->
        if (eq.rowCount == 0) return@map IntArray(0)
        val seen = BooleanArray(n)
        val j = DenseMatrix(eq.rowCount, n)
        for (probe in probes) {
            j.fill(0.0)
            eq.jacobian(probe, j, 0)
            markNonZeroColumns(j, seen)
        }
        (0 until n).filter { seen[it] }.toIntArray()
    }
}

private fun markNonZeroColumns(j: DenseMatrix, seen: BooleanArray) {
    for (r in 0 until j.rows) for (c in 0 until j.cols) if (abs(j[r, c]) > 0.0) seen[c] = true
}

/** Union-find decomposition of [equations] by shared variables; equations with no variables each form their own component. */
internal fun decompose(equations: List<ConstraintEquation>, support: List<IntArray>, variableCount: Int): List<Component> {
    val parent = IntArray(equations.size) { it }
    fun find(a: Int): Int {
        var r = a
        while (parent[r] != r) r = parent[r]
        var c = a
        while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
        return r
    }
    val owner = IntArray(variableCount) { -1 }
    for (e in equations.indices) for (v in support[e]) {
        if (owner[v] < 0) owner[v] = e else parent[find(e)] = find(owner[v])
    }
    val groups = LinkedHashMap<Int, MutableList<Int>>()
    for (e in equations.indices) groups.getOrPut(find(e)) { ArrayList() } += e
    return groups.values.map { eqs ->
        val vars = LinkedHashSet<Int>()
        for (e in eqs) for (v in support[e]) vars += v
        Component(eqs.toIntArray(), vars.sorted().toIntArray())
    }
}
