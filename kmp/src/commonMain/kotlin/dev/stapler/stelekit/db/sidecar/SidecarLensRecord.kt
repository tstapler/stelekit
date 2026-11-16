// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

import dev.stapler.stelekit.model.CaptureLensRecord
import dev.stapler.stelekit.model.LensRecordSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The nested `lensRecord` object of a v2 sidecar. [source] is a String so an unknown future value still decodes. */
@Serializable
data class SidecarLensRecord(
    @SerialName("cameraId") val cameraId: String,
    @SerialName("physicalId") val physicalId: String? = null,
    @SerialName("focalLengthMm") val focalLengthMm: Double? = null,
    @SerialName("focalLength35mmEq") val focalLength35mmEq: Double? = null,
    @SerialName("zoomRatio") val zoomRatio: Double = 1.0,
    @SerialName("source") val source: String = LensRecordSource.SELECTED_LENS.name,
    @SerialName("exifFocalMm") val exifFocalMm: Double? = null,
)

internal fun CaptureLensRecord.toSidecar() = SidecarLensRecord(
    cameraId, physicalId, focalLengthMm, focal35mmEq, zoomRatio, source.name, exifFocalMm,
)

/** An unknown [SidecarLensRecord.source] reads as the weaker [LensRecordSource.SELECTED_LENS], so it is never over-trusted. */
internal fun SidecarLensRecord.toDomain() = CaptureLensRecord(
    cameraId, physicalId, focalLengthMm, focalLength35mmEq, zoomRatio,
    runCatching { LensRecordSource.valueOf(source) }.getOrDefault(LensRecordSource.SELECTED_LENS), exifFocalMm,
)
