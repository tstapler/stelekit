// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan

/** How far the camera was from square-on, from capture-time gravity data (ADR-002 tilt tiers). */
enum class TiltTier {
    /** No orientation data (gallery images): neither safe nor unsafe. */
    UNKNOWN,

    /** At most [TiltAssessment.MILD_DEG]: no message. */
    FLAT,

    /** Above [TiltAssessment.MILD_DEG] but under the "Camera tilted" banner thresholds. */
    MILD,

    /** Pitch or roll past the thresholds that raise the "Camera tilted" banner. */
    EXCESSIVE,
}

/** Pure tilt rules shared by the editor banner and the per-measurement reliability warnings. */
object TiltAssessment {
    const val MILD_DEG = 3.0
    const val EXCESSIVE_PITCH_DEG = 15.0
    const val EXCESSIVE_ROLL_DEG = 10.0

    /**
     * Fraction of the camera distance by which an object typically sits off the ruler; with tilt theta the
     * scale error is about tan(theta) times this. Inferred from the 5 degree, 25 cm at 60 cm row of the
     * measurement error budget (3.6%), so it is an order-of-magnitude figure, not a bound.
     */
    private const val TYPICAL_OFFSET_FRACTION = 0.4
    private const val PERCENT = 100.0
    private const val HALF_TURN_DEG = 180.0

    fun tierOf(pitchDeg: Double?, rollDeg: Double?): TiltTier {
        val pitch = pitchDeg?.takeIf { it.isFinite() }
        val roll = rollDeg?.takeIf { it.isFinite() }
        return when {
            pitch == null && roll == null -> TiltTier.UNKNOWN
            (pitch != null && abs(pitch) > EXCESSIVE_PITCH_DEG) || (roll != null && abs(roll) > EXCESSIVE_ROLL_DEG) ->
                TiltTier.EXCESSIVE
            worstDegrees(pitch, roll)!! > MILD_DEG -> TiltTier.MILD
            else -> TiltTier.FLAT
        }
    }

    /** The larger of |pitch| and |roll|, or null when neither is known. */
    fun worstDegrees(pitchDeg: Double?, rollDeg: Double?): Double? {
        val values = listOfNotNull(pitchDeg, rollDeg).filter { it.isFinite() }.map { abs(it) }
        return values.takeIf { it.isNotEmpty() }?.reduce(::max)
    }

    /** Rough scale error (percent) across the frame for a tilt of [degrees]. */
    fun acrossFramePercent(degrees: Double): Double =
        tan(abs(degrees) * PI / HALF_TURN_DEG) * TYPICAL_OFFSET_FRACTION * PERCENT
}
