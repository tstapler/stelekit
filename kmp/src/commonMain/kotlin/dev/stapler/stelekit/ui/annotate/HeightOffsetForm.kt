// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.calibration.CameraDistance
import dev.stapler.stelekit.calibration.DistanceSource
import dev.stapler.stelekit.calibration.HeightOffset
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.MeasurementUnit
import kotlin.math.round

/** User-facing copy for the height-offset dialog. */
object HeightOffsetCopy {
    const val EXPLANATION: String =
        "Anything raised above the surface the ruler lies on looks bigger in the photo. Enter how far the edge you " +
            "measure is above that surface and measurements on this photo (reference lines excluded) are scaled " +
            "down accordingly. Leave it at 0 for things lying flat beside the ruler."
    const val NEEDS_DISTANCE: String =
        "The camera distance is unknown for this photo (no focal length). Enter it to apply a height offset."
}

/** What the user has typed into the height-offset dialog. */
data class HeightOffsetDraft(
    val heightText: String = "",
    val distanceText: String = "",
    val heightUnit: MeasurementUnit = MeasurementUnit.MILLIMETERS,
    val distanceUnit: MeasurementUnit = MeasurementUnit.CENTIMETERS,
)

/** Outcome of validating a [HeightOffsetDraft]; [geometry] is non-null when it can be applied. */
data class HeightOffsetPreview(
    val geometry: GeometryCalibration?,
    val message: String,
    val distance: CameraDistance?,
) {
    val canApply: Boolean get() = geometry != null
}

/** Draft that reproduces an already-saved [geometry] (height shown in mm, distance in cm). */
fun draftFor(geometry: GeometryCalibration?): HeightOffsetDraft = HeightOffsetDraft(
    heightText = geometry?.heightOffsetMeters?.takeIf { it > 0.0 }?.let { plainNumber(it * MM_PER_M) }.orEmpty(),
    distanceText = geometry?.cameraDistanceMeters?.let { plainNumber(it * CM_PER_M) }.orEmpty(),
)

/**
 * Validates [draft] against [calibration]; blank height means 0 (clear). A height above zero needs a camera
 * distance, either typed or derived from the focal length and scale.
 */
fun HeightOffsetDraft.preview(
    calibration: Calibration,
    focal35mmEq: Double?,
    widthPx: Double,
    heightPx: Double,
): HeightOffsetPreview {
    val height = if (heightText.isBlank()) 0.0 else parseMeters(heightText, heightUnit)
    val typedDistance = if (distanceText.isBlank()) null else parseMeters(distanceText, distanceUnit)
    if (height == null || height < 0.0) return HeightOffsetPreview(null, "Enter a height of 0 or more.", null)
    if (distanceText.isNotBlank() && (typedDistance == null || typedDistance <= 0.0)) {
        return HeightOffsetPreview(null, "Enter a camera distance above 0.", null)
    }
    val candidate = GeometryCalibration(height, typedDistance)
    val distance = HeightOffset.cameraDistance(candidate, calibration, focal35mmEq, widthPx, heightPx)
    if (height == 0.0) return HeightOffsetPreview(GeometryCalibration(0.0, typedDistance), "No height offset: measurements use the ruler's scale.", distance)
    if (distance == null) return HeightOffsetPreview(null, HeightOffsetCopy.NEEDS_DISTANCE, null)
    val factor = HeightOffset.factor(distance.meters, height)
        ?: return HeightOffsetPreview(null, "That height is too large for a camera distance of ${distanceCm(distance)} cm.", distance)
    val percent = (1.0 - factor) * PERCENT
    val source = if (distance.source == DistanceSource.MANUAL) "typed" else "from the focal length"
    val message = "Camera distance about ${distanceCm(distance)} cm ($source). Measurements shrink by ${oneDecimal(percent)}% (x${threeDecimals(factor)})."
    return HeightOffsetPreview(candidate, message, distance)
}

private const val MM_PER_M = 1000.0
private const val CM_PER_M = 100.0
private const val PERCENT = 100.0

private fun distanceCm(distance: CameraDistance): String = round(distance.meters * CM_PER_M).toLong().toString()

private fun oneDecimal(v: Double): String {
    val tenths = round(v * 10.0).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

private fun threeDecimals(v: Double): String {
    val thousandths = round(v * 1000.0).toLong()
    return "${thousandths / 1000}.${(thousandths % 1000).toString().padStart(3, '0')}"
}

/** Typed length in metres; unlike a reference length, zero is allowed. */
private fun parseMeters(text: String, unit: MeasurementUnit): Double? {
    val value = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return parseGridRefLengthToMeters("1", unit)?.let { value * it }
}

/** Shortest decimal text with at most two decimals (12.5, not 12.50 or 12.0). */
private fun plainNumber(value: Double): String {
    val hundredths = round(value * PERCENT).toLong()
    val whole = hundredths / PERCENT.toLong()
    val frac = hundredths % PERCENT.toLong()
    return if (frac == 0L) "$whole" else "$whole.${frac.toString().padStart(2, '0').trimEnd('0')}"
}
