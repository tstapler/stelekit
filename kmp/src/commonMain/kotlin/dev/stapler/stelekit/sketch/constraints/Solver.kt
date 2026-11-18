package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.sketch.constraints.constraints.ConstraintEquation
import dev.stapler.stelekit.sketch.constraints.linalg.Cholesky
import dev.stapler.stelekit.sketch.constraints.linalg.DenseMatrix
import dev.stapler.stelekit.sketch.constraints.linalg.hasNonFinite
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.TimeSource

/** The step algorithm that produced a result (Story 4.1.3: the result names which algorithm succeeded). */
enum class SolveAlgorithm { LevenbergMarquardt, DogLeg }

/** One algorithm and its own iteration budget; the next entry of [SolverOptions.algorithms] runs only if this one fails. */
data class AlgorithmBudget(val algorithm: SolveAlgorithm, val iterations: Int)

/**
 * Solver knobs. [wallTimeMillis] bounds the whole solve (all components, all algorithms). No caller overrides it, so every
 * production solve is capped at [DEFAULT_WALL_TIME_MILLIS] (2 s); `SceneLimits` has no solve-time cap, only the 100 ms
 * slow-solve indicator. One drag step can run an anchored and then a full solve, each with its own cap.
 * [rowScaling] normalises each Jacobian row to unit length inside the loop (measured in ADR-003; the production rows stay unscaled).
 */
data class SolverOptions(
    val algorithms: List<AlgorithmBudget> = DEFAULT_ALGORITHMS,
    val wallTimeMillis: Long = DEFAULT_WALL_TIME_MILLIS,
    val rowScaling: Boolean = false,
    val decompose: Boolean = true,
    val gaussNewtonRidge: Double = DEFAULT_GAUSS_NEWTON_RIDGE,
) {
    companion object {
        const val DEFAULT_ITERATION_BUDGET = 100
        const val DEFAULT_WALL_TIME_MILLIS = 2_000L
        /**
         * Relative ridge of the Gauss-Newton normal equations. Below about 1e-8 it amplifies last-bit differences into the
         * null space of an under-determined sketch (1e-16 start noise moved the solution by 5e-4 at 1e-10, ADR-003), which
         * would break the JVM-vs-wasm 1e-6 agreement bar.
         */
        const val DEFAULT_GAUSS_NEWTON_RIDGE = 1e-6
        /** DogLeg first: on the 30-seed corpus it needs 5 iterations where LM plateaus for 45-61 (ADR-003); LM is the fallback. */
        val DEFAULT_ALGORITHMS = listOf(
            AlgorithmBudget(SolveAlgorithm.DogLeg, DEFAULT_ITERATION_BUDGET),
            AlgorithmBudget(SolveAlgorithm.LevenbergMarquardt, DEFAULT_ITERATION_BUDGET),
        )
    }
}

/** Why a [SolveResult.NoConvergence] stopped. */
enum class StopReason { IterationBudget, WallTime, Stalled, NonFinite }

/**
 * Equations over one flat variable vector (a point is two consecutive variables, a scalar one). The component
 * structure is derived from the first start and cached, so a drag that re-solves the same problem pays for it once.
 */
class ConstraintProblem(val equations: List<ConstraintEquation>, val variableCount: Int) {
    internal val rowOffsets: IntArray = IntArray(equations.size + 1).also { for (i in equations.indices) it[i + 1] = it[i] + equations[i].rowCount }
    val rowCount: Int get() = rowOffsets[equations.size]

    private var cachedComponents: List<Component>? = null

    internal fun components(x: DoubleArray): List<Component> =
        cachedComponents ?: decompose(equations, variableSupport(equations, x), variableCount).also { cachedComponents = it }
}

/** Outcome of a solve. [x] is always the full variable vector: solved values, or the start values for what failed. */
sealed class SolveResult {
    abstract val x: DoubleArray

