package dev.stapler.stelekit.fusion

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import dev.stapler.stelekit.units.ArithmeticError
import dev.stapler.stelekit.units.Rational

/** A row `0 = residual` (`residual != 0`) with the union of constraints that produced it. */
internal class RawConflict(val provenance: Provenance, val residual: Rational)

/** `sum(coeffs[v] * v) = rhs` over union-find roots; [provenance] is the union of contributing constraints. */
private class Row(val coeffs: Map<Int, Rational>, val rhs: Rational, val provenance: Provenance)

private class Located(val root: Int, val offset: Rational, val path: Provenance)

/**
 * One pass of ADR-003 phase A over a fixed constraint list: weighted union-find for `Equal` and
 * `Offset`, then incremental sparse Gauss-Jordan for `Sum` and `Fixed`. Every pivot row is
 * kept reduced (it mentions no other pivot), so one pass of substitution fully reduces a row.
 */
internal class Elimination(private val constraints: List<Constraint>) {
    val dimensions: List<String> = constraints.flatMap { it.dimensions }.distinct()
    val conflicts = ArrayList<RawConflict>()

    /** Row combinations performed (the R-TIME work counter). */
    var pivotOps = 0L
        private set

    private val index: Map<String, Int> = dimensions.withIndex().associate { (i, d) -> d to i }
    private val parent = IntArray(dimensions.size) { it }
    private val size = IntArray(dimensions.size) { 1 }
    private val offset = Array(dimensions.size) { Rational.ZERO }
    // What the child's offset to its parent depends on: its own edge plus the paths used to compute it.
    private val edge = Array(dimensions.size) { Provenance.EMPTY }
    private val pivots = HashMap<Int, Row>()

    fun run(): Either<SolverError, Unit> = either {
        for (c in constraints) {
            when (c) {
                is Equal -> link(c.id, c.a, c.b, Rational.ZERO)
                is Offset -> link(c.id, c.a, c.b, c.k)
                else -> Unit
            }
        }
        for (c in constraints) {
            when (c) {
                is Sum -> insert(sumRow(c))
                is Fixed -> insert(fixedRow(c))
                else -> Unit
            }
        }
    }

    /** The determined value of [dimension] and a superset of the constraints it depends on, or null if free. */
    fun valueOf(dimension: String): Pair<Rational, Provenance>? {
        val at = locate(dimension).fold({ return null }, { it })
        val row = pivots[at.root]?.takeIf { it.coeffs.size == 1 } ?: return null
        val value = row.rhs.add(at.offset).fold({ return null }, { it })
        return value to row.provenance.union(at.path)
    }

    private fun <R> Raise<SolverError>.ok(result: Either<ArithmeticError, R>): R =
        result.fold({ raise(SolverError.Arithmetic(it)) }, { it })

    private fun locate(dimension: String): Either<SolverError, Located> = either {
        var current = index.getValue(dimension)
        var total = Rational.ZERO
        var path = Provenance.EMPTY
        while (parent[current] != current) {
            total = ok(total.add(offset[current]))
            path = path.union(edge[current])
            current = parent[current]
        }
        Located(current, total, path)
    }

    private fun Raise<SolverError>.link(id: Int, a: String, b: String, k: Rational) {
        val la = locate(a).bind()
        val lb = locate(b).bind()
        if (la.root == lb.root) {
            // Same component: the stored relation is oa - ob. Provenance is a superset (shared edges stay in).
            val residual = ok(ok(la.offset.subtract(lb.offset)).subtract(k))
            if (!residual.isZero) conflicts.add(RawConflict(la.path.union(lb.path).with(id), residual))
            return
        }
        // val(ra) = val(rb) + delta
        val delta = ok(ok(k.add(lb.offset)).subtract(la.offset))
        val basis = la.path.union(lb.path).with(id)
        if (size[la.root] <= size[lb.root]) {
            attach(la.root, lb.root, delta, basis)
        } else {
            attach(lb.root, la.root, ok(delta.negate()), basis)
        }
    }

    private fun attach(child: Int, root: Int, delta: Rational, basis: Provenance) {
        parent[child] = root
        offset[child] = delta
        edge[child] = basis
        size[root] += size[child]
    }

    private fun Raise<SolverError>.sumRow(c: Sum): Row {
        val coeffs = HashMap<Int, Rational>()
        var constant = Rational.ZERO
        var provenance = Provenance.EMPTY.with(c.id)
        val signed = listOf(c.total to Rational.ONE) + c.parts.map { it to ok(Rational.ONE.negate()) }
        for ((dim, coefficient) in signed) {
            val at = locate(dim).bind()
            coeffs[at.root] = ok((coeffs[at.root] ?: Rational.ZERO).add(coefficient))
            constant = ok(constant.add(ok(coefficient.multiply(at.offset))))
            provenance = provenance.union(at.path)
        }
        return Row(coeffs.filterValues { !it.isZero }, ok(constant.negate()), provenance)
    }

    private fun Raise<SolverError>.fixedRow(c: Fixed): Row {
        val at = locate(c.dimension).bind()
        return Row(mapOf(at.root to Rational.ONE), ok(c.value.subtract(at.offset)), at.path.with(c.id))
    }

    private fun Raise<SolverError>.combine(target: Row, pivot: Row, factor: Rational): Row {
        pivotOps++
        val coeffs = HashMap(target.coeffs)
        for ((v, c) in pivot.coeffs) {
            val next = ok((coeffs[v] ?: Rational.ZERO).subtract(ok(factor.multiply(c))))
            if (next.isZero) coeffs.remove(v) else coeffs[v] = next
        }
        return Row(coeffs, ok(target.rhs.subtract(ok(factor.multiply(pivot.rhs)))), target.provenance.union(pivot.provenance))
    }

    private fun Raise<SolverError>.insert(incoming: Row) {
        var row = incoming
        for (v in incoming.coeffs.keys.toList()) {
            val pivot = pivots[v] ?: continue
            val factor = row.coeffs[v] ?: continue
            row = combine(row, pivot, factor)
        }
        if (row.coeffs.isEmpty()) {
            if (!row.rhs.isZero) conflicts.add(RawConflict(row.provenance, row.rhs))
            return
        }
        val pivotVar = row.coeffs.keys.min()
        val lead = row.coeffs.getValue(pivotVar)
        if (lead != Rational.ONE) {
            pivotOps++
            val inverse = ok(lead.inverse())
            row = Row(
                row.coeffs.mapValues { ok(it.value.multiply(inverse)) },
                ok(row.rhs.multiply(inverse)),
                row.provenance,
            )
        }
        for ((variable, other) in pivots.entries.toList()) {
            val factor = other.coeffs[pivotVar] ?: continue
            pivots[variable] = combine(other, row, factor)
        }
        pivots[pivotVar] = row
    }
}
