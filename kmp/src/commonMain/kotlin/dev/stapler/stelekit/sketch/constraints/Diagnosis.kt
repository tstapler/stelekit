package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.sketch.constraints.linalg.DenseMatrix
import dev.stapler.stelekit.sketch.constraints.linalg.PivotedQr
import kotlin.math.abs
import kotlin.math.sqrt

/** Relative tolerance of the pivoted QR that decides which constraint rows are dependent (rows are unit length). */
private const val DEPENDENCE_TOLERANCE = 1e-7

/** A Jacobian row shorter than this carries no gradient: the constraint cannot move any variable. */
private const val ZERO_GRADIENT_NORM = 1e-12

/** A partner row in the expression of a dependent row counts when its coefficient exceeds this. */
private const val PARTNER_WEIGHT = 1e-6

/**
 * Rank diagnosis of the final system (Story 4.1.3b): degrees of freedom from the Jacobian rank, constraints that
 * duplicate others (redundant), the rows that explain a stall (conflict) and the constraints with no gradient
 * (degenerate geometry). Computed per connected component.
 */
internal class Diagnosis(val dof: Int, val redundant: List<String>, val degenerate: List<String>) {

    companion object {
        fun of(problem: ConstraintProblem, x: DoubleArray, @Suppress("UNUSED_PARAMETER") tolerance: Double): Diagnosis {
            var rank = 0
            val redundant = LinkedHashSet<String>()
            val degenerate = LinkedHashSet<String>()
            for (comp in problem.components(x)) {
                val view = RowView(problem, comp, x)
                degenerate += view.zeroGradientIds()
                val qr = view.factor() ?: continue
                rank += qr.rank
                redundant += view.dependentConstraintIds(qr)
            }
            return Diagnosis(problem.variableCount - rank, redundant.toList() - degenerate.toSet(), degenerate.toList())
        }

        /** Dependent constraints of the [failed] components at the stalled point [x], each with the rows that explain it. */
        fun conflictIds(problem: ConstraintProblem, x: DoubleArray, failed: List<Component>): List<String> {
            val ids = LinkedHashSet<String>()
            for (comp in failed) {
                val view = RowView(problem, comp, x)
                val qr = view.factor() ?: continue
                ids += view.conflictIds(qr)
            }
            return ids.toList()
        }
    }
}

/** The Jacobian of one component at a point, rows normalised to unit length, with the row-to-equation map. */
private class RowView(private val problem: ConstraintProblem, comp: Component, x: DoubleArray) {
    private val equations = comp.equations.filter { problem.equations[it].rowCount > 0 }
    private val m = equations.sumOf { problem.equations[it].rowCount }
    private val cols = comp.variables
    private val n = cols.size
    private val rowEquation = IntArray(m)
    private val rowIsZero = BooleanArray(m)
    private val normalised = DenseMatrix(n, m)

    init {
        val full = DenseMatrix(maxOf(m, 1), maxOf(problem.variableCount, 1))
        var off = 0
        for ((k, e) in equations.withIndex()) {
            val eq = problem.equations[e]
            eq.jacobian(x, full, off)
            for (r in 0 until eq.rowCount) rowEquation[off + r] = k
            off += eq.rowCount
        }
        for (row in 0 until m) {
            var s = 0.0
            for (c in 0 until n) { val v = full[row, cols[c]]; s += v * v }
            val norm = sqrt(s)
            rowIsZero[row] = !(norm >= ZERO_GRADIENT_NORM)
            val inv = if (rowIsZero[row]) 0.0 else 1.0 / norm
            for (c in 0 until n) normalised[c, row] = full[row, cols[c]] * inv
        }
    }

    private fun idOf(row: Int): String = problem.equations[equations[rowEquation[row]]].id

    /** Constraints with at least one zero-gradient row. */
    fun zeroGradientIds(): List<String> = (0 until m).filter { rowIsZero[it] }.map { idOf(it) }.distinct()

    fun factor(): PivotedQr? {
        if (m == 0 || n == 0) return null
        val qr = PivotedQr(n, m)
        qr.factor(normalised, DEPENDENCE_TOLERANCE)
        return qr
    }

    private fun dependentRows(qr: PivotedQr): List<Int> {
        val independent = qr.perm.take(qr.rank).toSet()
        return (0 until m).filter { it !in independent && !rowIsZero[it] }
    }

    /** A constraint is dependent only when every one of its rows is. */
    private fun dependentEquations(qr: PivotedQr): List<Int> {
        val byEquation = dependentRows(qr).groupBy { rowEquation[it] }
        return byEquation.filter { (k, rows) -> rows.size == problem.equations[equations[k]].rowCount }.keys.sorted()
    }

    fun dependentConstraintIds(qr: PivotedQr): List<String> =
        dependentEquations(qr).map { problem.equations[equations[it]].id }

    fun conflictIds(qr: PivotedQr): List<String> {
        val ids = LinkedHashSet<Int>()
        for (k in dependentEquations(qr)) {
            ids += k
            val firstRow = (0 until m).first { rowEquation[it] == k }
            for (partner in partnerRows(qr, firstRow)) ids += rowEquation[partner]
        }
        return ids.sorted().map { problem.equations[equations[it]].id }
    }

    /** Pivot rows that carry weight when dependent row [row] is expressed through the independent ones. */
    private fun partnerRows(qr: PivotedQr, row: Int): List<Int> {
        val k = qr.rank
        val pos = qr.perm.indexOf(row)
        if (pos < 0 || k == 0) return emptyList()
        val y = DoubleArray(k)
        for (i in k - 1 downTo 0) {
            var s = qr.r[i, pos]
            for (c in i + 1 until k) s -= qr.r[i, c] * y[c]
            y[i] = s / qr.r[i, i]
        }
        return (0 until k).filter { abs(y[it]) > PARTNER_WEIGHT }.map { qr.perm[it] }
    }
}