    /** Degrees of freedom: variables minus the rank of the final Jacobian. */
    abstract val dof: Int
    abstract val residualInf: Double
    abstract val iterations: Int
    abstract val algorithm: SolveAlgorithm?

    class Solved(override val x: DoubleArray, override val dof: Int, override val residualInf: Double, override val iterations: Int, override val algorithm: SolveAlgorithm?) : SolveResult()
    class Redundant(val ids: List<String>, override val x: DoubleArray, override val dof: Int, override val residualInf: Double, override val iterations: Int, override val algorithm: SolveAlgorithm?) : SolveResult()
    class Conflict(val ids: List<String>, override val x: DoubleArray, override val dof: Int, override val residualInf: Double, override val iterations: Int, override val algorithm: SolveAlgorithm?) : SolveResult()
    class NoConvergence(val reason: StopReason, override val x: DoubleArray, override val dof: Int, override val residualInf: Double, override val iterations: Int, override val algorithm: SolveAlgorithm?) : SolveResult()

    /** A constraint with no usable gradient (zero-length geometry) or a non-finite input: [ids] names them. */
    class Degenerate(val ids: List<String>, override val x: DoubleArray, override val dof: Int, override val residualInf: Double, override val iterations: Int, override val algorithm: SolveAlgorithm?) : SolveResult()
}

/** Residual bar of ADR-003 item 6, scene units: a solve that stops above it is `NoConvergence`, never committed. */
const val RESIDUAL_ACCEPTANCE_BAR = 1e-6

/** Stop tolerance relative to sketch scale (ADR-003 item 6), clamped so a large sheet offset cannot loosen the bar. */
private const val RELATIVE_STOP_TOLERANCE = 1e-9
private const val STOP_TOLERANCE_CEILING = 1e-7

private const val ROW_SCALE_FLOOR = 1e-12
private const val INITIAL_LAMBDA = 1e-3
private const val LAMBDA_FLOOR = 1e-12
private const val LAMBDA_CEILING = 1e12
private const val LAMBDA_SHRINK = 5.0
private const val LAMBDA_GROW = 4.0
private const val LAMBDA_RETRY_GROW = 10.0
private const val DIAGONAL_FLOOR = 1e-9

/** Unit scale for the Gauss-Newton ridge, divided by the square of [ridgeScale] so a large sketch (rows with gradient ~1/L) is not over-damped: a barely coupled coordinate (gradient near 1e-5) is damped like a coupled one, so it does not take up to residual / (2 * gradient) of a fix. */
private const val GAUSS_NEWTON_DIAGONAL_FLOOR = 1.0
private const val RIDGE_REFERENCE_EXTENT = 256.0
private const val MAX_DAMPING_RETRIES = 30
private const val INITIAL_TRUST_RADIUS_FACTOR = 1.0
private const val TRUST_GROW = 2.0
private const val TRUST_SHRINK = 0.25
private const val TRUST_GOOD_RHO = 0.75
private const val TRUST_BAD_RHO = 0.25
private const val TRUST_ACCEPT_RHO = 1e-4
private const val TRUST_FLOOR = 1e-14

/** How many times larger than [RIDGE_REFERENCE_EXTENT] the component's coordinates reach (1 at or below it, so small sketches keep the original unit floor). */
private fun ridgeScale(extent: Double): Double = max(1.0, extent / RIDGE_REFERENCE_EXTENT)

private val NEVER_CANCELLED: () -> Boolean = { false }

/** The solve's start time plus a caller poll; a true [cancelled] stops the solve at the next iteration like the wall-time cap does. */
private class Deadline(val mark: TimeSource.Monotonic.ValueTimeMark, val cancelled: () -> Boolean)

/**
 * Levenberg-Marquardt with Marquardt diagonal scaling and a DogLeg fallback over connected components
 * (ADR-003; clean-room, textbook trust-region methods only). Never throws: every failure is a [SolveResult] case.
 */
class Solver(private val options: SolverOptions = SolverOptions()) {

