// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One camera's probed facts; every field is nullable because an OEM may omit any characteristic. */
@Serializable
data class ProbeCamera(
    val id: String,
    val facing: String? = null,
    val focalsMm: List<Double>? = null,
    val sensorWidthMm: Double? = null,
    val sensorHeightMm: Double? = null,
    val logicalMulti: Boolean? = null,
    val physicalIds: List<String>? = null,
    /** Physical id to `ok` or `fail:<exception class>`. */
    val physicalBind: Map<String, String>? = null,
    val hasIntrinsicCalibration: Boolean? = null,
    val hasLensDistortion: Boolean? = null,
    val distortionModes: List<Int>? = null,
    val minZoom: Double? = null,
    val maxZoom: Double? = null,
)

/** The exported fixture: device make and model only, no serial, location or account data (public repo). */
@Serializable
data class ProbeFixture(val make: String, val model: String, val cameras: List<ProbeCamera>)

object CameraProbeFormat {
    private val json = Json { prettyPrint = true; encodeDefaults = false }

    fun line(c: ProbeCamera): String = buildString {
        append("camera id=${c.id}")
        append(" facing=${c.facing ?: "?"}")
        append(" focal=${c.focalsMm?.joinToString(",") ?: "?"}")
        append(" sensor=${if (c.sensorWidthMm != null && c.sensorHeightMm != null) "${c.sensorWidthMm}x${c.sensorHeightMm}" else "?"}")
        append(" logical=${c.logicalMulti ?: "?"}")
        append(" physical=${c.physicalIds?.joinToString(",") { id -> c.physicalBind?.get(id)?.let { "$id:$it" } ?: id } ?: "?"}")
        append(" intrinsic=${c.hasIntrinsicCalibration ?: "?"} distortion=${c.hasLensDistortion ?: "?"}")
        append(" distortionModes=${c.distortionModes?.joinToString(",") ?: "?"}")
        append(" zoom=${if (c.minZoom != null && c.maxZoom != null) "${c.minZoom}..${c.maxZoom}" else "?"}")
    }

    fun exportJson(fixture: ProbeFixture): String = json.encodeToString(ProbeFixture.serializer(), fixture)
}
