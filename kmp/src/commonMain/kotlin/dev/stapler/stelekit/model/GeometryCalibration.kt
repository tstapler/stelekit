// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.model

import kotlinx.serialization.Serializable

/**
 * Depth-related part of a [Calibration]: how far the measured surface sits above the reference
 * (ruler) plane, and optionally the camera-to-plane distance when it cannot be derived from EXIF.
 * Additive and independent of [CalibrationBasis]; absent means no correction.
 */
@Serializable
data class GeometryCalibration(
    /** Height of the measured surface above the reference plane, metres. 0 means on the plane. */
    val heightOffsetMeters: Double = 0.0,
    /** Manually entered camera-to-reference-plane distance, metres; null derives it from the focal length. */
    val cameraDistanceMeters: Double? = null,
)

/** JSON text form of [GeometryCalibration] for the `calibration_geometry_json` column; '' means none. */
object GeometryCalibrationCodec {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun encode(geometry: GeometryCalibration?): String =
        if (geometry == null) "" else json.encodeToString(GeometryCalibration.serializer(), geometry)

    /** Returns null for blank or unparseable text (an unreadable block degrades to no correction). */
    fun decode(text: String): GeometryCalibration? =
        if (text.isBlank()) null else runCatching { json.decodeFromString(GeometryCalibration.serializer(), text) }.getOrNull()
}
