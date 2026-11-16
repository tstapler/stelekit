// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/** One known distance prepared for fitting: raw taps, taps undistorted with the current `k`, and a noise prior. */
class Seg(
    val id: String,
    val meters: Double,
    val raw1: NormalizedPoint,
    val raw2: NormalizedPoint,
    val u1: PxPoint,
    val u2: PxPoint,
    /** Relative (log-length) noise prior: tap precision over the pixel length, plus a floor tap precision cannot remove. */
    val sigma: Double,
) {
    val lengthPx: Double = hypot(u2.x - u1.x, u2.y - u1.y)
    val midX: Double = (u1.x + u2.x) / 2.0
    val midY: Double = (u1.y + u2.y) / 2.0
}

/** Parameters of the focal-free tilt model `w0 * p / (1 + g . p)` in half-diagonal units; [k] only for the k tier. */
class GradSol(val lnW0: Double, val gx: Double, val gy: Double, val k: Double) {
    val magnitude: Double get() = hypot(gx, gy)
}

class LmResult(val sol: GradSol, val converged: Boolean, val ssr: Double, val iterations: Int)

/**
 * Standard errors of the gradient along its own direction and of `k`, plus the Wald distance `sqrt(g' S^-1 g)` of
 * the 2-D gradient from zero under its 2x2 covariance block. [t] is that distance, to be compared with
 * [FitStatistics.gradientGate] (a 1-D t threshold does not control the false-positive rate of a 2-D magnitude).
 */
class GradSignificance(val gradient: Double, val gradientSe: Double, val kSe: Double?, val rankOk: Boolean, val wald: Double = 0.0) {
    val t: Double get() = wald
}

/**
 * Levenberg-Marquardt over `(ln w0, gx, gy[, k])` with a numeric Jacobian (ADR-003 decision 5). Residuals are
 * relative log-length errors divided by each constraint's noise prior. No focal length enters the base model.
 */
