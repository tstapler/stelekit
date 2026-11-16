// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

import dev.stapler.stelekit.calibration.CalibrationService
import dev.stapler.stelekit.calibration.FocalResolution
import dev.stapler.stelekit.calibration.FocalSource
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.CaptureLensRecord
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.LensRecordSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 3.9c: the sidecar lens record reaches the focal-source resolver; photos without one stay EXIF_ONLY. */
class LensRecordFocalSourceTest {
    private val fs = FakeFileSystem()
    private val manager = ImageSidecarManager(fs)
    private val notes = EffectiveNotesPath("/g")

    private fun save(lens: CaptureLensRecord?) {
        manager.writeSidecar(
            ImageAnnotation(
                uuid = "focalimg1", blockUuid = "focalblk1", pageUuid = "focalpg01", graphPath = "/g", filePath = "/g/f.jpg",
                calibration = Calibration(CalibrationMethod.NONE, 0.0, 0),
                sensorData = ImageSensorData(focalLength35mmEq = 24.0, lensRecord = lens),
            ),
            emptyList(),
        )
    }

    private fun resolve(): FocalResolution = CalibrationService.focalResolution(
        lensRecord = manager.readLensRecord(notes, "focalimg1"),
        userConfirmed35mmEq = null,
        profileMatched = false,
        exifFocal35mmEq = 24.0,
    )

    private fun record(source: LensRecordSource, zoom: Double = 1.0, exif: Double? = 5.6) = CaptureLensRecord(
        "0", "2", 5.6, 26.0, zoom, source, exif,
    )

    @Test
    fun resolver_should_UseTheSidecarRecord_When_PreviewResultAtZoomOne() {
        save(record(LensRecordSource.PREVIEW_RESULT_AT_SHUTTER))
        assertEquals(FocalResolution(FocalSource.SIDECAR_LENS_RECORD, 26.0), resolve())
    }

    @Test
    fun resolver_should_FallBackToExifOnly_When_NoRecordOrUntrustedRecord() {
        save(null)
        assertEquals(FocalSource.EXIF_ONLY, resolve().source)
        save(record(LensRecordSource.SELECTED_LENS))
        assertEquals(FocalSource.EXIF_ONLY, resolve().source)
        save(record(LensRecordSource.PREVIEW_RESULT_AT_SHUTTER, exif = 9.0))
        assertEquals(FocalSource.EXIF_ONLY, resolve().source)
        save(record(LensRecordSource.PREVIEW_RESULT_AT_SHUTTER, zoom = 2.0))
        assertEquals(FocalSource.EXIF_ONLY, resolve().source)
    }
}
