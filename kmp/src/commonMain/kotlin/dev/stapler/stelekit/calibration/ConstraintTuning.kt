// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.FitTier

/**
 * Thresholds of the constraint calibrator (ADR-003 decision 5). Provisional until the Story 3.8 real-photo
 * study; every value that can move lives here so the study changes one file.
 */
object ConstraintTuning {
    /** GRADIENT ships off until the real-photo study shows a held-out gain (ADR-003, pre-mortem P1-5). */
    const val GRADIENT_DEFAULT_ON = false

    /** Settings key (Boolean) that lets the owner try the GRADIENT tier before the default flips. */
    const val SETTINGS_KEY_GRADIENT_TIER = "calibration_gradient_tier"

    /** The k-estimating tier is separately opt-in: its noise floor is near typical tap error (ADR-003). */
    const val K_TIER_DEFAULT_ON = false

    /** PER_AXIS has no physical source with square pixels; it is never auto-selected unless enabled. */
    const val PER_AXIS_DEFAULT_ON = false

    /** The stored Settings value wins; a missing key falls back to [GRADIENT_DEFAULT_ON]. */
    fun gradientEnabled(stored: Boolean?): Boolean = stored ?: GRADIENT_DEFAULT_ON

    /** Assumed per-tap error in image pixels; scales each constraint's prior noise by its pixel length. */
    const val TAP_NOISE_PX = 1.0

    /** Per-constraint relative error that no tap precision removes (ADR-003: 0.3 percent per segment). */
    const val RELATIVE_NOISE_FLOOR = 0.003

    /** A richer tier must cut the simpler tier's leave-one-out error by this fraction to be chosen. */
    const val LOO_MARGIN = 0.2

    /** Midpoints must span this share of the image diagonal for a gradient to be identifiable. */
    const val MIN_POSITION_SPAN_FRACTION = 0.4

    /** Standard deviation of midpoints across the minor axis, as a share of the diagonal; below it they are one line. */
    const val MIN_POSITION_MINOR_SD_FRACTION = 0.04

    /** At least one endpoint must lie beyond this radius (1 = image corner) for the k tier. */
    const val MIN_RADIAL_REACH = 0.5

    /** Largest k considered plausible when asking whether k could be seen above tap noise. */
    const val PLAUSIBLE_ABS_K = 0.03

    /** The predicted effect of [PLAUSIBLE_ABS_K] must exceed this multiple of the tap error. */
    const val NOISE_FLOOR_FACTOR = 2.0

    /** Orientation range (degrees) segments must cover for PER_AXIS. */
    const val MIN_DIRECTION_SPREAD_DEG = 20.0

    /** The EXIF-bounded implied tilt beyond which the focal-free model is not trusted. */
    const val MAX_TILT_DEG = 10.0

    /** Residuals beyond this percent, with at least two on each side, suggest two surfaces. */
    const val MIXED_SURFACE_PCT = 3.0

    /** A scale this many times off the median is flagged as a probable unit or typo error. */
    const val CONFLICT_RATIO = 2.0

    /** Image aspect (long / short) must be 4:3 or 3:2 within this fraction or the frame is treated as cropped. */
    const val ASPECT_TOLERANCE = 0.02

    const val MAX_ITERATIONS = 80
    const val SIGNIFICANCE_CONFIDENCE = 0.995

    /** Parameters per tier (SCALE `w0`; PER_AXIS `wx, wy`; GRADIENT `w0, gx, gy`; plus `k`). */
    fun parameters(tier: FitTier): Int = when (tier) {
        FitTier.SCALE -> 1
        FitTier.PER_AXIS -> 2
        FitTier.GRADIENT -> 3
        FitTier.GRADIENT_AND_K -> 4
    }

    /** Constraints a tier needs: two spare degrees of freedom beyond its parameters (SCALE needs one). */
    fun minConstraints(tier: FitTier): Int = if (tier == FitTier.SCALE) 1 else parameters(tier) + 2
}
