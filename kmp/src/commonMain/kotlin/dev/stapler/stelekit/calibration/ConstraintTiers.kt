// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.FitTier
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** PER_AXIS: two metric scales along the image axes, by linear least squares on squared lengths (optional, off by default). */
internal object PerAxisTier {
    private class Sol(val a: Double, val b: Double)

    fun evaluate(segs: List<Seg>, frame: CaptureFrameInfo): Either<CalibrationFailure, TierEval> {
        val spread = ConstraintCalibrator.orientationSpreadDeg(segs)
        if (segs.size >= 2 && spread < ConstraintTuning.MIN_DIRECTION_SPREAD_DEG) return CalibrationFailure.Collinear(spread).left()
        val need = ConstraintTuning.minConstraints(FitTier.PER_AXIS)
        if (segs.size < need) return CalibrationFailure.TooFewConstraints(need, segs.size).left()
        val sol = solve(segs) ?: return CalibrationFailure.IllConditioned(IllConditionedAspect.NORMAL_MATRIX).left()
        val res = segs.map { FitStatistics.relativeErrorPct(predict(sol, it), it.meters) * if (predict(sol, it) >= it.meters) 1.0 else -1.0 }
        val loo = ConstraintCalibrator.looPct(segs, ::solve, ::predict)
        val wx = sqrt(sol.a)
        val wy = sqrt(sol.b)
        val cx = frame.widthPx / 2.0
        val cy = frame.heightPx / 2.0
        val h = listOf(wx, 0.0, -wx * cx, 0.0, wy, -wy * cy, 0.0, 0.0, 1.0)
        val diag = TierDiagnostics(FitTier.PER_AXIS, res.map { abs(it) }.average(), loo)
        return TierEval(FitTier.PER_AXIS, res, loo, 1.0 / sqrt(wx * wy), h, 0.0, diag).right()
    }

    private fun dx(s: Seg) = s.u2.x - s.u1.x

    private fun dy(s: Seg) = s.u2.y - s.u1.y

    private fun predict(sol: Sol, s: Seg): Double = sqrt(sol.a * dx(s) * dx(s) + sol.b * dy(s) * dy(s))

    /** Rows `[dx^2, dy^2] / D^2 . [a, b] = 1`, weighted by the noise of a squared length. */
    private fun solve(segs: List<Seg>): Sol? {
        val ata = Array(2) { DoubleArray(2) }
        val atr = DoubleArray(2)
        for (s in segs) {
            val row = doubleArrayOf(dx(s) * dx(s) / (s.meters * s.meters), dy(s) * dy(s) / (s.meters * s.meters))
            val w = 1.0 / (4.0 * s.sigma * s.sigma)
            for (i in 0..1) {
                atr[i] += w * row[i]
                for (j in 0..1) ata[i][j] += w * row[i] * row[j]
            }
        }
        if (!FitStatistics.isFullRank(ata)) return null
        val x = HomographyFitter.solve(ata, atr) ?: return null
        return if (x[0] > 0.0 && x[1] > 0.0) Sol(x[0], x[1]) else null
    }
}

/** GRADIENT and GRADIENT_AND_K: the focal-free scale-gradient tilt model behind its identifiability and significance gates. */
internal object GradientTier {
    @Suppress("ReturnCount", "LongMethod")
    fun evaluate(tier: FitTier, segs: List<Seg>, frame: CaptureFrameInfo, options: FitOptions, scaleMpp: Double): Either<CalibrationFailure, TierEval> {
        val n = segs.size
        val need = ConstraintTuning.minConstraints(tier)
        if (n < need) return CalibrationFailure.TooFewConstraints(need, n).left()
        ConstraintCalibrator.cropReason(frame)?.let { return CalibrationFailure.CroppedOrZoomedFrame(it).left() }
        spreadGate(segs, frame)?.let { return it.left() }
        val withK = tier == FitTier.GRADIENT_AND_K
        if (withK) radialGate(segs, frame, options)?.let { return it.left() }

        val half = hypot(frame.widthPx, frame.heightPx) / 2.0
        val trusted = options.focalSource == FocalSource.USER_CONFIRMED || options.focalSource == FocalSource.SIDECAR_LENS_RECORD
        val focalNorm = if (options.focalRefinement && trusted) {
            HeightOffset.focalPixels(options.focal35mmEq, frame.widthPx, frame.heightPx)?.div(half)
        } else {
            null
        }
        val solver = GradientSolver(frame.widthPx, frame.heightPx, withK, focalNorm, options.maxIterations)
        val start = GradSol(ln(scaleMpp * half), 0.0, 0.0, options.k)
        if (!solver.hasFullRank(segs, start)) return CalibrationFailure.IllConditioned(IllConditionedAspect.RANK_DEFICIENT).left()
        val fit = solver.fit(segs, start)
        if (fit == null || !fit.converged) return CalibrationFailure.DidNotConverge.left()
        val sol = fit.sol
        val sig = solver.significance(segs, sol, fit.ssr)
        if (!sig.rankOk) return CalibrationFailure.IllConditioned(IllConditionedAspect.RANK_DEFICIENT).left()
        val threshold = FitStatistics.studentT995(n - ConstraintTuning.parameters(tier))
        if (sig.t <= threshold) return CalibrationFailure.NoMeasurableTilt(sig.t, threshold).left()
        if (withK) {
            val kSe = sig.kSe
            if (kSe == null || abs(sol.k) <= threshold * kSe) return CalibrationFailure.IllConditioned(IllConditionedAspect.NOISE_FLOOR, "k not significant").left()
        }
        val tilt = ConstraintCalibrator.tiltDeg(sol.magnitude, frame)
        if (tilt != null && tilt > ConstraintTuning.MAX_TILT_DEG) return CalibrationFailure.TiltTooLarge(tilt).left()

        val loo = ConstraintCalibrator.looPct(segs, { sub -> solver.fit(sub, sol)?.takeIf { it.converged }?.sol }, solver::predict)
        val res = segs.map { s ->
            val p = solver.predict(sol, s)
            (p - s.meters) / s.meters * FitStatistics.PERCENT
        }
        val bound = if (tilt == null) TiltBound.Unchecked else TiltBound.Within(tilt)
        val diag = TierDiagnostics(tier, res.map { abs(it) }.average(), loo, sig.t, threshold, bound, sol.magnitude, sig.t)
        val ppm = half / exp(sol.lnW0)
        return TierEval(tier, res, loo, ppm, homography(sol, frame, half, focalNorm), sol.k, diag, focalNorm != null).right()
    }

