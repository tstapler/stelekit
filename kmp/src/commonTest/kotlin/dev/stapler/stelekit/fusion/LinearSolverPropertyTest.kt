package dev.stapler.stelekit.fusion

import dev.stapler.stelekit.units.Rational
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinearSolverPropertyTest {
    private class Scenario(val hidden: Map<String, Rational>, val constraints: List<Constraint>) {
        val nextId: Int get() = (constraints.maxOfOrNull { it.id } ?: -1) + 1
    }

    private operator fun Rational.plus(other: Rational): Rational = add(other).fold({ error("$it") }, { it })

    private operator fun Rational.minus(other: Rational): Rational = subtract(other).fold({ error("$it") }, { it })

    /** A hidden solution first, then equations true of it, in random order: a consistent system by construction. */
    private val scenarios: Arb<Scenario> = arbitrary { rs ->
        val random = rs.random
        val hidden = LinkedHashMap<String, Rational>()
        val constraints = ArrayList<Constraint>()
        var id = 0
        repeat(random.nextInt(3, 8)) { i ->
            hidden["b$i"] = Rational(random.nextLong(-3_200L, 3_200L), 16L)
            constraints += Fixed(id++, "b$i", hidden.getValue("b$i"))
        }
        repeat(random.nextInt(1, 5)) { j ->
            val pool = hidden.keys.toList().shuffled(random)
            val parts = pool.take(random.nextInt(2, minOf(4, pool.size) + 1))
            hidden["t$j"] = parts.fold(Rational.ZERO) { acc, p -> acc + hidden.getValue(p) }
            constraints += Sum(id++, "t$j", parts)
        }
        repeat(random.nextInt(0, 3)) {
            val (a, b) = hidden.keys.toList().shuffled(random).take(2)
            constraints += Offset(id++, a, b, hidden.getValue(a) - hidden.getValue(b))
        }
        Scenario(hidden, constraints.shuffled(random))
    }

    private fun solve(constraints: List<Constraint>): Solution =
        LinearSolver.solve(constraints).fold({ error("$it") }, { it })

    @Test
    fun consistentSystems_should_RecoverTheHiddenSolution() = runTest {
        checkAll(scenarios) { s ->
            val solution = solve(s.constraints)
            assertEquals(emptyList(), solution.inconsistencies)
            assertEquals(emptyList(), solution.notRepresentable)
            for ((dim, expected) in s.hidden) {
                assertEquals(expected, assertIs<DimensionResult.Determined>(solution.dimensions.getValue(dim)).value, dim)
            }
            assertEquals(setOf(ConstraintVerdict.HOLDS), solution.verdicts.values.toSet())
        }
    }

    @Test
    fun underdeterminedSystems_should_NeverReportAWrongValue() = runTest {
        checkAll(scenarios) { s ->
            val kept = s.constraints.filterIndexed { index, c -> c !is Fixed || index % 2 == 0 }
            val solution = solve(kept)
            assertEquals(emptyList(), solution.inconsistencies)
            solution.dimensions.forEach { (dim, result) ->
                if (result is DimensionResult.Determined) assertEquals(s.hidden.getValue(dim), result.value, dim)
            }
        }
    }

    // Pitfall 6: blame must contain the perturbed constraint and nothing that is not needed.
    @Test
    fun perturbedEquation_should_BeBlamedWithAnIrredundantSet() = runTest {
        checkAll(scenarios, Arb.dimIndex()) { s, pick ->
            val dim = s.hidden.keys.toList()[pick % s.hidden.size]
            val perturbed = Fixed(s.nextId, dim, s.hidden.getValue(dim) + Rational(1, 16))
            val all = s.constraints + perturbed
            val conflict = solve(all).inconsistencies.first()
            assertTrue(perturbed.id in conflict.provenance)
            for (id in conflict.provenance.ids()) {
                val without = all.filter { it.id in conflict.provenance && it.id != id }
                assertEquals(emptyList(), solve(without).inconsistencies, "id $id is not needed for the conflict")
            }
            val within = all.filter { it.id in conflict.provenance }
            assertTrue(solve(within).inconsistencies.isNotEmpty(), "blame set alone must be inconsistent: blame=${conflict.provenance} all=$all")
        }
    }

    private fun Arb.Companion.dimIndex(): Arb<Int> = arbitrary { it.random.nextInt(0, 1_000) }
}
