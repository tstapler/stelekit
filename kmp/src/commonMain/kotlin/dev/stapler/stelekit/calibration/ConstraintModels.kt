// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import arrow.core.Either
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.FitReport
import dev.stapler.stelekit.model.FitTier

/** Which identifiability aspect a tier's design failed on. */
enum class IllConditionedAspect { POSITION_SPREAD, RADIAL_SPREAD, NOISE_FLOOR, RANK_DEFICIENT, NORMAL_MATRIX }

/** Why a frame cannot carry the focal-free tilt model. */
enum class CropReason { ZOOM_RATIO, EXIF_DIGITAL_ZOOM, ASPECT }

/** Every way a constraint fit declines to produce a number (ADR-003 decision 4); never a number on degenerate input. */
sealed interface CalibrationFailure {
    data class TooFewConstraints(val need: Int, val have: Int) : CalibrationFailure

    /** Segments are all (nearly) parallel; [angleSpreadDeg] is the orientation range they cover. */
    data class Collinear(val angleSpreadDeg: Double) : CalibrationFailure

    data class ZeroLengthSegment(val id: String) : CalibrationFailure

    data class NonPositiveDistance(val id: String) : CalibrationFailure

    /** Two groups of constraints imply scales more than 2x apart, so no single surface fits. */
    data class ConflictingConstraints(val worstId: String, val impliedScaleRatio: Double) : CalibrationFailure

    data class IllConditioned(val aspect: IllConditionedAspect, val detail: String? = null) : CalibrationFailure

    /** The gradient is not distinguishable from tap noise at the requested confidence. */
    data class NoMeasurableTilt(val tStatistic: Double, val threshold: Double) : CalibrationFailure

    data object DidNotConverge : CalibrationFailure

    /** A basis-0 (legacy) calibration is never rescaled. */
    data object LegacyBasis : CalibrationFailure

    data class CroppedOrZoomedFrame(val reason: CropReason) : CalibrationFailure

    /** The EXIF-bounded implied tilt exceeds the model's validity range. */
    data class TiltTooLarge(val impliedTiltDeg: Double) : CalibrationFailure

    /** The richer tier did not beat the simpler tier's leave-one-out error by the margin (the LOO veto). */
    data class NotBetterThanSimpler(val looPct: Double, val simplerLooPct: Double) : CalibrationFailure

    /** The tier is switched off (GRADIENT ships default-off). */
    data class TierDisabled(val tier: FitTier) : CalibrationFailure
}

/** User-facing sentence for a failure, used in the fit report and the pill. */
fun CalibrationFailure.describe(): String = when (this) {
    is CalibrationFailure.TooFewConstraints -> "needs ${need - have} more distance${if (need - have == 1) "" else "s"}"
    is CalibrationFailure.Collinear -> "distances all point the same way"
    is CalibrationFailure.ZeroLengthSegment -> "a known distance has both ends on the same point"
    is CalibrationFailure.NonPositiveDistance -> "a known distance must be positive"
    is CalibrationFailure.ConflictingConstraints -> "distances disagree by about ${kotlin.math.round(impliedScaleRatio * 10) / 10}x"
    is CalibrationFailure.IllConditioned -> "distances are not spread enough (${aspect.name.lowercase().replace('_', ' ')})"
    is CalibrationFailure.NoMeasurableTilt -> "no measurable tilt"
    CalibrationFailure.DidNotConverge -> "the fit did not converge"
    CalibrationFailure.LegacyBasis -> "calibrated with an older method"
    is CalibrationFailure.CroppedOrZoomedFrame -> "the photo looks cropped or zoomed"
    is CalibrationFailure.TiltTooLarge -> "tilt too large for this model"
    is CalibrationFailure.NotBetterThanSimpler -> "not better than a single scale"
    is CalibrationFailure.TierDisabled -> "${tier.name} is switched off"
}

/**
 * What the calibrator knows about the frame. The EXIF focal is an order-of-magnitude check on tilt only;
 * [zoomRatio] comes from the sidecar lens record and [exifDigitalZoomRatio] from the EXIF tag.
 */
