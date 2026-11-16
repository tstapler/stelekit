// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.ConstraintResidual
import dev.stapler.stelekit.model.FitReport
import dev.stapler.stelekit.model.FitTier
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One tier's answer on one input, whether or not it is selected. */
class TierEval(
    val tier: FitTier,
    val signedResidualsPct: List<Double>,
    val loo: Double?,
    val pixelsPerMeter: Double,
    val homography: List<Double>?,
    val k: Double,
    val diagnostics: TierDiagnostics,
    val focalApplied: Boolean = false,
) {
    val residualMeanPct: Double get() = signedResidualsPct.map { abs(it) }.average()
}

/**
 * Multi-distance calibration (ADR-003): fits a scale, optionally a focal-free scale gradient (and `k`), from
 * several known distances. Pure and platform-agnostic; leave-one-out error chooses between tiers and no
 * number is produced on degenerate input.
 */
object ConstraintCalibrator {
    private const val MIN_SEGMENT_PX = 1.0
    private const val MIN_CONFLICT_MEMBERS = 2
    private const val CONFLICT_MIN_N = 4
    private const val MIN_ROBUST_N = 3
    private const val ROUND = 10.0
    private const val DEG = 180.0 / kotlin.math.PI

    /** Fits [constraints] on a frame described by [frame]; see [FitOptions] for what is requested. */
    fun fit(constraints: List<CalibrationConstraint>, frame: CaptureFrameInfo, options: FitOptions = FitOptions()): FitResponse {
        val segs = prepare(constraints, frame, options).fold({ return FitResponse(it.left(), null) }, { it })
        val scale = scaleEval(segs, options)
        conflict(segs)?.let { return FitResponse(it.left(), null) }
        val declined = mutableListOf<TierDecline>()
        val shadows = mutableListOf<TierEval>()
        val request = options.request
        val chosen: TierEval = when (request) {
            is TierRequest.Only -> {
                if (request.tier == FitTier.SCALE) {
                    scale
                } else {
                    val eval = evaluate(request.tier, segs, frame, options, scale)
                        .onRight { shadows += it }
                        .flatMap { vetoed(it, scale) }
                    eval.fold(
                        {
                            declined += TierDecline(request.tier, it)
                            val fallback = build(scale, segs, declined, shadows, options, request.tier)
                            return FitResponse(it.left(), fallback)
                        },
                        { it },
                    )
                }
            }
            TierRequest.Auto -> select(segs, frame, options, scale, declined, shadows)
        }
        val fit = build(chosen, segs, declined, shadows, options, null)
        return FitResponse(fit.right(), fit)
    }

    private fun <A, B, C> Either<A, B>.flatMap(f: (B) -> Either<A, C>): Either<A, C> = fold({ it.left() }, f)

    private fun prepare(constraints: List<CalibrationConstraint>, frame: CaptureFrameInfo, options: FitOptions): Either<CalibrationFailure, List<Seg>> {
        if (options.basisVersion == CalibrationBasis.LEGACY_DECODE_RELATIVE) return CalibrationFailure.LegacyBasis.left()
        if (constraints.isEmpty()) return CalibrationFailure.TooFewConstraints(1, 0).left()
        val segs = constraints.map { c ->
            if (!c.distanceMeters.isFinite() || c.distanceMeters <= 0.0) return CalibrationFailure.NonPositiveDistance(c.id).left()
            val u1 = RadialLens.undistort(c.p1.x, c.p1.y, options.k, frame.widthPx, frame.heightPx)
            val u2 = RadialLens.undistort(c.p2.x, c.p2.y, options.k, frame.widthPx, frame.heightPx)
            val len = hypot(u2.x - u1.x, u2.y - u1.y)
            if (len < MIN_SEGMENT_PX) return CalibrationFailure.ZeroLengthSegment(c.id).left()
            val tap = options.tapNoisePx * sqrt(2.0) / len
            val sigma = sqrt(tap * tap + ConstraintTuning.RELATIVE_NOISE_FLOOR * ConstraintTuning.RELATIVE_NOISE_FLOOR)
            Seg(c.id, c.distanceMeters, c.p1, c.p2, u1, u2, sigma)
        }
        return segs.right()
    }

    // ---- SCALE ----

    /** Noise-weighted log-mean of the scales that agree with the median within [ConstraintTuning.TRIM_RATIO]. */
    private fun scaleModel(segs: List<Seg>): Double {
        val logs = segs.map { ln(it.meters / it.lengthPx) }
        val centre = if (segs.size >= MIN_ROBUST_N) FitStatistics.median(logs) else null
        val limit = ln(ConstraintTuning.TRIM_RATIO)
        var sw = 0.0
        var s = 0.0
        for ((i, g) in segs.withIndex()) {
            if (centre != null && abs(logs[i] - centre) > limit) continue
            val w = 1.0 / (g.sigma * g.sigma)
            sw += w
            s += w * logs[i]
        }
        return if (sw > 0.0) exp(s / sw) else exp(logs.average())
    }

