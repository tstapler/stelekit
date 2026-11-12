// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.GeometryCalibration
import kotlin.math.hypot

/** Where a camera-to-plane distance came from. */
enum class DistanceSource { MANUAL, FOCAL_LENGTH }

data class CameraDistance(val meters: Double, val source: DistanceSource)

/**
 * Height-offset correction (ADR-002 step 5). An edge `h` above the reference plane is `D - h` from the
 * camera instead of `D`, so it looks `D / (D - h)` times larger; multiplying by `(D - h) / D` undoes that.
 * Pure and platform-agnostic.
 */
object HeightOffset {
    /** Beyond this share of D the pinhole approximation and any plausible phone shot are out of range. */
    const val MAX_HEIGHT_FRACTION = 0.5

    /** Diagonal of a 35 mm frame, the basis of "35 mm equivalent" focal lengths. */
    private const val FULL_FRAME_DIAGONAL_MM = 43.2666

    /** Thickness presets, metres; values are typical, not measured for a specific product. */
    val PRESETS: List<Pair<String, Double>> = listOf(
        "Credit card (0.76 mm)" to 0.00076,
        "Flat ruler (about 2 mm)" to 0.002,
        "Coin (about 2 mm)" to 0.002,
    )

    /** Focal length in image pixels from a 35 mm-equivalent focal length, assuming an uncropped frame. */
    fun focalPixels(focal35mmEq: Double?, widthPx: Double, heightPx: Double): Double? {
        if (focal35mmEq == null || !focal35mmEq.isFinite() || focal35mmEq <= 0.0) return null
        return focal35mmEq / FULL_FRAME_DIAGONAL_MM * hypot(widthPx, heightPx)
    }

    /**
     * Camera-to-plane distance: the manual value if set, else `f_px / pixelsPerMeter` (the scale on the
     * plane is `f_px / D`), which needs a known focal length. As in the editor's own measuring, the scale is
     * taken to be in the same pixel units as [widthPx] x [heightPx].
     */
    fun cameraDistance(
        geometry: GeometryCalibration?,
        calibration: Calibration,
        focal35mmEq: Double?,
        widthPx: Double,
        heightPx: Double,
    ): CameraDistance? {
        geometry?.cameraDistanceMeters?.takeIf { it.isFinite() && it > 0.0 }?.let { return CameraDistance(it, DistanceSource.MANUAL) }
        val ppm = calibration.pixelsPerMeter
        if (!ppm.isFinite() || ppm <= 0.0) return null
        val f = focalPixels(focal35mmEq, widthPx, heightPx) ?: return null
        return CameraDistance(f / ppm, DistanceSource.FOCAL_LENGTH)
    }

    /** `(D - h) / D`, or null when [heightMeters] is negative or not below [MAX_HEIGHT_FRACTION] of [distanceMeters]. */
    fun factor(distanceMeters: Double, heightMeters: Double): Double? {
        val distanceOk = distanceMeters.isFinite() && distanceMeters > 0.0
        val heightOk = heightMeters.isFinite() && heightMeters >= 0.0
        if (!distanceOk || !heightOk) return null
        if (heightMeters >= distanceMeters * MAX_HEIGHT_FRACTION) return null
        return (distanceMeters - heightMeters) / distanceMeters
    }

    /** Length factor to apply to a measurement under [calibration]; 1.0 when there is no usable offset. */
    fun lengthFactor(calibration: Calibration?, focal35mmEq: Double?, widthPx: Double, heightPx: Double): Double {
        val geometry = calibration?.geometry ?: return 1.0
        if (geometry.heightOffsetMeters <= 0.0) return 1.0
        val distance = cameraDistance(geometry, calibration, focal35mmEq, widthPx, heightPx) ?: return 1.0
        return factor(distance.meters, geometry.heightOffsetMeters) ?: 1.0
    }
}
