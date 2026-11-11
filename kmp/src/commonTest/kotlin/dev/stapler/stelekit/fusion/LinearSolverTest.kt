package dev.stapler.stelekit.fusion

import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every number here is invented. */
class LinearSolverTest {
    private fun solve(vararg constraints: Constraint): Solution =
        LinearSolver.solve(constraints.toList()).fold({ error("solver failed: $it") }, { it })

    private val zones = listOf("z1", "z2", "z3", "z4", "z5")
    private val zoneValues = listOf(Rational(20), Rational(121, 4), Rational(51, 2), Rational(121, 4), Rational(20))

    private fun zoneFixes(): List<Constraint> = zones.mapIndexed { i, z -> Fixed(i + 1, z, zoneValues[i]) }

    private fun determined(solution: Solution, dim: String) =
        assertIs<DimensionResult.Determined>(solution.dimensions.getValue(dim))

    @Test
    fun sumOfFixedParts_should_DeriveTheTotalAndHold() {
        val solution = LinearSolver.solve(listOf(Sum(0, "opening", zones)) + zoneFixes()).fold({ error("$it") }, { it })
        val opening = determined(solution, "opening")
        assertEquals(Rational(126), opening.value)
        assertTrue(opening.derived)
        assertEquals(ConstraintVerdict.HOLDS, solution.verdicts.getValue(0))
        assertEquals(emptyList(), solution.inconsistencies)
    }

    @Test
    fun statedTotalThatMatches_should_HoldWithoutConflict() {
        val solution = LinearSolver.solve(listOf(Sum(0, "opening", zones), Fixed(6, "opening", Rational(126))) + zoneFixes())
            .fold({ error("$it") }, { it })
        assertEquals(emptyList(), solution.inconsistencies)
        assertEquals(setOf(ConstraintVerdict.HOLDS), solution.verdicts.values.toSet())
        assertEquals(false, determined(solution, "opening").derived)
    }

    @Test
    fun seededInconsistency_should_BlameExactlyTheContributors() {
        val unrelated = listOf(Equal(7, "p", "q"), Fixed(8, "p", Rational(3)), Offset(9, "r", "p", Rational(1)))
        val constraints = listOf(Sum(0, "opening", zones), Fixed(6, "opening", Rational(127))) + zoneFixes() + unrelated
        val solution = LinearSolver.solve(constraints).fold({ error("$it") }, { it })
        val conflict = solution.inconsistencies.single()
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), conflict.provenance.ids())
        assertEquals(Rational(1), conflict.residual.abs().fold({ error("$it") }, { it }))
        assertEquals(ConstraintVerdict.VIOLATED, solution.verdicts.getValue(6))
        assertEquals(ConstraintVerdict.HOLDS, solution.verdicts.getValue(7))
        assertEquals(ConstraintVerdict.HOLDS, solution.verdicts.getValue(9))
    }

    @Test
    fun singleUnknownInASum_should_BeDerivable() {
        val solution = solve(
            Sum(0, "opening", listOf("left", "middle", "right")),
            Fixed(1, "opening", Rational(100)),
            Fixed(2, "left", Rational(30)),
            Fixed(3, "right", Rational(25, 2)),
        )
        val middle = determined(solution, "middle")
        assertEquals(Rational(115, 2), middle.value)
        assertTrue(middle.derived)
        assertTrue(listOf(0, 1, 2, 3).all { it in middle.provenance })
    }

    @Test
    fun twoUnknownsInASum_should_StayFree() {
        val solution = solve(Sum(0, "opening", listOf("a", "b")), Fixed(1, "opening", Rational(10)))
        assertEquals(DimensionResult.Free, solution.dimensions.getValue("a"))
        assertEquals(ConstraintVerdict.UNDETERMINED, solution.verdicts.getValue(0))
        assertNull(solution.value("b"))
    }

    @Test
    fun equalAndOffset_should_PropagateThroughTheUnionFind() {
        val solution = solve(
            Offset(0, "a", "b", Rational(3)),
            Equal(1, "b", "c"),
            Fixed(2, "c", Rational(10)),
        )
        assertEquals(Rational(13), solution.value("a"))
        assertEquals(Rational(10), solution.value("b"))
    }

    @Test
    fun cyclicOffsetMismatch_should_BlameTheCycleOnly() {
        val solution = solve(
            Offset(0, "a", "b", Rational(3)),
            Equal(1, "b", "c"),
            Offset(2, "a", "c", Rational(4)),
            Equal(3, "x", "y"),
        )
        assertEquals(listOf(0, 1, 2), solution.inconsistencies.single().provenance.ids())
    }

    @Test
    fun nonRepresentableResult_should_ReportExactValueAndRemainder() {
        val solution = solve(
            Sum(0, "opening", List(5) { "zone" }),
            Fixed(1, "opening", Rational(136)),
        )
        assertEquals(Rational(136, 5), solution.value("zone"))
        val entry = solution.notRepresentable.single()
        assertEquals("zone", entry.dimension)
        assertEquals(Rational(136, 5), entry.exact)
        assertEquals(Rational(1, 80), entry.remainder)
    }

    @Test
    fun invalidIds_should_BeRejected() {
        assertEquals(
            SolverError.DuplicateId(1),
            LinearSolver.solve(listOf(Fixed(1, "a", Rational.ONE), Fixed(1, "b", Rational.ONE))).leftOrNull(),
        )
        assertEquals(SolverError.NegativeId(-2), LinearSolver.solve(listOf(Fixed(-2, "a", Rational.ONE))).leftOrNull())
    }

    @Test
    fun overflow_should_BeReportedNotWrapped() {
        val big = Rational(Long.MAX_VALUE)
        val result = LinearSolver.solve(listOf(Sum(0, "t", listOf("a", "b")), Fixed(1, "a", big), Fixed(2, "b", big)))
        assertIs<SolverError.Arithmetic>(result.leftOrNull())
    }

    @Test
    fun largeSparseSystem_should_StayWithinThePivotOperationBound() {
        val groups = 100
        val constraints = ArrayList<Constraint>()
        var id = 0
        repeat(groups) { g ->
            val parts = List(4) { "g$g.p$it" }
            constraints += Sum(id++, "g$g.total", parts)
            constraints += Fixed(id++, "g$g.total", Rational(40))
            parts.take(3).forEach { constraints += Fixed(id++, it, Rational(10)) }
        }
        // 500 dimensions in a chain of equalities hanging off the first group's total.
        repeat(100) { constraints += Equal(id++, "chain$it", if (it == 0) "g0.total" else "chain${it - 1}") }
        val solution = LinearSolver.solve(constraints).fold({ error("$it") }, { it })
        assertEquals(emptyList(), solution.inconsistencies)
        assertEquals(Rational(40), solution.value("chain99"))
        assertEquals(Rational(10), solution.value("g99.p3"))
        assertTrue(solution.dimensions.size >= 500, "dims=${solution.dimensions.size}")
        // Each sum and fixed row is combined with at most a few pivots; the bound is linear in rows.
        assertTrue(solution.stats.pivotOps <= 4L * constraints.size, "ops=${solution.stats.pivotOps} rows=${constraints.size}")
    }
}
