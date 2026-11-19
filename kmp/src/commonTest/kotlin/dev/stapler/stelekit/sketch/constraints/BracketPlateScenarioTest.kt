// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.sketch.constraints.constraints.ConstraintEquation
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceDimension
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceX
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceY
import dev.stapler.stelekit.sketch.constraints.constraints.FixedPoint
import dev.stapler.stelekit.sketch.constraints.constraints.Horizontal
import dev.stapler.stelekit.sketch.constraints.constraints.Parallel
import dev.stapler.stelekit.sketch.constraints.constraints.PointOnLine
import dev.stapler.stelekit.sketch.constraints.constraints.RadiusDimension
import dev.stapler.stelekit.sketch.constraints.constraints.RadiusRef
import dev.stapler.stelekit.sketch.constraints.constraints.Vertical
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bracket plate of requirements metric 3 on a `Full` target: it needs Fixed, Radius and Parallel, each of which passes
 * its randomised property test in `SolverPropertyTest` (FixedPoint, Radius, Parallel), so the scenario is enabled.
 * Layout: plate corners p0..p3 at 0, 2, 4, 6; hole centre at 8, hole radius at 10; rib endpoints at 11 and 13.
 */
class BracketPlateScenarioTest {
    private val plateWidth = 120.0
    private val plateHeight = 80.0
    private val holeX = 30.0
    private val holeY = 40.0
    private val holeRadius = 10.0
    private val ribY = 60.0
    private val variables = 15
    private val tolerance = RESIDUAL_ACCEPTANCE_BAR

    private fun equations(): List<ConstraintEquation> = listOf(
        Horizontal("bottom", 0, 2), Horizontal("top", 6, 4), Vertical("right", 2, 4), Vertical("left", 0, 6),
        FixedPoint("anchor", 0, 0.0, 0.0),
        DistanceX("width", 0, 2, plateWidth), DistanceY("height", 0, 6, plateHeight),
        DistanceX("hole-x", 0, 8, holeX), DistanceY("hole-y", 0, 8, holeY), RadiusDimension("hole-r", RadiusRef.scalar(10), holeRadius),
        PointOnLine("rib-left", 11, 0, 6), PointOnLine("rib-right", 13, 2, 4),
        Parallel("rib-parallel", 11, 13, 0, 2), DistanceY("rib-y", 0, 11, ribY),
    )

    private val truth = doubleArrayOf(
        0.0, 0.0, plateWidth, 0.0, plateWidth, plateHeight, 0.0, plateHeight,
        holeX, holeY, holeRadius,
        0.0, ribY, plateWidth, ribY,
    )

    @Test
    fun bracketPlate_should_BeFullyConstrained_AndSolveToTheDimensions() {
        val rnd = Random(11)
        val start = DoubleArray(variables) { truth[it] + rnd.nextDouble(-2.0, 2.0) }
        val problem = ConstraintProblem(equations(), variables)
        val res = Solver().solve(problem, start)
        assertTrue(res is SolveResult.Solved, "got ${res::class.simpleName}")
        assertEquals(0, res.dof, "fully constrained")
        for (i in 0 until variables) assertTrue(abs(res.x[i] - truth[i]) < tolerance, "variable $i is ${res.x[i]}, want ${truth[i]}")
    }

    @Test
    fun anExtraDistanceOnTheHoleCentre_should_BeRejectedAsRedundantOrConflicting() {
        val problem = ConstraintProblem(equations() + DistanceDimension("extra", 0, 8, 51.0), variables)
        val res = Solver().solve(problem, truth.copyOf())
        assertTrue(res is SolveResult.Conflict || res is SolveResult.NoConvergence, "got ${res::class.simpleName}")
    }
}