    private fun scaleEval(segs: List<Seg>, options: FitOptions): TierEval {
        val m = scaleModel(segs)
        val res = segs.map { (m * it.lengthPx - it.meters) / it.meters * FitStatistics.PERCENT }
        val loo = if (segs.size >= 3) looPct(segs, ::scaleModel) { mm, g -> mm * g.lengthPx } else null
        val diag = TierDiagnostics(FitTier.SCALE, res.map { abs(it) }.average(), loo)
        return TierEval(FitTier.SCALE, res, loo, 1.0 / m, null, options.k, diag)
    }

    internal fun <S> looPct(segs: List<Seg>, fitFn: (List<Seg>) -> S?, predict: (S, Seg) -> Double): Double {
        var total = 0.0
        for (i in segs.indices) {
            val sol = fitFn(segs.filterIndexed { j, _ -> j != i }) ?: return Double.POSITIVE_INFINITY
            val p = predict(sol, segs[i])
            if (!p.isFinite()) return Double.POSITIVE_INFINITY
            total += FitStatistics.relativeErrorPct(p, segs[i].meters)
        }
        return total / segs.size
    }

    // ---- tier dispatch ----

    private fun enabled(tier: FitTier, options: FitOptions) = when (tier) {
        FitTier.SCALE -> true
        FitTier.PER_AXIS -> options.perAxisEnabled
        FitTier.GRADIENT -> options.gradientEnabled
        FitTier.GRADIENT_AND_K -> options.gradientEnabled && options.kTierEnabled
    }

    private fun evaluate(tier: FitTier, segs: List<Seg>, frame: CaptureFrameInfo, options: FitOptions, scale: TierEval): Either<CalibrationFailure, TierEval> =
        when (tier) {
            FitTier.SCALE -> scale.right()
            FitTier.PER_AXIS -> PerAxisTier.evaluate(segs, frame)
            FitTier.GRADIENT, FitTier.GRADIENT_AND_K -> GradientTier.evaluate(tier, segs, frame, options, scaleModel(segs))
        }

    private fun vetoed(eval: TierEval, simpler: TierEval): Either<CalibrationFailure, TierEval> {
        val loo = eval.loo ?: return CalibrationFailure.NotBetterThanSimpler(Double.NaN, simpler.loo ?: Double.NaN).left()
        val base = simpler.loo ?: return eval.right()
        return if (loo <= (1.0 - ConstraintTuning.LOO_MARGIN) * base) eval.right() else CalibrationFailure.NotBetterThanSimpler(loo, base).left()
    }

    private fun select(segs: List<Seg>, frame: CaptureFrameInfo, options: FitOptions, scale: TierEval, declined: MutableList<TierDecline>, shadows: MutableList<TierEval>): TierEval {
        var best = scale
        val tiers = buildList {
            if (options.perAxisEnabled) add(FitTier.PER_AXIS)
            add(FitTier.GRADIENT)
            if (options.kTierEnabled) add(FitTier.GRADIENT_AND_K)
        }
        for (tier in tiers) {
            val eval = evaluate(tier, segs, frame, options, scale)
            val e = eval.fold({ declined += TierDecline(tier, it); null }, { it }) ?: continue
            shadows += e
            if (!enabled(tier, options)) {
                declined += TierDecline(tier, CalibrationFailure.TierDisabled(tier))
                continue
            }
            vetoed(e, best).fold({ declined += TierDecline(tier, it) }, { best = it })
        }
        return best
    }

    // ---- conflicts and reporting ----

    /** A single distance more than [ConstraintTuning.CONFLICT_RATIO] off while all the others agree with each other. */
    private fun lone(segs: List<Seg>): CalibrationFailure.ConflictingConstraints? {
        if (segs.size < MIN_ROBUST_N) return null
        val logs = segs.map { ln(it.meters / it.lengthPx) }
        val limit = ln(ConstraintTuning.CONFLICT_RATIO)
        for (i in segs.indices) {
            val rest = logs.filterIndexed { j, _ -> j != i }
            if ((rest.max() - rest.min()) >= limit) continue
            val off = abs(logs[i] - FitStatistics.median(rest))
            if (off >= limit) return CalibrationFailure.ConflictingConstraints(segs[i].id, exp(off))
        }
        return null
    }

    /** Two distances that disagree by [ConstraintTuning.CONFLICT_RATIO] or more cannot say which is right, so neither is trusted. */
    private fun pair(segs: List<Seg>): CalibrationFailure.ConflictingConstraints? {
        if (segs.size != 2) return null
        val ratio = exp(abs(ln(segs[0].meters / segs[0].lengthPx) - ln(segs[1].meters / segs[1].lengthPx)))
        return if (ratio >= ConstraintTuning.CONFLICT_RATIO) CalibrationFailure.ConflictingConstraints(segs[1].id, ratio) else null
    }

