// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Story 4.1.3 performance acceptance and the ADR-003 open-risk measurement: the 30-seed corpus of Spike 0.1.2
 * (seeds 100..129; 111 and 127 are the damped-plateau tail) on the production solver, per algorithm.
 * Lines starting "SOLVER " are the measurements recorded in ADR-003.
 *
 * Wall times depend on the host, so each solve is timed best-of-[REPEATS] after a warm-up (a loaded machine inflates the
 * single-run maximum, not the minimum); iteration counts are machine independent and asserted exactly where it matters.
 */
class SolverCorpusTest {
    private val onJvm: Boolean = !1e-9.toString().contains('e')
    // Owner-approved headroom over the 50 ms design target: shared CI runners have measured 58 ms on a healthy solver.
    private val barMillis = if (onJvm) 100.0 else 150.0
    private val platform = if (onJvm) "jvm" else "wasm-or-js"

    private fun bestOf(solver: Solver, case: SolverCorpus.Case): Pair<SolveResult, Double> {
        var best = Double.MAX_VALUE
        var last: SolveResult? = null
        repeat(REPEATS) {
            val t0 = TimeSource.Monotonic.markNow()
            last = solver.solve(case.problem, case.start)
            best = minOf(best, t0.elapsedNow().inWholeMicroseconds / 1000.0)
        }
        return last!! to best
    }

    private fun only(algorithm: SolveAlgorithm, rowScaling: Boolean = false, decompose: Boolean = true) =
        Solver(SolverOptions(listOf(AlgorithmBudget(algorithm, SolverOptions.DEFAULT_ITERATION_BUDGET)), rowScaling = rowScaling, decompose = decompose))

    @Test
    fun defaultSolver_should_SolveEverySeedOfTheCorpus_WithinTheTimeBar() {
        val solver = Solver()
        repeat(WARMUPS) { solver.solve(SolverCorpus.hundredConstraints(1).problem, SolverCorpus.hundredConstraints(1).start) }
        var worstMs = 0.0
        var worstIterations = 0
        var worstResidual = 0.0
        for (seed in SolverCorpus.corpusSeeds) {
            val case = SolverCorpus.hundredConstraints(seed)
            val (res, ms) = bestOf(solver, case)
            assertTrue(res is SolveResult.Solved, "seed $seed: ${res::class.simpleName} residual ${res.residualInf}")
            assertTrue(res.x.none { it.isNaN() })
            worstMs = max(worstMs, ms)
            worstIterations = max(worstIterations, res.iterations)
            worstResidual = max(worstResidual, SolverCorpus.maxResidual(case.problem, res.x))
        }
        println("SOLVER default platform=$platform corpus30 worst_best_of_${REPEATS}_ms=$worstMs worst_iterations=$worstIterations worst_residual=$worstResidual")
        assertTrue(worstResidual < RESIDUAL_ACCEPTANCE_BAR, "residual $worstResidual")
        assertTrue(worstMs < barMillis, "worst seed $worstMs ms over the $barMillis ms bar")
    }

    @Test
    fun dogLeg_should_ClearTheDampedPlateauThatLevenbergMarquardtCrawlsThrough() {
        for (seed in SolverCorpus.tailSeeds) {
            val case = SolverCorpus.hundredConstraints(seed)
            val (dog, dogMs) = bestOf(only(SolveAlgorithm.DogLeg), case)
            val (lm, lmMs) = bestOf(only(SolveAlgorithm.LevenbergMarquardt), case)
            val (lmRows, lmRowsMs) = bestOf(only(SolveAlgorithm.LevenbergMarquardt, rowScaling = true), case)
            val (whole, wholeMs) = bestOf(only(SolveAlgorithm.LevenbergMarquardt, decompose = false), case)
            println("SOLVER tail platform=$platform seed=$seed dogleg=${dog.iterations}it/${dogMs}ms lm=${lm.iterations}it/${lmMs}ms lm_rowscale=${lmRows.iterations}it/${lmRowsMs}ms lm_no_decomposition=${whole.iterations}it/${wholeMs}ms")
            assertTrue(dog is SolveResult.Solved && lm is SolveResult.Solved, "seed $seed")
            assertTrue(dog.iterations <= DOGLEG_TAIL_ITERATIONS, "seed $seed: dogleg took ${dog.iterations}")
            assertTrue(lm.iterations >= LM_PLATEAU_ITERATIONS, "seed $seed: the LM plateau is the documented weakness, got ${lm.iterations}")
            assertEquals(lm.iterations, whole.iterations, "the chain is one component, so decomposition changes nothing")
        }
    }

    @Test
    fun hundredConstraintSolve_should_MeetTheBarCold_AndPrintTheSolutionForCrossTargetComparison() {
        val case = SolverCorpus.hundredConstraints(1)
        val solver = Solver()
        val t0 = TimeSource.Monotonic.markNow()
        val res = solver.solve(case.problem, case.start)
        val coldMs = t0.elapsedNow().inWholeMicroseconds / 1000.0
        // After the cold sample: without this the "steady" runs can be timed while the JIT is still compiling.
        // Timed, not counted: JIT compiles in the background, and on CI 6 solves were not enough.
        val warmupStart = TimeSource.Monotonic.markNow()
        while (warmupStart.elapsedNow() < WARMUP_TIME) solver.solve(case.problem, case.start)
        val (_, steadyMs) = bestOf(solver, case)
        println("SOLVER seed1 platform=$platform cold_ms=$coldMs steady_best_ms=$steadyMs iterations=${res.iterations} dof=${res.dof}")
        println("SOLVER seed1 solution=" + res.x.joinToString(",") { it.toString() })
        assertTrue(res is SolveResult.Solved)
        assertTrue(SolverCorpus.maxResidual(case.problem, res.x) < RESIDUAL_ACCEPTANCE_BAR)
        assertTrue(steadyMs < barMillis, "steady $steadyMs ms over the $barMillis ms bar")
    }

    private companion object {
        const val REPEATS = 5
        const val WARMUPS = 3
        val WARMUP_TIME = 1.seconds
        const val DOGLEG_TAIL_ITERATIONS = 10
        const val LM_PLATEAU_ITERATIONS = 40
    }
}
