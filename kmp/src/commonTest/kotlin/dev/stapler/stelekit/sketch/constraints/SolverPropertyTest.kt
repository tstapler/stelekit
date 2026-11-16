// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.sketch.constraints.constraints.AngleDimension
import dev.stapler.stelekit.sketch.constraints.constraints.AnglePolicy
import dev.stapler.stelekit.sketch.constraints.constraints.Coincident
import dev.stapler.stelekit.sketch.constraints.constraints.ConstraintEquation
import dev.stapler.stelekit.sketch.constraints.constraints.DiameterDimension
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceDimension
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceX
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceY
import dev.stapler.stelekit.sketch.constraints.constraints.EqualLength
import dev.stapler.stelekit.sketch.constraints.constraints.EqualRadius
import dev.stapler.stelekit.sketch.constraints.constraints.FixedPoint
import dev.stapler.stelekit.sketch.constraints.constraints.FixedScalar
import dev.stapler.stelekit.sketch.constraints.constraints.Horizontal
import dev.stapler.stelekit.sketch.constraints.constraints.Parallel
import dev.stapler.stelekit.sketch.constraints.constraints.Perpendicular
import dev.stapler.stelekit.sketch.constraints.constraints.PointOnCircle
import dev.stapler.stelekit.sketch.constraints.constraints.PointOnLine
import dev.stapler.stelekit.sketch.constraints.constraints.RadiusDimension
import dev.stapler.stelekit.sketch.constraints.constraints.RadiusRef
import dev.stapler.stelekit.sketch.constraints.constraints.Symmetric
import dev.stapler.stelekit.sketch.constraints.constraints.TangentCircleCircle
import dev.stapler.stelekit.sketch.constraints.constraints.TangentLineCircle
import dev.stapler.stelekit.sketch.constraints.constraints.Vertical
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 4.1.3 acceptance (a, b): satisfiable sketches solve to the residual bar or report `NoConvergence` with the
 * last good parameters, the Story 0.1.2 cases classify, DOF equals the analytic count, a forced small budget falls
 * back to the next algorithm, the wall-time cap returns instead of hanging and degenerate geometry never throws.
 * Layout, as in `JacobianFiniteDifferenceTest`: six points at variable indices 0, 2, .. 10, scalar radii at 12 and 13.
 */
class SolverPropertyTest {
    private val p = IntArray(6) { it * 2 }
    private val r1 = 12
    private val r2 = 13
    private val variableCount = 14

    private val iterationsPerKind = 40
    private val perturbation = 0.1
    private val minPointSeparation = 0.5
    private val residualPerScale = 1e-9

    /** A constraint plus the edit that makes a random configuration satisfy it (value-free kinds need one). */
    private class Kind(val name: String, val satisfy: (DoubleArray, Random) -> Unit = { _, _ -> }, val build: (DoubleArray) -> ConstraintEquation)

    private fun lerpTo(x: DoubleArray, dst: Int, from: Int, dirX: Double, dirY: Double, t: Double) {
        x[dst] = x[from] + t * dirX; x[dst + 1] = x[from + 1] + t * dirY
    }

