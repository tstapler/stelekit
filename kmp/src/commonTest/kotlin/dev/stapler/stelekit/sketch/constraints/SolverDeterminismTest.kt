// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-003 item 6: JVM and wasm solutions agree to 1e-6. The two targets differ only in last-bit arithmetic, so this
 * perturbs every start coordinate by a few ulps and requires the same solution: a solver that amplifies rounding noise
 * through the null space of an under-determined sketch (the Gauss-Newton ridge was once 1e-10) fails here on one target.
 */
class SolverDeterminismTest {
    private val relativeNoise = 4e-16
    private val agreementBar = 1e-6

    @Test
    fun lastBitNoiseInTheStart_should_MoveTheSolutionByLessThanTheAgreementBar_OnTheWholeCorpus() {
        var worst = 0.0
        for (seed in SolverCorpus.corpusSeeds) {
            val case = SolverCorpus.hundredConstraints(seed)
            val rnd = Random(seed)
            val noisy = DoubleArray(case.start.size) { case.start[it] * (1.0 + (rnd.nextDouble() - 0.5) * relativeNoise) }
            val a = Solver().solve(case.problem, case.start)
            val b = Solver().solve(case.problem, noisy)
            assertTrue(a is SolveResult.Solved && b is SolveResult.Solved, "seed $seed")
            for (i in a.x.indices) worst = maxOf(worst, abs(a.x[i] - b.x[i]))
        }
        println("SOLVER determinism worst_coordinate_difference=$worst")
        assertTrue(worst < agreementBar, "noise moved the solution by $worst")
    }
}