    /** Native undistorted pixels to metres: `diag(A, 1) * [[a,0,-a cx],[0,a,-a cy],[gx/s, gy/s, 1 - (gx cx + gy cy)/s]]`. */
    private fun homography(s: GradSol, frame: CaptureFrameInfo, half: Double, focalNorm: Double?): List<Double> {
        val cx = frame.widthPx / 2.0
        val cy = frame.heightPx / 2.0
        val a = exp(s.lnW0) / half
        val base = listOf(
            a, 0.0, -a * cx,
            0.0, a, -a * cy,
            s.gx / half, s.gy / half, 1.0 - (s.gx * cx + s.gy * cy) / half,
        )
        val g = s.magnitude
        if (focalNorm == null || g <= 0.0) return base
        val q = sqrt(1.0 + (g * focalNorm) * (g * focalNorm)) - 1.0
        val ux = s.gx / g
        val uy = s.gy / g
        val m = listOf(1.0 + q * ux * ux, q * ux * uy, q * ux * uy, 1.0 + q * uy * uy)
        return List(9) { i ->
            val r = i / 3
            val c = i % 3
            if (r < 2) m[r * 2] * base[c] + m[r * 2 + 1] * base[3 + c] else base[i]
        }
    }

    /** Midpoints must span the frame in both axes: the gradient is the scale difference between places. */
    internal fun spreadGate(segs: List<Seg>, frame: CaptureFrameInfo): CalibrationFailure? {
        val diag = hypot(frame.widthPx, frame.heightPx)
        var span = 0.0
        for (i in segs.indices) for (j in i + 1 until segs.size) {
            span = max(span, hypot(segs[i].midX - segs[j].midX, segs[i].midY - segs[j].midY) / diag)
        }
        val mx = segs.map { it.midX }.average()
        val my = segs.map { it.midY }.average()
        val sxx = segs.sumOf { (it.midX - mx) * (it.midX - mx) } / segs.size
        val syy = segs.sumOf { (it.midY - my) * (it.midY - my) } / segs.size
        val sxy = segs.sumOf { (it.midX - mx) * (it.midY - my) } / segs.size
        val tr = sxx + syy
        val minor = sqrt(max(0.0, tr / 2.0 - sqrt(max(0.0, tr * tr / 4.0 - (sxx * syy - sxy * sxy))))) / diag
        return if (span < ConstraintTuning.MIN_POSITION_SPAN_FRACTION || minor < ConstraintTuning.MIN_POSITION_MINOR_SD_FRACTION) {
            CalibrationFailure.IllConditioned(IllConditionedAspect.POSITION_SPREAD)
        } else {
            null
        }
    }

    private fun radialGate(segs: List<Seg>, frame: CaptureFrameInfo, options: FitOptions): CalibrationFailure? {
        val half = hypot(frame.widthPx, frame.heightPx) / 2.0
        fun r(p: dev.stapler.stelekit.model.NormalizedPoint) = hypot((p.x - 0.5) * frame.widthPx, (p.y - 0.5) * frame.heightPx) / half
        val ends = segs.flatMap { listOf(r(it.raw1), r(it.raw2)) }
        if (ends.max() < ConstraintTuning.MIN_RADIAL_REACH) return CalibrationFailure.IllConditioned(IllConditionedAspect.RADIAL_SPREAD)
        val meanR2 = segs.map { (r(it.raw1) * r(it.raw1) + r(it.raw2) * r(it.raw2)) / 2.0 }
        val within = segs.maxOf { abs(r(it.raw1) * r(it.raw1) - r(it.raw2) * r(it.raw2)) }
        val effect = ConstraintTuning.PLAUSIBLE_ABS_K * max(meanR2.max() - meanR2.min(), within)
        val longest = segs.maxOf { it.lengthPx }
        val floor = ConstraintTuning.NOISE_FLOOR_FACTOR * options.tapNoisePx * sqrt(2.0) / longest
        return if (effect < floor) CalibrationFailure.IllConditioned(IllConditionedAspect.NOISE_FLOOR, "k effect below tap noise") else null
    }
}