class GradientSolver(
    private val widthPx: Double,
    private val heightPx: Double,
    private val withK: Boolean,
    /** Focal length in half-diagonal units when the exact `1/cos(tau)` refinement applies, else null. */
    private val focalNorm: Double?,
    private val maxIterations: Int,
) {
    val half: Double = hypot(widthPx, heightPx) / 2.0
    private val parameters = if (withK) 4 else 3

    private fun theta(s: GradSol) = if (withK) doubleArrayOf(s.lnW0, s.gx, s.gy, s.k) else doubleArrayOf(s.lnW0, s.gx, s.gy)

    private fun sol(t: DoubleArray, base: GradSol) =
        GradSol(t[0], t[1], t[2], if (withK) t[3].coerceIn(-RadialLens.MAX_ABS_K, RadialLens.MAX_ABS_K) else base.k)

    private fun points(s: GradSol, seg: Seg): Pair<PxPoint, PxPoint> =
        if (withK) {
            RadialLens.undistort(seg.raw1.x, seg.raw1.y, s.k, widthPx, heightPx) to
                RadialLens.undistort(seg.raw2.x, seg.raw2.y, s.k, widthPx, heightPx)
        } else {
            seg.u1 to seg.u2
        }

    private fun map(s: GradSol, p: PxPoint): Pair<Double, Double>? {
        val x = (p.x - widthPx / 2.0) / half
        val y = (p.y - heightPx / 2.0) / half
        val den = 1.0 + s.gx * x + s.gy * y
        if (den < MIN_DENOMINATOR) return null
        val w0 = exp(s.lnW0)
        return w0 * x / den to w0 * y / den
    }

    /** Metric length of [seg] under [s]; NaN when a tap maps to the horizon. */
    fun predict(s: GradSol, seg: Seg): Double {
        val (a, b) = points(s, seg)
        val ma = map(s, a) ?: return Double.NaN
        val mb = map(s, b) ?: return Double.NaN
        var dx = ma.first - mb.first
        var dy = ma.second - mb.second
        val g = s.magnitude
        if (focalNorm != null && g > 0.0) {
            val q = sqrt(1.0 + (g * focalNorm) * (g * focalNorm))
            val par = (dx * s.gx + dy * s.gy) / g
            dx += (q - 1.0) * par * s.gx / g
            dy += (q - 1.0) * par * s.gy / g
        }
        return hypot(dx, dy)
    }

    private fun residuals(s: GradSol, segs: List<Seg>): DoubleArray? {
        val r = DoubleArray(segs.size)
        for (i in segs.indices) {
            val p = predict(s, segs[i])
            if (!p.isFinite() || p <= 0.0) return null
            r[i] = (ln(p) - ln(segs[i].meters)) / segs[i].sigma
        }
        return r
    }

    private fun cost(r: DoubleArray?) = r?.sumOf { it * it } ?: Double.POSITIVE_INFINITY

    private fun jacobian(s: GradSol, segs: List<Seg>): Array<DoubleArray>? {
        val t = theta(s)
        val j = Array(segs.size) { DoubleArray(parameters) }
        for (c in 0 until parameters) {
            val up = t.copyOf().also { it[c] += STEP }
            val dn = t.copyOf().also { it[c] -= STEP }
            val ru = residuals(sol(up, s), segs) ?: return null
            val rd = residuals(sol(dn, s), segs) ?: return null
            for (i in segs.indices) j[i][c] = (ru[i] - rd[i]) / (2.0 * STEP)
        }
        return j
    }

    private fun gram(j: Array<DoubleArray>): Array<DoubleArray> =
        Array(parameters) { a -> DoubleArray(parameters) { b -> j.sumOf { it[a] * it[b] } } }

    /** Rank of the Jacobian at the scale-only start point; false means the layout cannot determine the parameters. */
    fun hasFullRank(segs: List<Seg>, start: GradSol): Boolean {
        val j = jacobian(start, segs) ?: return false
        return FitStatistics.isFullRank(gram(j))
    }

    /** Fits from [start]; null when the model is undefined at the start. */
    fun fit(segs: List<Seg>, start: GradSol): LmResult? {
        var cur = start
        var r = residuals(cur, segs) ?: return null
        var c = cost(r)
        var lambda = LAMBDA0
        var converged = c < ZERO_COST
        var iterations = 0
        while (iterations < maxIterations && !converged) {
            iterations++
            val j = jacobian(cur, segs) ?: return null
            val a = gram(j)
            val grad = DoubleArray(parameters) { col -> j.indices.sumOf { j[it][col] * r[it] } }
            val trial = step(cur, a, grad, lambda, segs)
            if (trial != null && trial.second < c) {
                val rel = (c - trial.second) / (c + TINY)
                val moved = DoubleArray(parameters).also { d -> theta(trial.first).forEachIndexed { i, v -> d[i] = v - theta(cur)[i] } }
                cur = trial.first
                c = trial.second
                r = residuals(cur, segs) ?: return null
                lambda = (lambda / LAMBDA_DOWN).coerceAtLeast(LAMBDA_MIN)
                converged = rel < REL_TOL || moved.all { abs(it) < STEP_TOL } || c < ZERO_COST
            } else {
                lambda *= LAMBDA_UP
                if (lambda > LAMBDA_MAX) {
                    // No damped step improves the cost any more: the iterate sits at the minimum up to round-off.
                    converged = true
                    break
                }
            }
        }
        return LmResult(cur, converged, c, iterations)
    }

    private fun step(cur: GradSol, a: Array<DoubleArray>, grad: DoubleArray, lambda: Double, segs: List<Seg>): Pair<GradSol, Double>? {
        val damped = Array(parameters) { i -> DoubleArray(parameters) { j -> a[i][j] + if (i == j) lambda * (a[i][i] + TINY) else 0.0 } }
        val delta = HomographyFitter.solve(damped, DoubleArray(parameters) { -grad[it] }) ?: return null
        val t = theta(cur)
        val next = sol(DoubleArray(parameters) { t[it] + delta[it] }, cur)
        val cNext = cost(residuals(next, segs))
        return if (cNext.isFinite()) next to cNext else null
    }

    /** Covariance at [s] scaled by `max(1, SSR / (N - P))`: the prior noise is a floor, never an excuse for confidence. */
    fun significance(segs: List<Seg>, s: GradSol, ssr: Double): GradSignificance {
        val j = jacobian(s, segs) ?: return GradSignificance(s.magnitude, Double.POSITIVE_INFINITY, null, false)
        val a = gram(j)
        if (!FitStatistics.isFullRank(a)) return GradSignificance(s.magnitude, Double.POSITIVE_INFINITY, null, false)
        val inv = FitStatistics.invert(a) ?: return GradSignificance(s.magnitude, Double.POSITIVE_INFINITY, null, false)
        val dof = (segs.size - parameters).coerceAtLeast(1)
        val scale = (ssr / dof).coerceAtLeast(1.0)
        val g = s.magnitude
        if (g <= 0.0) return GradSignificance(0.0, sqrt(scale * inv[1][1]), if (withK) sqrt(scale * inv[3][3]) else null, true)
        val ux = s.gx / g
        val uy = s.gy / g
        val variance = scale * (ux * ux * inv[1][1] + 2.0 * ux * uy * inv[1][2] + uy * uy * inv[2][2])
        val det = inv[1][1] * inv[2][2] - inv[1][2] * inv[1][2]
        val quad = inv[2][2] * s.gx * s.gx - 2.0 * inv[1][2] * s.gx * s.gy + inv[1][1] * s.gy * s.gy
        val wald = if (det > 0.0) sqrt((quad / (scale * det)).coerceAtLeast(0.0)) else 0.0
        return GradSignificance(g, sqrt(variance.coerceAtLeast(0.0)), if (withK) sqrt(scale * inv[3][3]) else null, true, wald)
    }

    private companion object {
        const val STEP = 1e-6
        const val MIN_DENOMINATOR = 0.05
        const val LAMBDA0 = 1e-3
        const val LAMBDA_DOWN = 3.0
        const val LAMBDA_UP = 5.0
        const val LAMBDA_MIN = 1e-12
        const val LAMBDA_MAX = 1e12
        const val REL_TOL = 1e-12
        const val STEP_TOL = 1e-10
        const val ZERO_COST = 1e-10
        const val TINY = 1e-30
    }
}