    fun solve(problem: ConstraintProblem, start: DoubleArray, cancelled: () -> Boolean = NEVER_CANCELLED): SolveResult {
        require(start.size == problem.variableCount) { "start has ${start.size} values, problem has ${problem.variableCount}" }
        val clock = Deadline(TimeSource.Monotonic.markNow(), cancelled)
        val n = problem.variableCount
        val x = start.copyOf()
        val nonFinite = nonFiniteEquations(problem, x)
        if (start.hasNonFinite() || nonFinite.isNotEmpty()) {
            return SolveResult.Degenerate(nonFinite, start.copyOf(), n, Double.NaN, 0, null)
        }
        val scale = max(1.0, start.maxOfOrNull { abs(it) } ?: 1.0)
        val tolerance = min(RELATIVE_STOP_TOLERANCE * scale, STOP_TOLERANCE_CEILING)
        val comps = if (options.decompose) problem.components(x) else listOf(Component(IntArray(problem.equations.size) { it }, IntArray(n) { it }))
        val runs = ArrayList<ComponentRun>(comps.size)
        for (c in comps) {
            if (c.equations.all { problem.equations[it].rowCount == 0 }) continue
            runs += solveComponent(problem, c, x, tolerance, clock)
        }
        val failed = runs.filter { !it.converged }
        for (run in failed) for (v in run.component.variables) x[v] = start[v]
        var finalX = x
        var overall: List<ComponentRun> = runs
        if (failed.isEmpty() && fullResidualInf(problem, x) > RESIDUAL_ACCEPTANCE_BAR && options.decompose) {
            // The probe-based support missed a coupling: redo the whole problem as one component.
            val whole = Component(IntArray(problem.equations.size) { it }, IntArray(n) { it })
            finalX = start.copyOf()
            overall = listOf(solveComponent(problem, whole, finalX, tolerance, clock))
            if (!overall[0].converged) finalX = start.copyOf()
        }
        return diagnose(problem, finalX, overall, tolerance)
    }

    private class ComponentRun(val component: Component, val converged: Boolean, val iterations: Int, val algorithm: SolveAlgorithm?, val reason: StopReason?, val stalledAt: DoubleArray? = null)

    private fun solveComponent(problem: ConstraintProblem, comp: Component, x: DoubleArray, tolerance: Double, clock: Deadline): ComponentRun {
        val ws = Workspace(problem, comp, x)
        var iterations = 0
        var reason: StopReason? = StopReason.Stalled
        var last: SolveAlgorithm? = null
        if (ws.residualInf() <= tolerance) return ComponentRun(comp, true, 0, null, null)
        for (budget in options.algorithms) {
            ws.restart()
            last = budget.algorithm
            val outcome = when (budget.algorithm) {
                SolveAlgorithm.LevenbergMarquardt -> ws.levenbergMarquardt(budget.iterations, tolerance, clock)
                SolveAlgorithm.DogLeg -> ws.dogLeg(budget.iterations, tolerance, clock)
            }
            iterations += outcome.iterations
            reason = outcome.reason
            if (outcome.reason == null) {
                ws.commit(x)
                return ComponentRun(comp, true, iterations, budget.algorithm, null)
            }
            if (outcome.reason == StopReason.WallTime) break
        }
        return ComponentRun(comp, false, iterations, last, reason, ws.snapshot())
    }

