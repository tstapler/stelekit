// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.model.CaptureLensRecord
import dev.stapler.stelekit.model.LensRecordSource
import kotlin.concurrent.Volatile
import kotlin.math.abs

/** The lens facts of one repeating-request result (`LENS_FOCAL_LENGTH`, active physical id, zoom). */
data class CaptureResultSnapshot(
    val timestampNs: Long,
    val focalLengthMm: Double?,
    val activePhysicalId: String?,
    val zoomRatio: Double?,
)

/** Latest capture-callback result, written from the camera thread and read at shutter press. */
class CaptureResultHolder {
    @Volatile private var snapshot: CaptureResultSnapshot? = null

    fun update(next: CaptureResultSnapshot) {
        snapshot = next
    }

    fun latest(): CaptureResultSnapshot? = snapshot
}

/**
 * Builds the persisted lens record at shutter (ADR-004 decision 5). A snapshot taken within [FRESH_WINDOW_NS] of the
 * shutter that carries a lens key is the record; otherwise the record names the selected lens as
 * [LensRecordSource.SELECTED_LENS], which downstream code never treats as independent evidence.
 */
object CaptureLensRecordBuilder {
    /** About three frames at 30 fps; provisional until the owner probe (Task 4.1b) shows real delivery latency. */
    const val FRESH_WINDOW_NS = 100_000_000L
    private const val FOCAL_MATCH = 0.03

    fun build(
        selected: CameraLens,
        zoomAtShutter: Double,
        snapshot: CaptureResultSnapshot?,
        shutterNs: Long,
        exifFocalMm: Double?,
    ): CaptureLensRecord {
        val usable = snapshot?.takeIf {
            abs(shutterNs - it.timestampNs) <= FRESH_WINDOW_NS && (it.focalLengthMm != null || it.activePhysicalId != null)
        }
        if (usable == null) {
            return CaptureLensRecord(
                cameraId = selected.cameraId,
                physicalId = selected.physicalId,
                focalLengthMm = selected.focalLengthMm,
                focal35mmEq = selected.focal35mmEq,
                zoomRatio = zoomAtShutter,
                source = LensRecordSource.SELECTED_LENS,
                exifFocalMm = exifFocalMm,
            )
        }
        val sameFocal = usable.focalLengthMm == null || selected.focalLengthMm == null ||
            abs(usable.focalLengthMm - selected.focalLengthMm) / selected.focalLengthMm <= FOCAL_MATCH
        return CaptureLensRecord(
            cameraId = selected.cameraId,
            physicalId = usable.activePhysicalId ?: selected.physicalId,
            focalLengthMm = usable.focalLengthMm ?: selected.focalLengthMm,
            focal35mmEq = selected.focal35mmEq.takeIf { sameFocal },
            zoomRatio = usable.zoomRatio ?: zoomAtShutter,
            source = LensRecordSource.PREVIEW_RESULT_AT_SHUTTER,
            exifFocalMm = exifFocalMm,
        )
    }
}
