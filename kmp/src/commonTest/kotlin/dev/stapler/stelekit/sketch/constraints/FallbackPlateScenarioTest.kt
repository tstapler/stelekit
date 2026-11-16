// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.sketch.constraints.constraints.Coincident
import dev.stapler.stelekit.sketch.constraints.constraints.ConstraintEquation
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceDimension
import dev.stapler.stelekit.sketch.constraints.constraints.EqualLength
import dev.stapler.stelekit.sketch.constraints.constraints.FixedPoint
import dev.stapler.stelekit.sketch.constraints.constraints.Horizontal
import dev.stapler.stelekit.sketch.constraints.constraints.Vertical
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Requirements metric 3 on the geometric-only fallback set (Coincident, Horizontal, Vertical, Equal, Distance): the
 * "fallback plate" of Story 4.1.3, 6 x 4 in plus a square pocket. One inch is 16 scene units (ADR-002 scale convention).
 */
class FallbackPlateScenarioTest {
    private val inch = 16.0
    private val width = 6 * inch
    private val height = 4 * inch
    private val pocketSide = 2 * inch
    private val tolerance = RESIDUAL_ACCEPTANCE_BAR

    /** A closed four-line loop: line i runs from variable `base + 4i` to `base + 4i + 2`; returns its constraints. */
    private fun loop(prefix: String, base: Int): List<ConstraintEquation> {
        val start = IntArray(4) { base + 4 * it }
        val end = IntArray(4) { base + 4 * it + 2 }
        val eqs = ArrayList<ConstraintEquation>()
        for (i in 0 until 4) eqs += Coincident("$prefix-co$i", end[i], start[(i + 1) % 4])
        eqs += Horizontal("$prefix-h0", start[0], end[0]); eqs += Horizontal("$prefix-h2", start[2], end[2])
        eqs += Vertical("$prefix-v1", start[1], end[1]); eqs += Vertical("$prefix-v3", start[3], end[3])
        return eqs
    }

    private fun loopTruth(x0: Double, y0: Double, w: Double, h: Double): DoubleArray = doubleArrayOf(
        x0, y0, x0 + w, y0,
        x0 + w, y0, x0 + w, y0 + h,
        x0 + w, y0 + h, x0, y0 + h,
        x0, y0 + h, x0, y0,
    )

    private class Scenario(val problem: ConstraintProblem, val start: DoubleArray, val width: DistanceDimension)

    private fun scenario(extra: List<ConstraintEquation> = emptyList(), seed: Int = 7): Scenario {
        val truth = loopTruth(0.0, 0.0, width, height) + loopTruth(2 * inch, inch, pocketSide, pocketSide)
        val widthDim = DistanceDimension("plate-w", 0, 2, width)
        val eqs = ArrayList<ConstraintEquation>()
        eqs += loop("plate", 0); eqs += widthDim; eqs += DistanceDimension("plate-h", 4, 6, height)
        eqs += loop("pocket", 16)
        eqs += EqualLength("pocket-eq", 16, 18, 20, 22); eqs += DistanceDimension("pocket-d", 16, 18, pocketSide)
        eqs += extra
        val rnd = Random(seed)
        val start = DoubleArray(truth.size) { truth[it] + rnd.nextDouble(-3.0, 3.0) }
        return Scenario(ConstraintProblem(eqs, truth.size), start, widthDim)
    }

    private fun plateWidth(x: DoubleArray) = hypot(x[2] - x[0], x[3] - x[1])
    private fun plateHeight(x: DoubleArray) = hypot(x[6] - x[4], x[7] - x[5])

    @Test
    fun plate_should_HaveAnalyticDofFour_AndMeetTheResidualBar() {
        val s = scenario()
        val res = Solver().solve(s.problem, s.start)
        assertTrue(res is SolveResult.Solved, "got ${res::class.simpleName}")
        assertEquals(4, res.dof)
        assertTrue(SolverCorpus.maxResidual(s.problem, res.x) < tolerance)
        assertTrue(abs(plateWidth(res.x) - width) < tolerance && abs(plateHeight(res.x) - height) < tolerance)
    }

    @Test
    fun draggingACorner_should_MoveTheWholePlate_WithSizeIntact() {
        val s = scenario()
        val solved = Solver().solve(s.problem, s.start) as SolveResult.Solved
        val target = FixedPoint("drag", 0, solved.x[0] + 10.0, solved.x[1] + 7.0)
        val dragged = scenario(listOf(target))
        val res = Solver().solve(dragged.problem, solved.x)
        assertTrue(res is SolveResult.Solved, "got ${res::class.simpleName}")
        for (i in 0 until 16 step 2) {
            assertTrue(abs(res.x[i] - solved.x[i] - 10.0) < tolerance && abs(res.x[i + 1] - solved.x[i + 1] - 7.0) < tolerance, "plate point $i did not translate")
        }
        assertTrue(abs(plateWidth(res.x) - width) < tolerance && abs(plateHeight(res.x) - height) < tolerance)
        for (i in 16 until 32) assertEquals(solved.x[i], res.x[i], "the pocket is a separate component and stays put")
    }

    @Test
    fun secondDistanceOnTheBottomEdge_should_BeAConflict_NamingBothDistances() {
        val s = scenario()
        val solved = Solver().solve(s.problem, s.start) as SolveResult.Solved
        val clash = scenario(listOf(DistanceDimension("plate-w2", 0, 2, 5 * inch)))
        val res = Solver().solve(clash.problem, solved.x)
        assertTrue(res is SolveResult.Conflict, "got ${res::class.simpleName}")
        assertEquals(listOf("plate-w", "plate-w2"), res.ids.sorted())
        assertTrue(res.x.contentEquals(solved.x), "a rejected add leaves the geometry unchanged")
    }

    @Test
    fun editingTheWidthToSevenInches_should_WidenThePlateByOneInch_AndRestoreOnRevert() {
        val s = scenario()
        val solver = Solver()
        val solved = solver.solve(s.problem, s.start) as SolveResult.Solved
        s.width.value = 7 * inch
        val wider = solver.solve(s.problem, solved.x)
        assertTrue(wider is SolveResult.Solved, "got ${wider::class.simpleName}")
        assertTrue(abs(plateWidth(wider.x) - plateWidth(solved.x) - inch) < tolerance, "width grew by ${plateWidth(wider.x) - plateWidth(solved.x)}")
        assertTrue(abs(plateHeight(wider.x) - height) < tolerance)
        s.width.value = width
        val back = solver.solve(s.problem, wider.x)
        assertTrue(back is SolveResult.Solved && abs(plateWidth(back.x) - width) < tolerance, "reverting the edit restores 6 in")
    }
}