    private fun diagnose(problem: ConstraintProblem, x: DoubleArray, runs: List<ComponentRun>, tolerance: Double): SolveResult {
        val iterations = runs.sumOf { it.iterations }
        val algorithm = runs.mapNotNull { it.algorithm }.maxByOrNull { it.ordinal }
        val residual = fullResidualInf(problem, x)
        val diagnosis = Diagnosis.of(problem, x, tolerance)
        val failed = runs.firstOrNull { !it.converged }
        return when {
            diagnosis.degenerate.isNotEmpty() -> SolveResult.Degenerate(diagnosis.degenerate, x, diagnosis.dof, residual, iterations, algorithm)
            failed != null || residual > RESIDUAL_ACCEPTANCE_BAR -> {
                val conflictIds = runs.filter { !it.converged }.flatMap { run -> Diagnosis.conflictIds(problem, run.stalledAt ?: x, listOf(run.component)) }.distinct()
                if (conflictIds.isNotEmpty()) {
                    SolveResult.Conflict(conflictIds, x, diagnosis.dof, residual, iterations, algorithm)
                } else {
                    SolveResult.NoConvergence(failed?.reason ?: StopReason.Stalled, x, diagnosis.dof, residual, iterations, algorithm)
                }
            }
            diagnosis.redundant.isNotEmpty() -> SolveResult.Redundant(diagnosis.redundant, x, diagnosis.dof, residual, iterations, algorithm)
            else -> SolveResult.Solved(x, diagnosis.dof, residual, iterations, algorithm)
        }
    }

    private fun nonFiniteEquations(problem: ConstraintProblem, x: DoubleArray): List<String> {
        val out = ArrayList<String>()
        val r = DoubleArray(maxOf(problem.rowCount, 1))
        for ((i, eq) in problem.equations.withIndex()) {
            eq.residual(x, r, problem.rowOffsets[i])
            for (k in 0 until eq.rowCount) if (!r[problem.rowOffsets[i] + k].isFinite()) { out += eq.id; break }
        }
        return out
    }

    private fun fullResidualInf(problem: ConstraintProblem, x: DoubleArray): Double {
        val r = DoubleArray(problem.rowCount)
        for ((i, eq) in problem.equations.withIndex()) eq.residual(x, r, problem.rowOffsets[i])
        var worst = 0.0
        for (v in r) {
            if (v.isNaN()) return Double.NaN
            worst = max(worst, abs(v))
        }
        return worst
    }

    private class Outcome(val iterations: Int, val reason: StopReason?)

