// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.sketch.constraints.constraints.AngleDimension
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceDimension
import dev.stapler.stelekit.sketch.constraints.constraints.FixedPoint
import dev.stapler.stelekit.sketch.constraints.constraints.Horizontal
import dev.stapler.stelekit.sketch.constraints.constraints.Parallel
import dev.stapler.stelekit.sketch.constraints.constraints.Perpendicular
import dev.stapler.stelekit.sketch.constraints.constraints.PointLineDistance
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Review-fix D5 and D6: the Gauss-Newton ridge scales with the sketch, and a point exactly on the line still gets a gradient. */
class SolverScaleTest {
    private fun angleSolve(length: Double): SolveResult {
        val problem = ConstraintProblem(
            listOf(
                FixedPoint("a", 0, 0.0, 0.0), FixedPoint("b", 2, length, 0.0), FixedPoint("c", 4, 0.0, 0.0),
                AngleDimension("ang", 0, 2, 4, 6, ANGLE),
            ),
            variableCount = 8,
        )
        return Solver().solve(problem, doubleArrayOf(0.0, 0.0, length, 0.0, 0.0, 0.0, length, 0.3 * length))
    }

    @Test
    fun angleSolve_should_StayOnDogLegWithFewIterations_AtFiftyFeet() {
        val small = angleSolve(100.0)
        val large = angleSolve(LARGE)
        assertTrue(small is SolveResult.Solved && large is SolveResult.Solved, "${small::class.simpleName} / ${large::class.simpleName}")
        println("SOLVER scale small_its=${small.iterations} large_its=${large.iterations} algorithm=${large.algorithm}")
        assertTrue(abs(atan2(large.x[7], large.x[6]) - ANGLE) < 1e-6, "angle reached")
        assertEquals(small.algorithm, large.algorithm, "same algorithm at both scales (small ${small.iterations} its, large ${large.iterations} its)")
        assertTrue(large.iterations <= LARGE_ITERATION_BAR, "iterations at $LARGE units: ${large.iterations} (before the fix: 104 on Levenberg-Marquardt), at 100 units: ${small.iterations}")
    }

    @Test
    fun pointLineDistance_should_LeaveTheLine_When_ThePointStartsExactlyOnIt() {
        val problem = ConstraintProblem(
            listOf(FixedPoint("a", 0, 0.0, 0.0), FixedPoint("b", 2, 10.0, 0.0), PointLineDistance("d", 4, 0, 2, 3.0)),
            variableCount = 6,
        )
        val solved = Solver().solve(problem, doubleArrayOf(0.0, 0.0, 10.0, 0.0, 5.0, 0.0))
        assertTrue(solved is SolveResult.Solved, "got ${solved::class.simpleName}")
        assertTrue(abs(abs(solved.x[5]) - 3.0) < 1e-6, "distance reached: y = ${solved.x[5]}")
    }

    /** A 100 x 50 rectangle (a, b, c, d) pinned at `(offset, offset)`, started 0.4 off, solved at that offset. */
    private fun rectangleSolve(offset: Double): SolveResult {
        val problem = ConstraintProblem(
            listOf(
                FixedPoint("pin", 0, offset, offset), Horizontal("h", 0, 2), DistanceDimension("w", 0, 2, 100.0),
                Perpendicular("perp", 0, 2, 2, 4), DistanceDimension("ht", 2, 4, 50.0),
                Parallel("par", 4, 6, 0, 2), DistanceDimension("w2", 4, 6, 100.0),
            ),
            variableCount = 8,
        )
        val start = doubleArrayOf(0.0, 0.0, 99.6, 0.4, 100.4, 49.6, -0.4, 50.4).also { for (i in it.indices) it[i] += offset }
        return Solver().solve(problem, start)
    }

    @Test
    fun solve_should_ConvergeToTheSameShape_When_TheSketchIsFarFromTheOrigin() {
        val atOrigin = rectangleSolve(0.0)
        assertTrue(atOrigin is SolveResult.Solved, "origin: ${atOrigin::class.simpleName}")
        for (offset in listOf(1e5, 1e7, -1e7, 2.5e8)) {
            val far = rectangleSolve(offset)
            assertTrue(far is SolveResult.Solved, "offset $offset: ${far::class.simpleName} residual ${far.residualInf}")
            for (i in far.x.indices) assertTrue(abs((far.x[i] - offset) - atOrigin.x[i]) < 1e-6, "offset $offset var $i: ${far.x[i] - offset} vs ${atOrigin.x[i]}")
        }
    }

    private companion object {
        const val ANGLE = 0.5
        const val LARGE = 9_600.0
        const val LARGE_ITERATION_BAR = 16
    }
}