    private val kinds: List<Kind> = listOf(
        Kind("Coincident", { x, _ -> x[p[1]] = x[p[0]]; x[p[1] + 1] = x[p[0] + 1] }) { Coincident("c", p[0], p[1]) },
        Kind("FixedPoint") { x -> FixedPoint("c", p[0], x[p[0]], x[p[0] + 1]) },
        Kind("FixedScalar") { x -> FixedScalar("c", r1, x[r1]) },
        Kind("PointOnLine", { x, rnd ->
            val t = rnd.nextDouble(0.2, 0.8)
            lerpTo(x, p[2], p[0], x[p[1]] - x[p[0]], x[p[1] + 1] - x[p[0] + 1], t)
        }) { PointOnLine("c", p[2], p[0], p[1]) },
        Kind("PointOnCircle", { x, _ -> x[r1] = hypot(x[p[2]] - x[p[0]], x[p[2] + 1] - x[p[0] + 1]) }) { PointOnCircle("c", p[2], p[0], RadiusRef.scalar(r1)) },
        Kind("Horizontal", { x, _ -> x[p[1] + 1] = x[p[0] + 1] }) { Horizontal("c", p[0], p[1]) },
        Kind("Vertical", { x, _ -> x[p[1]] = x[p[0]] }) { Vertical("c", p[0], p[1]) },
        Kind("Parallel", { x, rnd ->
            lerpTo(x, p[3], p[2], x[p[1]] - x[p[0]], x[p[1] + 1] - x[p[0] + 1], rnd.nextDouble(0.5, 2.0))
        }) { Parallel("c", p[0], p[1], p[2], p[3]) },
        Kind("Perpendicular", { x, rnd ->
            lerpTo(x, p[3], p[2], -(x[p[1] + 1] - x[p[0] + 1]), x[p[1]] - x[p[0]], rnd.nextDouble(0.5, 2.0))
        }) { Perpendicular("c", p[0], p[1], p[2], p[3]) },
        Kind("EqualLength", { x, _ ->
            val want = hypot(x[p[1]] - x[p[0]], x[p[1] + 1] - x[p[0] + 1])
            val dx = x[p[3]] - x[p[2]]; val dy = x[p[3] + 1] - x[p[2] + 1]
            val have = hypot(dx, dy)
            x[p[3]] = x[p[2]] + dx / have * want; x[p[3] + 1] = x[p[2] + 1] + dy / have * want
        }) { EqualLength("c", p[0], p[1], p[2], p[3]) },
        Kind("EqualRadius", { x, _ -> x[r2] = x[r1] }) { EqualRadius("c", RadiusRef.scalar(r1), RadiusRef.scalar(r2)) },
        Kind("Symmetric", { x, _ ->
            val ax = x[p[2]]; val ay = x[p[2] + 1]
            val dx = x[p[3]] - ax; val dy = x[p[3] + 1] - ay
            val len2 = dx * dx + dy * dy
            val t = ((x[p[0]] - ax) * dx + (x[p[0] + 1] - ay) * dy) / len2
            val fx = ax + t * dx; val fy = ay + t * dy
            x[p[1]] = 2 * fx - x[p[0]]; x[p[1] + 1] = 2 * fy - x[p[0] + 1]
        }) { Symmetric("c", p[0], p[1], p[2], p[3]) },
        Kind("TangentLineCircle", { x, _ ->
            val dx = x[p[1]] - x[p[0]]; val dy = x[p[1] + 1] - x[p[0] + 1]
            val signed = (dx * (x[p[2] + 1] - x[p[0] + 1]) - dy * (x[p[2]] - x[p[0]])) / hypot(dx, dy)
            x[r1] = abs(signed)
        }) { x -> TangentLineCircle("c", p[0], p[1], p[2], RadiusRef.scalar(r1), TangentLineCircle.sideOf(x, p[0], p[1], p[2])) },
        Kind("TangentCircleCircle", { x, rnd ->
            val d = hypot(x[p[1]] - x[p[0]], x[p[1] + 1] - x[p[0] + 1])
            val share = rnd.nextDouble(0.3, 0.7)
            x[r1] = d * share; x[r2] = d * (1 - share)
        }) { x -> TangentCircleCircle.infer("c", x, p[0], RadiusRef.scalar(r1), p[1], RadiusRef.scalar(r2)) },
        Kind("Distance") { x -> DistanceDimension("c", p[0], p[1], hypot(x[p[1]] - x[p[0]], x[p[1] + 1] - x[p[0] + 1])) },
        Kind("DistanceX") { x -> DistanceX("c", p[0], p[1], x[p[1]] - x[p[0]]) },
        Kind("DistanceY") { x -> DistanceY("c", p[0], p[1], x[p[1] + 1] - x[p[0] + 1]) },
        Kind("Radius") { x -> RadiusDimension("c", RadiusRef.scalar(r1), x[r1]) },
        Kind("RadiusArc") { x -> RadiusDimension("c", RadiusRef.arc(p[0], p[1]), hypot(x[p[1]] - x[p[0]], x[p[1] + 1] - x[p[0] + 1])) },
        Kind("Diameter") { x -> DiameterDimension("c", RadiusRef.scalar(r1), 2 * x[r1]) },
        Kind("AngleDirected") { x -> AngleDimension("c", p[0], p[1], p[2], p[3], measuredAngle(x, AnglePolicy.Directed), AnglePolicy.Directed) },
        Kind("AngleLines") { x -> AngleDimension("c", p[0], p[1], p[2], p[3], measuredAngle(x, AnglePolicy.Lines), AnglePolicy.Lines) },
    )

