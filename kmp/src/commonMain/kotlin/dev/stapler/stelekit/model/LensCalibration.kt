package dev.stapler.stelekit.model

import kotlinx.serialization.Serializable

/**
 * One ruler mark used by multi-point calibration: where the user tapped (normalized image
 * coordinates) and the exact real-world position of that mark along the ruler, in metres.
 */
@Serializable
data class CalibrationTick(
    val x: Double,
    val y: Double,
    val positionMeters: Double,
)

/**
 * Lens-aware part of a [Calibration] (basis [CalibrationBasis.NATIVE_PIXELS_LENS]).
 *
 * [k] is the one-parameter division-model coefficient (0 = no correction), [ticks] the ruler marks
 * the scale was fitted from, and [residualPercent] how far the marks disagree with a straight,
 * evenly-scaled ruler after correction. Stored measurements keep their raw normalized points.
 */
@Serializable
data class LensCalibration(
    val k: Double = 0.0,
    val ticks: List<CalibrationTick> = emptyList(),
    val residualPercent: Double = 0.0,
    val kEstimated: Boolean = false,
)

/** JSON text form of [LensCalibration] for the `calibration_lens_json` column; '' means none. */
object LensCalibrationCodec {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun encode(lens: LensCalibration?): String = if (lens == null) "" else json.encodeToString(LensCalibration.serializer(), lens)

    /** Returns null for blank or unparseable text (an unreadable lens block degrades to a plain scale). */
    fun decode(text: String): LensCalibration? =
        if (text.isBlank()) null else runCatching { json.decodeFromString(LensCalibration.serializer(), text) }.getOrNull()
}
