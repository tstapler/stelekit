// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import arrow.core.Either
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ConstraintFitRecord
import dev.stapler.stelekit.model.FitTier
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.LensCalibration
import dev.stapler.stelekit.model.PlaneCalibration
import dev.stapler.stelekit.model.PlaneKind
import dev.stapler.stelekit.model.PriorBasis
import kotlin.math.max

/**
 * The only writer of constraint-fit state into a [Calibration] (ADR-003 decision 2). Pure: every flow in the
 * precedence table is one function here, so the table has a test per row and the ViewModel only delegates.
 * Every tier writes the existing `MANUAL_REFERENCE` method and a plane homography, never a new enum value,
 * so an older build keeps measuring correctly from the same data.
 */
object ConstraintFitApplier {
    const val PLANE_NAME = "Known distances"

    /** Manual references always report full confidence (`MultiPointCalibration`); the real quality is the fit record. */
    private const val CONFIDENCE_PERCENT = 100
    private const val PLANE_CELLS = 9
    private const val MIN_EXTENT_METERS = 1e-3

    sealed interface Outcome {
        /** [fallback] is why the requested tier was not used (the SCALE fit was applied instead); null otherwise. */
        data class Applied(val calibration: Calibration, val record: ConstraintFitRecord, val fallback: CalibrationFailure?) : Outcome

        /** Nothing was written: not even a SCALE fit could be computed. */
        data class Declined(val failure: CalibrationFailure) : Outcome
    }

    /**
     * Writes [response] into [current] for [constraints]. A requested tier that failed (crop, tilt too large, veto)
     * applies the SCALE fit and writes no plane; a response without any fit changes nothing.
     */
    fun apply(
        current: Calibration?,
        constraints: List<CalibrationConstraint>,
        response: FitResponse,
        frame: CaptureFrameInfo,
    ): Outcome {
        val base = current ?: Calibration()
        if (base.method != CalibrationMethod.NONE && base.isLegacyBasis) return Outcome.Declined(CalibrationFailure.LegacyBasis)
        val fit = response.effective ?: return Outcome.Declined(response.requested.fold({ it }, { CalibrationFailure.DidNotConverge }))
        val fallback = (response.requested as? Either.Left)?.value
        // A plane is written only for a tier that was actually granted; the SCALE fallback never carries one.
        val writePlane = fallback == null && fit.tier != FitTier.SCALE && fit.homography?.size == PLANE_CELLS
        val geometry = base.geometry ?: GeometryCalibration()
        val prior = geometry.fit?.priorBasis ?: snapshot(base)
        val record = ConstraintFitRecord(
            tier = fit.tier,
            n = fit.report.n,
            residualMeanPct = fit.report.residualMeanPct,
            looPct = fit.report.looPct,
            worstId = fit.report.worst?.id,
            focalSource = fit.focalSource,
            priorBasis = prior,
        )
        val plane = if (writePlane) constraintPlane(fit, constraints, frame) else null
        val withK = fit.tier == FitTier.GRADIENT_AND_K && writePlane
        val lens = when {
            withK -> (base.lens ?: LensCalibration()).copy(k = fit.k, kEstimated = true, profileId = null, profileLabel = null)
            else -> base.lens
        }
        val written = base.copy(
            method = CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = fit.pixelsPerMeter,
            confidencePercent = CONFIDENCE_PERCENT,
            basisVersion = if (base.method == CalibrationMethod.NONE || withK) CalibrationBasis.NATIVE_PIXELS_LENS else base.basisVersion,
            lens = lens,
            geometry = geometry.copy(plane = plane, constraints = constraints, fit = record),
        )
        return Outcome.Applied(written, record, fallback)
    }

    /**
     * Undoes the fit after the last known distance is deleted: the replaced basis returns exactly and constraints
     * and fit are cleared (height offset stays). With no fit record, only the constraints are cleared.
     */
    fun restorePriorBasis(current: Calibration): Calibration {
        val geometry = current.geometry ?: return current
        val prior = geometry.fit?.priorBasis
        val remaining = geometry.copy(constraints = emptyList(), fit = null)
        if (prior == null) return current.copy(geometry = remaining.takeUnless { it.isEmpty() })
        return current.copy(
            method = prior.method,
            pixelsPerMeter = prior.pixelsPerMeter,
            confidencePercent = prior.confidencePercent,
            lens = prior.lens,
            geometry = remaining.copy(plane = prior.plane).takeUnless { it.isEmpty() },
        )
    }

    /**
     * A new line, ruler or paper-plane calibration replaces the basis: the height offset already entered stays,
     * the previous plane, constraints and fit (stale provenance) go. A plane the [replacement] brings is kept.
     */
    fun replacingBasis(previous: Calibration?, replacement: Calibration): Calibration {
        val before = previous?.geometry
        val geometry = (replacement.geometry ?: GeometryCalibration()).copy(
            heightOffsetMeters = before?.heightOffsetMeters ?: 0.0,
            cameraDistanceMeters = before?.cameraDistanceMeters,
            constraints = emptyList(),
            fit = null,
        )
        return replacement.copy(geometry = geometry.takeUnless { it.isEmpty() })
    }

    /** Height-offset edit: only those two fields change; plane, constraints and fit survive. */
    fun withHeightOffset(current: Calibration, heightOffsetMeters: Double, cameraDistanceMeters: Double?): Calibration {
        val merged = (current.geometry ?: GeometryCalibration()).copy(
            heightOffsetMeters = heightOffsetMeters,
            cameraDistanceMeters = cameraDistanceMeters,
        )
        return current.copy(geometry = merged.takeUnless { it.isEmpty() })
    }

    private fun snapshot(base: Calibration) = PriorBasis(
        method = base.method,
        pixelsPerMeter = base.pixelsPerMeter,
        confidencePercent = base.confidencePercent,
        plane = base.geometry?.plane,
        lens = base.lens,
    )

    /** Non-zero metric bounding extent of the constraint endpoints, so an older build never divides by zero. */
    private fun constraintPlane(fit: ConstraintFit, constraints: List<CalibrationConstraint>, frame: CaptureFrameInfo): PlaneCalibration {
        val h = Homography(checkNotNull(fit.homography))
        val mapped = constraints.flatMap { listOf(it.p1, it.p2) }.mapNotNull { p ->
            h.apply(RadialLens.undistort(p.x, p.y, fit.k, frame.widthPx, frame.heightPx))
        }
        val width = (mapped.maxOfOrNull { it.x } ?: 0.0) - (mapped.minOfOrNull { it.x } ?: 0.0)
        val height = (mapped.maxOfOrNull { it.y } ?: 0.0) - (mapped.minOfOrNull { it.y } ?: 0.0)
        return PlaneCalibration(
            homography = fit.homography,
            referenceName = PLANE_NAME,
            widthMeters = max(width, MIN_EXTENT_METERS),
            heightMeters = max(height, MIN_EXTENT_METERS),
            checkResidualMm = null,
            kind = PlaneKind.CONSTRAINT_FIT,
        )
    }
}