    private fun measuredAngle(x: DoubleArray, policy: AnglePolicy): Double =
        AngleDimension("probe", p[0], p[1], p[2], p[3], 0.0, policy, reference = true).measure(x)

    private fun randomConfig(rng: Random): DoubleArray {
        val x = DoubleArray(variableCount)
        var placed = 0
        while (placed < 6) {
            val cx = rng.nextDouble(-5.0, 5.0)
            val cy = rng.nextDouble(-5.0, 5.0)
            val clear = (0 until placed).all { k -> hypot(x[p[k]] - cx, x[p[k] + 1] - cy) > minPointSeparation }
            if (clear) { x[p[placed]] = cx; x[p[placed] + 1] = cy; placed++ }
        }
        x[r1] = rng.nextDouble(0.5, 3.0)
        x[r2] = rng.nextDouble(0.5, 3.0)
        return x
    }

    @Test
    fun everyKind_should_SolveToTheResidualBar_Or_ReturnNoConvergenceWithTheStartValues() = runTest {
        for (kind in kinds) {
            var solved = 0
            checkAll(iterationsPerKind, Arb.int()) { seed ->
                val rnd = Random(seed)
                val truth = randomConfig(rnd)
                kind.satisfy(truth, rnd)
                val equation = kind.build(truth)
                val start = DoubleArray(variableCount) { truth[it] + rnd.nextDouble(-perturbation, perturbation) }
                start[r1] = maxOf(start[r1], 0.1); start[r2] = maxOf(start[r2], 0.1)
                val problem = ConstraintProblem(listOf(equation), variableCount)
                val res = Solver().solve(problem, start)
                val scale = maxOf(1.0, start.maxOf { abs(it) })
                when (res) {
                    is SolveResult.Solved -> {
                        solved++
                        assertTrue(res.residualInf <= residualPerScale * scale, "${kind.name}: residual ${res.residualInf}")
                        assertTrue(SolverCorpus.maxResidual(problem, res.x) <= residualPerScale * scale, "${kind.name}: recomputed residual")
                    }
                    is SolveResult.NoConvergence -> assertTrue(res.x.contentEquals(start), "${kind.name}: NoConvergence must return the start values")
                    else -> assertTrue(false, "${kind.name}: unexpected ${res::class.simpleName}")
                }
            }
            assertTrue(solved >= iterationsPerKind * 9 / 10, "${kind.name}: only $solved of $iterationsPerKind solved")
        }
    }

    // ---- classification and degrees of freedom -------------------------------------------------------------

    private fun twoPoints(bx: Double, by: Double) = doubleArrayOf(0.0, 0.0, bx, by)

    @Test
    fun duplicateHorizontal_should_BeRedundant_AndDofMatchesTheAnalyticCount() {
        val eqs = listOf(Horizontal("c1", 0, 2), Vertical("c2", 0, 2), Horizontal("c3", 0, 2))
        val res = Solver().solve(ConstraintProblem(eqs, 4), twoPoints(3.0, 4.0))
        assertTrue(res is SolveResult.Redundant, "got ${res::class.simpleName}")
        assertEquals(listOf("c3"), res.ids)
        assertEquals(2, res.dof)
    }

    @Test
    fun conflictingDistances_should_BeConflict_NamingBothDistances() {
        val eqs = listOf(Horizontal("c1", 0, 2), DistanceDimension("c4", 0, 2, 10.0), DistanceDimension("c5", 0, 2, 12.0))
        val res = Solver().solve(ConstraintProblem(eqs, 4), twoPoints(8.0, 1.0))
        assertTrue(res is SolveResult.Conflict, "got ${res::class.simpleName}")
        assertEquals(listOf("c4", "c5"), res.ids)
        assertEquals(2, res.dof)
    }

    @Test
    fun inconsistentTriangle_should_BeConflict_NotSilentBestEffort() {
        // Sides 3, 4 and 8 cannot close; no two rows are parallel at the start, only at the least-squares stall point.
        val eqs = listOf(DistanceDimension("ab", 0, 2, 3.0), DistanceDimension("bc", 2, 4, 4.0), DistanceDimension("ca", 4, 0, 8.0))
        val start = doubleArrayOf(0.0, 0.0, 3.0, 0.1, 1.0, 3.0)
        val res = Solver().solve(ConstraintProblem(eqs, 6), start)
        assertFalse(res is SolveResult.Solved, "an impossible triangle must not be Solved")
        assertTrue(res is SolveResult.Conflict || res is SolveResult.NoConvergence, "got ${res::class.simpleName}")
        assertTrue(res.x.contentEquals(start), "failed solves return the last good parameters")
    }

