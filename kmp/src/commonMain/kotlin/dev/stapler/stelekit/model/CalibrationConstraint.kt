// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.model

import dev.stapler.stelekit.calibration.FocalSource
import kotlinx.serialization.Serializable

/** Where a [CalibrationConstraint] came from. */
@Serializable
enum class ConstraintSource {
    /** Drawn through "Draw a known distance". */
    DRAWN,

    /** Promoted from an existing measurement via "Use as known distance" (ADR-002). */
    FROM_MEASUREMENT,
}

/** A length the user knows, between two points of the photo, used to fit the photo's scale (ADR-003). */
@Serializable
data class CalibrationConstraint(
    val id: String,
    val p1: NormalizedPoint,
    val p2: NormalizedPoint,
    val distanceMeters: Double,
    val label: String? = null,
    val source: ConstraintSource = ConstraintSource.DRAWN,
    /** The measurement this constraint was promoted from, when [source] is [ConstraintSource.FROM_MEASUREMENT]. */
    val measurementUuid: String? = null,
)

/** Model tier a constraint fit used; stored as a name, so values are append-only. */
@Serializable
enum class FitTier { SCALE, PER_AXIS, GRADIENT, GRADIENT_AND_K }

/** Why a plane exists: a tapped paper/card rectangle, or a fit from known distances (ADR-003 decision 3). */
@Serializable
enum class PlaneKind { PAPER_REFERENCE, CONSTRAINT_FIT }

/**
 * Snapshot of the basis a constraint fit replaced, so deleting the last constraint restores it exactly
 * (ADR-003 decision 1). [lens] carries `k`, which only the GRADIENT_AND_K tier overwrites.
 */
@Serializable
data class PriorBasis(
    val method: CalibrationMethod = CalibrationMethod.NONE,
    val pixelsPerMeter: Double,
    val confidencePercent: Int = 0,
    val plane: PlaneCalibration? = null,
    val lens: LensCalibration? = null,
)

/** What the last fit produced, kept beside the constraints so the pill can say how far to trust the scale. */
@Serializable
data class ConstraintFitRecord(
    val tier: FitTier = FitTier.SCALE,
    val n: Int,
    val residualMeanPct: Double,
    /** Leave-one-out error in percent; null for N below 3, where it is undefined. */
    val looPct: Double? = null,
    val worstId: String? = null,
    val focalSource: FocalSource = FocalSource.EXIF_ONLY,
    val priorBasis: PriorBasis? = null,
)

/** Residual of one constraint against the fit. [impliedScaleRatio] is its scale divided by the fitted scale. */
@Serializable
data class ConstraintResidual(
    val id: String,
    val residualPct: Double,
    val impliedScaleRatio: Double = 1.0,
)

/** Full result of a fit, for the sheet and the logs; the persisted part is [ConstraintFitRecord]. */
@Serializable
data class FitReport(
    val tier: FitTier,
    val n: Int,
    val residualMeanPct: Double,
    val looPct: Double? = null,
    /** `|s1 - s2| / mean` when exactly two constraints exist; null otherwise. */
    val agreementPct: Double? = null,
    val perConstraint: List<ConstraintResidual> = emptyList(),
    val worst: ConstraintResidual? = null,
    /** Residuals split into two groups beyond 3 percent, which suggests different surfaces. */
    val mixedSurfaces: Boolean = false,
    /** A tier that was requested but not used, and why; null when the requested tier was used. */
    val fallbackReason: String? = null,
)

/** Where a measurement's number came from. Stored names are persistent (DB column and sidecar). */
@Serializable
enum class ValueSource { COMPUTED, USER_OVERRIDE }

/**
 * A measurement's value with the illegal pairs unrepresentable (ADR-002 decision 6):
 * a computed value has no original, an override always has one.
 */
sealed interface MeasurementValue {
    val meters: Double

    data class Computed(override val meters: Double) : MeasurementValue

    data class Overridden(override val meters: Double, val originalMeters: Double) : MeasurementValue

    companion object {
        /**
         * Builds a value from the persisted column triple. A COMPUTED row drops a stray original and an
         * override without one reads as `Overridden(meters, meters)`; a null [meters] has no value.
         */
        fun fromColumns(meters: Double?, source: ValueSource, originalMeters: Double?): MeasurementValue? {
            if (meters == null) return null
            return when (source) {
                ValueSource.COMPUTED -> Computed(meters)
                ValueSource.USER_OVERRIDE -> Overridden(meters, originalMeters ?: meters)
            }
        }
    }
}