data class CaptureFrameInfo(
    val widthPx: Double,
    val heightPx: Double,
    val exifFocal35mmEq: Double? = null,
    val zoomRatio: Double? = null,
    val exifDigitalZoomRatio: Double? = null,
)

/** Which tier to produce: the best one the evidence supports, or exactly one. */
sealed interface TierRequest {
    data object Auto : TierRequest

    data class Only(val tier: FitTier) : TierRequest
}

/** Knobs of one fit. Defaults are the shipped behaviour: SCALE with leave-one-out, richer tiers off. */
data class FitOptions(
    val request: TierRequest = TierRequest.Auto,
    val gradientEnabled: Boolean = ConstraintTuning.GRADIENT_DEFAULT_ON,
    val kTierEnabled: Boolean = ConstraintTuning.K_TIER_DEFAULT_ON,
    val perAxisEnabled: Boolean = ConstraintTuning.PER_AXIS_DEFAULT_ON,
    /** The calibration's current division `k`; taps are undistorted with it before every tier but GRADIENT_AND_K. */
    val k: Double = 0.0,
    val basisVersion: Int = CalibrationBasis.NATIVE_PIXELS_LENS,
    val tapNoisePx: Double = ConstraintTuning.TAP_NOISE_PX,
    /** Exact `1/cos(tau)` foreshortening for GRADIENT; ships off and only applies for a trusted [focalSource]. */
    val focalRefinement: Boolean = false,
    val focalSource: FocalSource = FocalSource.EXIF_ONLY,
    /** 35 mm-equivalent focal that [focalSource] vouches for (the EXIF value when it is [FocalSource.EXIF_ONLY]). */
    val focal35mmEq: Double? = null,
    val maxIterations: Int = ConstraintTuning.MAX_ITERATIONS,
)

/** How a tier's implied tilt compares with the EXIF-bounded validity range. */
sealed interface TiltBound {
    /** No EXIF focal, so the bound cannot be checked. */
    data object Unchecked : TiltBound

    data class Within(val impliedTiltDeg: Double) : TiltBound
}

/** What was learned about one tier on this input, whether or not it was selected (logged for the study). */
data class TierDiagnostics(
    val tier: FitTier,
    val residualMeanPct: Double,
    val looPct: Double?,
    /** Gradient magnitude over its standard error along the gradient direction; null for non-gradient tiers. */
    val tStatistic: Double? = null,
    val tThreshold: Double? = null,
    val tiltBound: TiltBound? = null,
    /** Fitted gradient magnitude per normalised unit (half-diagonal), when the tier has one. */
    val gradient: Double? = null,
    /** Gradient over its standard error, a signal-to-noise figure for the study. */
    val snr: Double? = null,
)

/** A tier that was not used and why. */
data class TierDecline(val tier: FitTier, val failure: CalibrationFailure)

/** One successful fit. [homography] maps undistorted native pixels to metres for the non-SCALE tiers. */
data class ConstraintFit(
    val report: FitReport,
    /** Scale at the image centre in undistorted native pixels per metre. */
    val pixelsPerMeter: Double,
    val homography: List<Double>?,
    /** The `k` the fit assumes: the input `k`, or the estimated one for GRADIENT_AND_K. */
    val k: Double,
    val diagnostics: List<TierDiagnostics>,
    val declined: List<TierDecline>,
    val focalSource: FocalSource,
    val focalRefinementApplied: Boolean,
    /** Plain-language lines for the report ("GRADIENT needs 1 more distance"). */
    val notes: List<String>,
) {
    val tier: FitTier get() = report.tier
}

/**
 * Answer to a fit request. [requested] is exactly what was asked for; [effective] is it when it succeeded and
 * otherwise the SCALE fit (null when even SCALE cannot be computed), so a caller can keep working on a decline.
 */
data class FitResponse(val requested: Either<CalibrationFailure, ConstraintFit>, val effective: ConstraintFit?)