    @Test
    fun primitiveOnlySketches_should_ReportTheAnalyticDof() = runTest {
        checkAll(iterationsPerKind, Arb.int()) { seed ->
            val rnd = Random(seed)
            val points = rnd.nextInt(3, 8)
            val coords = DoubleArray(points * 2) { rnd.nextDouble(-5.0, 5.0) }
            val eqs = ArrayList<ConstraintEquation>()
            var rows = 0
            for (i in 0 until points - 1) {
                when (rnd.nextInt(3)) {
                    0 -> { eqs += Horizontal("h$i", 2 * i, 2 * i + 2); coords[2 * i + 3] = coords[2 * i + 1] }
                    1 -> { eqs += Vertical("v$i", 2 * i, 2 * i + 2); coords[2 * i + 2] = coords[2 * i] }
                    else -> eqs += DistanceDimension("d$i", 2 * i, 2 * i + 2, hypot(coords[2 * i + 2] - coords[2 * i], coords[2 * i + 3] - coords[2 * i + 1]))
                }
                rows++
            }
            val start = DoubleArray(coords.size) { coords[it] + rnd.nextDouble(-0.05, 0.05) }
            val res = Solver().solve(ConstraintProblem(eqs, coords.size), start)
            assertTrue(res is SolveResult.Solved, "chain of $points points: ${res::class.simpleName}")
            assertEquals(coords.size - rows, res.dof)
        }
    }

    // ---- fallback, caps, degenerate input ------------------------------------------------------------------

    @Test
    fun forcedSmallBudget_should_RunTheFallback_AndNameTheAlgorithmThatSucceeded() {
        val case = SolverCorpus.hundredConstraints(SolverCorpus.tailSeeds.first())
        val dogLegStarved = SolverOptions(listOf(AlgorithmBudget(SolveAlgorithm.DogLeg, 3), AlgorithmBudget(SolveAlgorithm.LevenbergMarquardt, 100)))
        val res = Solver(dogLegStarved).solve(case.problem, case.start)
        assertTrue(res is SolveResult.Solved, "got ${res::class.simpleName}")
        assertEquals(SolveAlgorithm.LevenbergMarquardt, res.algorithm)
        val lmStarved = SolverOptions(listOf(AlgorithmBudget(SolveAlgorithm.LevenbergMarquardt, 3), AlgorithmBudget(SolveAlgorithm.DogLeg, 100)))
        val res2 = Solver(lmStarved).solve(case.problem, case.start)
        assertTrue(res2 is SolveResult.Solved, "got ${res2::class.simpleName}")
        assertEquals(SolveAlgorithm.DogLeg, res2.algorithm)
    }

    @Test
    fun exhaustedBudgets_should_BeNoConvergence_WithTheStartValues() {
        val case = SolverCorpus.hundredConstraints(SolverCorpus.tailSeeds.first())
        val starved = SolverOptions(listOf(AlgorithmBudget(SolveAlgorithm.DogLeg, 2), AlgorithmBudget(SolveAlgorithm.LevenbergMarquardt, 2)))
        val res = Solver(starved).solve(case.problem, case.start)
        assertTrue(res is SolveResult.NoConvergence, "got ${res::class.simpleName}")
        assertEquals(StopReason.IterationBudget, res.reason)
        assertTrue(res.x.contentEquals(case.start))
    }

    @Test
    fun wallTimeCap_should_ReturnNoConvergence_InsteadOfHanging() {
        val case = SolverCorpus.hundredConstraints(SolverCorpus.tailSeeds.first())
        val res = Solver(SolverOptions(wallTimeMillis = 0L)).solve(case.problem, case.start)
        assertTrue(res is SolveResult.NoConvergence, "got ${res::class.simpleName}")
        assertEquals(StopReason.WallTime, res.reason)
        assertTrue(res.x.contentEquals(case.start))
    }