    /** Buffers and step algorithms for one component. Variables are compacted to `0 until n`. */
    private inner class Workspace(private val problem: ConstraintProblem, private val comp: Component, source: DoubleArray) {
        private val eqs = comp.equations.map { problem.equations[it] }
        private val m = eqs.sumOf { it.rowCount }
        private val n = comp.variables.size
        private val cols = comp.variables
        private val full = DenseMatrix(max(m, 1), max(problem.variableCount, 1))
        private val j = DenseMatrix(m, n)
        private val gram = DenseMatrix(n, n)
        private val work = DenseMatrix(n, n)
        private val chol = Cholesky(n)
        private val initial = source.copyOf()
        private val extentScale = ridgeScale(cols.maxOfOrNull { abs(source[it]) } ?: 0.0)
        private val diagonalFloor = GAUSS_NEWTON_DIAGONAL_FLOOR / (extentScale * extentScale)
        private var xc = source.copyOf()
        private var xt = source.copyOf()
        private var r = DoubleArray(m)
        private var rt = DoubleArray(m)
        private val rowScale = DoubleArray(m) { 1.0 }
        private val g = DoubleArray(n)
        private val rhs = DoubleArray(n)
        private val dx = DoubleArray(n)
        private val gn = DoubleArray(n)
        private val sd = DoubleArray(n)
        private val jsd = DoubleArray(m)
        private val jp = DoubleArray(m)

        fun restart() { initial.copyInto(xc); r = residuals(xc, r) }

        fun commit(out: DoubleArray) { for (c in cols) out[c] = xc[c] }

        fun snapshot(): DoubleArray = xc.copyOf()

        fun residualInf(): Double { r = residuals(xc, r); return infNorm(r) }

        private fun residuals(at: DoubleArray, into: DoubleArray): DoubleArray {
            var off = 0
            for (eq in eqs) { eq.residual(at, into, off); off += eq.rowCount }
            return into
        }

        /** Evaluates J (with optional row scaling folded into both J and [r]) at [xc]; the scale is remembered. */
        private fun linearise() {
            full.fill(0.0)
            var off = 0
            for (eq in eqs) { eq.jacobian(xc, full, off); off += eq.rowCount }
            for (row in 0 until m) {
                var s = 0.0
                for (c in 0 until n) { val v = full[row, cols[c]]; j[row, c] = v; s += v * v }
                rowScale[row] = if (options.rowScaling) 1.0 / max(sqrt(s), ROW_SCALE_FLOOR) else 1.0
                if (options.rowScaling) for (c in 0 until n) j[row, c] = j[row, c] * rowScale[row]
            }
            j.transposeTimesInto(scaledResidual(), g)
            j.gramInto(gram)
        }

        private val scaled = DoubleArray(m)
        private fun scaledResidual(): DoubleArray { for (i in 0 until m) scaled[i] = r[i] * rowScale[i]; return scaled }
        private fun scaledCost(v: DoubleArray): Double { var s = 0.0; for (i in 0 until m) { val e = v[i] * rowScale[i]; s += e * e }; return s }

        private fun trial(step: DoubleArray) { for (c in 0 until n) xt[cols[c]] = xc[cols[c]] + step[c] }

        private fun accept() { for (c in cols) xc[c] = xt[c]; val t = r; r = rt; rt = t }

        fun levenbergMarquardt(budget: Int, tolerance: Double, clock: Deadline): Outcome {
            var lambda = INITIAL_LAMBDA
            var iter = 0
            xc.copyInto(xt)
            while (iter < budget) {
                if (infNorm(r) <= tolerance) return Outcome(iter, null)
                if (r.hasNonFinite()) return Outcome(iter, StopReason.NonFinite)
                if (wallTimeUp(clock)) return Outcome(iter, StopReason.WallTime)
                iter++
                linearise()
                val cost = scaledCost(r)
                var accepted = false
                var tries = 0
                while (!accepted && tries < MAX_DAMPING_RETRIES) {
                    tries++
                    work.copyFrom(gram)
                    for (i in 0 until n) work[i, i] = gram[i, i] + lambda * max(gram[i, i], DIAGONAL_FLOOR)
                    for (i in 0 until n) rhs[i] = -g[i]
                    if (!chol.factor(work)) { lambda *= LAMBDA_RETRY_GROW; continue }
                    chol.solve(rhs, dx)
                    if (dx.hasNonFinite()) { lambda *= LAMBDA_RETRY_GROW; continue }
                    trial(dx)
                    residuals(xt, rt)
                    val costNew = scaledCost(rt)
                    if (!costNew.isNaN() && costNew < cost) {
                        accept(); lambda = max(lambda / LAMBDA_SHRINK, LAMBDA_FLOOR); accepted = true
                    } else {
                        lambda *= LAMBDA_GROW
                        if (lambda > LAMBDA_CEILING) break
                    }
                }
                if (!accepted) return Outcome(iter, StopReason.Stalled)
            }
            return Outcome(iter, if (infNorm(r) <= tolerance) null else StopReason.IterationBudget)
        }

        fun dogLeg(budget: Int, tolerance: Double, clock: Deadline): Outcome {
            var radius = INITIAL_TRUST_RADIUS_FACTOR * extentScale
            var iter = 0
            xc.copyInto(xt)
            while (iter < budget) {
                if (infNorm(r) <= tolerance) return Outcome(iter, null)
                if (r.hasNonFinite()) return Outcome(iter, StopReason.NonFinite)
                if (wallTimeUp(clock)) return Outcome(iter, StopReason.WallTime)
                iter++
                linearise()
                val cost = scaledCost(r)
                if (!gaussNewton()) return Outcome(iter, StopReason.Stalled)
                steepestDescent()
                var accepted = false
                var tries = 0
                while (!accepted && tries < MAX_DAMPING_RETRIES) {
                    tries++
                    blend(radius)
                    trial(dx)
                    residuals(xt, rt)
                    val costNew = scaledCost(rt)
                    val predicted = cost - predictedCost()
                    val rho = if (predicted > 0.0 && !costNew.isNaN()) (cost - costNew) / predicted else -1.0
                    val stepLen = norm(dx)
                    if (rho > TRUST_ACCEPT_RHO) {
                        accept(); accepted = true
                        if (rho > TRUST_GOOD_RHO) radius = max(radius, TRUST_GROW * stepLen)
                        else if (rho < TRUST_BAD_RHO) radius = max(stepLen * TRUST_SHRINK, TRUST_FLOOR)
                    } else {
                        radius = max(min(radius, stepLen) * TRUST_SHRINK, TRUST_FLOOR)
                        if (radius <= TRUST_FLOOR) break
                    }
                }
                if (!accepted) return Outcome(iter, StopReason.Stalled)
            }
            return Outcome(iter, if (infNorm(r) <= tolerance) null else StopReason.IterationBudget)
        }

        /** Gauss-Newton step from the ridge-regularised normal equations (minimum-norm-like on an under-determined system). */
        private fun gaussNewton(): Boolean {
            var ridge = options.gaussNewtonRidge
            repeat(MAX_DAMPING_RETRIES) {
                work.copyFrom(gram)
                for (i in 0 until n) work[i, i] = gram[i, i] + ridge * max(gram[i, i], diagonalFloor)
                for (i in 0 until n) rhs[i] = -g[i]
                if (chol.factor(work)) {
                    chol.solve(rhs, gn)
                    if (!gn.hasNonFinite()) return true
                }
                ridge *= LAMBDA_RETRY_GROW
            }
            return false
        }

        /** Cauchy point: the minimiser of the quadratic model along `-g`. */
        private fun steepestDescent() {
            for (i in 0 until n) sd[i] = -g[i]
            j.timesInto(sd, jsd)
            var num = 0.0; var den = 0.0
            for (i in 0 until n) num += g[i] * g[i]
            for (i in 0 until m) den += jsd[i] * jsd[i]
            val alpha = if (den > 0.0) num / den else 0.0
            for (i in 0 until n) sd[i] *= alpha
        }

        /** Writes the dogleg step for trust [radius] into [dx]. */
        private fun blend(radius: Double) {
            val gnLen = norm(gn)
            if (gnLen <= radius) { gn.copyInto(dx); return }
            val sdLen = norm(sd)
            if (sdLen >= radius) { for (i in 0 until n) dx[i] = sd[i] * (radius / sdLen); return }
            // Solve |sd + t (gn - sd)| = radius for t in [0, 1].
            var a = 0.0; var b = 0.0; var c = -radius * radius
            for (i in 0 until n) { val d = gn[i] - sd[i]; a += d * d; b += 2.0 * sd[i] * d; c += sd[i] * sd[i] }
            val t = if (a > 0.0) (-b + sqrt(max(b * b - 4.0 * a * c, 0.0))) / (2.0 * a) else 0.0
            for (i in 0 until n) dx[i] = sd[i] + t * (gn[i] - sd[i])
        }

        /** `|r + J dx|^2` in the scaled metric. */
        private fun predictedCost(): Double {
            j.timesInto(dx, jp)
            var s = 0.0
            for (i in 0 until m) { val e = r[i] * rowScale[i] + jp[i]; s += e * e }
            return s
        }

        private fun wallTimeUp(clock: Deadline): Boolean = clock.cancelled() || clock.mark.elapsedNow().inWholeMilliseconds >= options.wallTimeMillis
    }

    private fun infNorm(v: DoubleArray): Double {
        var s = 0.0
        for (a in v) {
            if (a.isNaN()) return Double.NaN
            s = max(s, abs(a))
        }
        return s
    }

    private fun norm(v: DoubleArray): Double { var s = 0.0; for (a in v) s += a * a; return sqrt(s) }
}
