// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Builds the user-facing confidence statement for a measurement: lens/radial warnings from
 * [LensMeasurement.assess], plus short-reference, tick-count, camera-tilt and applied-correction messages.
 * Pure; the editor shows the result on the measurement pill and in its status area.
 */
object MeasurementAdvisor {
    /** A reference shorter than this share of the image's long side makes a 1 px tap error a large share of the scale. */
    const val SHORT_REFERENCE_FRACTION = 0.15
    private const val ENDPOINT_PX_ERROR = 1.0
    private const val MIN_TICKS_TO_CHECK_DISTORTION = 3
    private const val PERCENT = 100.0
    private const val TENTH = 10.0

    const val TILT_UNKNOWN_NOTE: String = "Tilt unknown: this photo has no camera orientation data."

    /** Words for the pill hint when a measurement carries warnings. */
    const val PILL_WARNING_PREFIX: String = "⚠ "

    /**
     * [points] are the measured annotation's points; pass an empty list for a calibration-level check
     * (the radial warning needs an object, the rest does not).
     */
    fun assess(
        points: List<NormalizedPoint>,
        calibration: Calibration,
        widthPx: Double,
        heightPx: Double,
        pitchDeg: Double?,
        rollDeg: Double?,
    ): MeasurementReliability {
        val lens = calibration.lens
        val base = if (lens == null) {
            MeasurementReliability(0.0, emptyList())
        } else {
            // With no object, the ruler itself stands in so only the object-independent warnings fire.
            val probe = points.ifEmpty { lens.ticks.map { NormalizedPoint(it.x, it.y) } }
            LensMeasurement.assess(probe, calibration, widthPx, heightPx)
        }
        val warnings = base.warnings.toMutableList()
        val notes = mutableListOf<String>()
        var error = base.estimatedErrorPercent

        if (lens != null) {
            shortReference(lens.ticks, widthPx, heightPx)?.let { (message, percent) ->
                warnings += message
                error = max(error, percent)
            }
            if (lens.ticks.size in 1 until MIN_TICKS_TO_CHECK_DISTORTION) {
                notes += "Only ${lens.ticks.size} ruler marks: mark a third to check for lens distortion or tilt."
            }
            if (lens.k != 0.0) notes += "Lens correction applied (k = ${decimals(lens.k, 3)})."
        }

        when (TiltAssessment.tierOf(pitchDeg, rollDeg)) {
            TiltTier.UNKNOWN -> notes += TILT_UNKNOWN_NOTE
            TiltTier.FLAT -> Unit
            TiltTier.MILD, TiltTier.EXCESSIVE -> {
                val degrees = TiltAssessment.worstDegrees(pitchDeg, rollDeg) ?: 0.0
                val percent = TiltAssessment.acrossFramePercent(degrees)
                warnings += "Camera tilt about ${round(degrees).toInt()}° may add up to about ${decimals(percent, 1)}% " +
                    "across the frame: shoot straight down or use a reference rectangle."
                error = max(error, percent)
            }
        }

        return MeasurementReliability(
            estimatedErrorPercent = error,
            warnings = warnings,
            notes = notes,
            guidance = if (warnings.isEmpty()) null else LensMeasurement.SHOOTING_GUIDANCE,
        )
    }

    /** Pill text: a warning mark in front of the stored value text when the measurement has warnings. */
    fun pillText(valueText: String, hasWarning: Boolean): String =
        if (hasWarning) PILL_WARNING_PREFIX + valueText else valueText

    private fun shortReference(ticks: List<CalibrationTick>, w: Double, h: Double): Pair<String, Double>? {
        if (ticks.size < 2) return null
        val first = ticks.minBy { it.positionMeters }
        val last = ticks.maxBy { it.positionMeters }
        val spanPx = hypot((last.x - first.x) * w, (last.y - first.y) * h)
        val longSide = max(w, h)
        if (spanPx <= 0.0 || spanPx >= SHORT_REFERENCE_FRACTION * longSide) return null
        val percent = sqrt(2.0) * ENDPOINT_PX_ERROR / spanPx * PERCENT
        val share = decimals(spanPx / longSide * PERCENT, 0)
        val message = "Short reference: the ruler spans only $share% of the image, so a 1 px tap error is " +
            "about ${decimals(percent, 1)}% of every measurement. Use a longer span."
        return message to percent
    }

    private fun decimals(value: Double, digits: Int): String {
        var factor = 1.0
        repeat(digits) { factor *= TENTH }
        val scaled = round(kotlin.math.abs(value) * factor).toLong()
        val sign = if (value < 0.0 && scaled != 0L) "-" else ""
        if (digits == 0) return sign + scaled
        val unit = factor.toLong()
        return "$sign${scaled / unit}.${(scaled % unit).toString().padStart(digits, '0')}"
    }
}