    @Test
    fun zeroLengthLine_should_BeDegenerate_WithFiniteNumbers_AndNoException() {
        // Line 0-1 has zero length: a Distance on it has a zero gradient and nothing can satisfy it.
        val x = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 4.0, 1.0, 7.0, 2.0)
        val eqs = listOf(Parallel("par", 0, 2, 4, 6), DistanceDimension("len", 0, 2, 5.0))
        val res = Solver().solve(ConstraintProblem(eqs, 8), x)
        assertTrue(res is SolveResult.Degenerate, "got ${res::class.simpleName}")
        assertEquals(listOf("len"), res.ids)
        assertTrue(res.x.all { it.isFinite() })
        assertTrue(res.x.contentEquals(x))
    }

    @Test
    fun parallelOnAZeroLengthLine_should_StayFiniteAndNeverThrow() {
        // Parallel is a polynomial row, so it is already satisfied (and finite) when one segment collapses.
        val x = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 4.0, 1.0, 7.0, 2.0)
        val res = Solver().solve(ConstraintProblem(listOf(Parallel("par", 0, 2, 4, 6)), 8), x)
        assertTrue(res is SolveResult.Solved || res is SolveResult.Redundant || res is SolveResult.Degenerate, "got ${res::class.simpleName}")
        assertTrue(res.x.all { it.isFinite() } && res.residualInf.isFinite())
    }

    @Test
    fun nonFiniteStart_should_BeDegenerate_NotAnException() {
        val res = Solver().solve(ConstraintProblem(listOf(Horizontal("h", 0, 2)), 4), doubleArrayOf(0.0, Double.NaN, 1.0, 1.0))
        assertTrue(res is SolveResult.Degenerate, "got ${res::class.simpleName}")
        val inf = Solver().solve(ConstraintProblem(listOf(DistanceDimension("d", 0, 2, 1.0)), 4), doubleArrayOf(0.0, 0.0, Double.POSITIVE_INFINITY, 1.0))
        assertTrue(inf is SolveResult.Degenerate, "got ${inf::class.simpleName}")
    }

    @Test
    fun degenerateFuzz_should_NeverThrowOrReturnNonFiniteValues() = runTest {
        checkAll(iterationsPerKind * 5, Arb.int()) { seed ->
            val rnd = Random(seed)
            val x = DoubleArray(variableCount) { if (rnd.nextInt(3) == 0) 0.0 else rnd.nextDouble(-1e-9, 1e-9) + rnd.nextInt(-2, 3) }
            val eqs = kinds.shuffled(rnd).take(4).map { it.build(x) }
            val res = Solver().solve(ConstraintProblem(eqs, variableCount), x)
            assertTrue(res.x.all { it.isFinite() }, "non-finite output from ${res::class.simpleName}")
        }
    }

    // ---- warm start and branch stability during a drag -----------------------------------------------------

    @Test
    fun tangentDrag200Steps_should_SolveEveryStep_WithoutABranchFlip() {
        // Line (0,0)-(10,0) pinned, circle centre dragged by a pin, radius free; tangent keeps the circle above the line.
        val x0 = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 5.0, 5.0, 5.0)
        val drag = FixedPoint("drag", 4, 5.0, 5.0)
        val tangent = TangentLineCircle("t", 0, 2, 4, RadiusRef.scalar(6), TangentLineCircle.sideOf(x0, 0, 2, 4))
        val problem = ConstraintProblem(listOf(FixedPoint("fa", 0, 0.0, 0.0), FixedPoint("fb", 2, 10.0, 0.0), tangent, drag), 7)
        val solver = Solver()
        var x = x0
        var worstMoveOverDelta = 0.0
        val step = 0.5
        for (i in 1..200) {
            drag.tx = 5.0 + i * step
            drag.ty = 5.0 - i * step * 0.0225
            val res = solver.solve(problem, x)
            assertTrue(res is SolveResult.Solved, "step $i: ${res::class.simpleName}")
            worstMoveOverDelta = maxOf(worstMoveOverDelta, hypot(res.x[4] - x[4], res.x[5] - x[5]) / step)
            assertTrue(res.x[5] > 0.0 && res.x[6] > 0.0, "step $i: circle flipped below the line or radius sign changed")
            assertTrue(abs(res.x[5] - res.x[6]) < 1e-6, "step $i: tangent distance")
            x = res.x
        }
        assertTrue(worstMoveOverDelta <= 3.0, "point motion $worstMoveOverDelta x the pointer delta")
    }
}