    private fun conflict(segs: List<Seg>): CalibrationFailure.ConflictingConstraints? {
        pair(segs)?.let { return it }
        lone(segs)?.let { return it }
        if (segs.size < CONFLICT_MIN_N) return null
        val sorted = segs.map { it to ln(it.meters / it.lengthPx) }.sortedBy { it.second }
        var bestGap = 0.0
        var at = -1
        for (i in 1 until sorted.size) {
            val gap = sorted[i].second - sorted[i - 1].second
            if (gap > bestGap && i >= MIN_CONFLICT_MEMBERS && sorted.size - i >= MIN_CONFLICT_MEMBERS) {
                bestGap = gap
                at = i
            }
        }
        val ratio = exp(bestGap)
        return if (at > 0 && ratio >= ConstraintTuning.CONFLICT_RATIO) {
            CalibrationFailure.ConflictingConstraints(sorted[at].first.id, ratio)
        } else {
            null
        }
    }

    private fun build(chosen: TierEval, segs: List<Seg>, declined: List<TierDecline>, shadows: List<TierEval>, options: FitOptions, requested: FitTier?): ConstraintFit {
        val n = segs.size
        val ratios = chosen.signedResidualsPct.map { 1.0 / (1.0 + it / FitStatistics.PERCENT) }
        val median = FitStatistics.median(ratios)
        val perConstraint = segs.indices.map { i ->
            ConstraintResidual(segs[i].id, chosen.signedResidualsPct[i], ratios[i] / median)
        }
        val worst = if (n >= 3) perConstraint.maxByOrNull { abs(ln(it.impliedScaleRatio)) } else null
        val over = chosen.signedResidualsPct.count { it > ConstraintTuning.MIXED_SURFACE_PCT }
        val under = chosen.signedResidualsPct.count { it < -ConstraintTuning.MIXED_SURFACE_PCT }
        val agreement = if (n == 2) {
            val s1 = segs[0].meters / segs[0].lengthPx
            val s2 = segs[1].meters / segs[1].lengthPx
            abs(s1 - s2) / ((s1 + s2) / 2.0) * FitStatistics.PERCENT
        } else {
            null
        }
        val reason = requested?.let { t -> declined.firstOrNull { it.tier == t }?.failure?.describe()?.let { "${t.name}: $it" } }
        val report = FitReport(
            tier = chosen.tier, n = n, residualMeanPct = chosen.residualMeanPct, looPct = chosen.loo,
            agreementPct = agreement, perConstraint = perConstraint, worst = worst,
            mixedSurfaces = over >= MIN_CONFLICT_MEMBERS && under >= MIN_CONFLICT_MEMBERS, fallbackReason = reason,
        )
        val diagnostics = listOf(chosen.diagnostics) + shadows.map { it.diagnostics }.filter { it.tier != chosen.tier }
        val notes = declined.filter { enabledOrRequested(it.tier, options, requested) && it.failure !is CalibrationFailure.TierDisabled }
            .map { "${it.tier.name} ${it.failure.describe()}" }
        return ConstraintFit(
            report, chosen.pixelsPerMeter, chosen.homography, chosen.k, diagnostics, declined.toList(),
            options.focalSource, chosen.focalApplied, notes,
        )
    }

    private fun enabledOrRequested(tier: FitTier, options: FitOptions, requested: FitTier?) = tier == requested || enabled(tier, options)

    internal fun roundTenth(v: Double) = kotlin.math.round(v * ROUND) / ROUND

    /** Why [frame] cannot carry the focal-free tilt model, or null when it can (ADR-003 crop and zoom rule). */
    fun cropReason(frame: CaptureFrameInfo): CropReason? {
        val zoom = frame.zoomRatio
        if (zoom != null && abs(zoom - 1.0) > ZOOM_EPSILON) return CropReason.ZOOM_RATIO
        val digital = frame.exifDigitalZoomRatio
        if (digital != null && digital > 1.0 + ZOOM_EPSILON) return CropReason.EXIF_DIGITAL_ZOOM
        val aspect = max(frame.widthPx, frame.heightPx) / min(frame.widthPx, frame.heightPx)
        val near = listOf(4.0 / 3.0, 3.0 / 2.0).any { abs(aspect - it) / it <= ConstraintTuning.ASPECT_TOLERANCE }
        return if (near) null else CropReason.ASPECT
    }

    private const val ZOOM_EPSILON = 1e-3

    internal fun tiltDeg(gradientPerHalf: Double, frame: CaptureFrameInfo): Double? {
        val f = HeightOffset.focalPixels(frame.exifFocal35mmEq, frame.widthPx, frame.heightPx) ?: return null
        val half = hypot(frame.widthPx, frame.heightPx) / 2.0
        return atan(gradientPerHalf / half * f) * DEG
    }

    internal fun orientationSpreadDeg(segs: List<Seg>): Double {
        if (segs.size < 2) return 0.0
        val a = segs.map { s ->
            val t = atan2(s.u2.y - s.u1.y, s.u2.x - s.u1.x)
            ((t % kotlin.math.PI) + kotlin.math.PI) % kotlin.math.PI
        }.sorted()
        var maxGap = a.first() + kotlin.math.PI - a.last()
        for (i in 1 until a.size) maxGap = max(maxGap, a[i] - a[i - 1])
        return (kotlin.math.PI - maxGap) * DEG
    }
}
