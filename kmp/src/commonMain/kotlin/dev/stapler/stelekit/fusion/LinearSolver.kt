package dev.stapler.stelekit.fusion

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import dev.stapler.stelekit.units.ArithmeticError
import dev.stapler.stelekit.units.InchesError
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.units.toInches

sealed interface SolverError {
    data class DuplicateId(val id: Int) : SolverError

    data class NegativeId(val id: Int) : SolverError

    data class Arithmetic(val error: ArithmeticError) : SolverError
}

sealed interface DimensionResult {
    /**
     * The constraints force one value. [derived] is true when no [Fixed] names this dimension, so
     * the value was worked out rather than stated. [provenance] is a superset of the constraints
     * the value depends on.
     */
    data class Determined(val value: Rational, val provenance: Provenance, val derived: Boolean) : DimensionResult

    /** The constraints leave this dimension free (under-constrained). */
    data object Free : DimensionResult
}

enum class ConstraintVerdict {
    /** Every dimension is determined and the constraint is in no conflict. */
    HOLDS,

    /** The constraint belongs to a minimal inconsistent set. */
    VIOLATED,

    /** Some dimension it mentions is still free. */
    UNDETERMINED,
}

/** `0 = residual` with `residual != 0`: [provenance] is an irredundant set of constraints that cannot all hold. */
data class Inconsistency(val provenance: Provenance, val residual: Rational)

/** A determined value that is not a whole number of sixteenths; reported exactly, never rounded. */
data class NotRepresentable(val dimension: String, val exact: Rational, val remainder: Rational)

/** Work counters, for deterministic scale assertions (R-TIME). */
data class SolveStats(val pivotOps: Long, val blameOps: Long)

data class Solution(
    val dimensions: Map<String, DimensionResult>,
    val verdicts: Map<Int, ConstraintVerdict>,
    val inconsistencies: List<Inconsistency>,
    val notRepresentable: List<NotRepresentable>,
    val stats: SolveStats,
) {
    fun value(dimension: String): Rational? = (dimensions[dimension] as? DimensionResult.Determined)?.value
}

/**
 * Phase A of ADR-003: exact solving of linear equalities over [Rational].
 *
 * `Equal`/`Offset` are folded first by a weighted union-find (each dimension becomes a root plus
 * a constant), then `Sum` and `Fixed` rows go through sparse Gauss-Jordan elimination over the
 * roots. Every row carries a [Provenance] union; an inconsistent row's union is then shrunk to an
 * irredundant set by dropping each id whose removal leaves the rest inconsistent.
 */
object LinearSolver {

    fun solve(constraints: List<Constraint>): Either<SolverError, Solution> = either {
        validate(constraints)
        val main = Elimination(constraints)
        main.run().bind()
        val blame = BlameCounter()
        val inconsistencies = main.conflicts
            .map { Inconsistency(minimise(constraints, it.provenance, blame), it.residual) }
            .distinctBy { it.provenance }
        build(constraints, main, inconsistencies, blame.ops)
    }

    private fun Raise<SolverError>.validate(constraints: List<Constraint>) {
        val seen = HashSet<Int>()
        for (c in constraints) {
            if (c.id < 0) raise(SolverError.NegativeId(c.id))
            if (!seen.add(c.id)) raise(SolverError.DuplicateId(c.id))
        }
    }

    private class BlameCounter(var ops: Long = 0L)

    private fun Raise<SolverError>.minimise(all: List<Constraint>, superset: Provenance, counter: BlameCounter): Provenance {
        var kept = superset
        for (id in superset.ids()) {
            val trial = kept.without(id)
            val probe = Elimination(all.filter { it.id in trial })
            probe.run().bind()
            counter.ops += probe.pivotOps
            if (probe.conflicts.isNotEmpty()) kept = trial
        }
        return kept
    }

    private fun Raise<SolverError>.build(
        constraints: List<Constraint>,
        elimination: Elimination,
        inconsistencies: List<Inconsistency>,
        blameOps: Long,
    ): Solution {
        val fixedDims = constraints.filterIsInstance<Fixed>().map { it.dimension }.toSet()
        val dimensions = LinkedHashMap<String, DimensionResult>()
        val notRepresentable = ArrayList<NotRepresentable>()
        for (dim in elimination.dimensions) {
            val result = elimination.valueOf(dim)
            dimensions[dim] = result?.let { (value, provenance) ->
                DimensionResult.Determined(value, provenance, derived = dim !in fixedDims)
            } ?: DimensionResult.Free
            if (result != null) {
                val inches = result.first.toInches()
                if (inches is Either.Left) {
                    val error = inches.value
                    if (error is InchesError.NotRepresentable) notRepresentable.add(NotRepresentable(dim, error.exact, error.remainder))
                    if (error is InchesError.Failed) raise(SolverError.Arithmetic(error.error))
                }
            }
        }
        val violated = inconsistencies.fold(Provenance.EMPTY) { acc, i -> acc.union(i.provenance) }
        val verdicts = constraints.associate { c ->
            c.id to when {
                c.id in violated -> ConstraintVerdict.VIOLATED
                c.dimensions.all { dimensions[it] is DimensionResult.Determined } -> ConstraintVerdict.HOLDS
                else -> ConstraintVerdict.UNDETERMINED
            }
        }
        return Solution(
            dimensions, verdicts, inconsistencies, notRepresentable,
            SolveStats(elimination.pivotOps, blameOps),
        )
    }
}
