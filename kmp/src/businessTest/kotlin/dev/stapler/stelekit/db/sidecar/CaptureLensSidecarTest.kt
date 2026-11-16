// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.CaptureLensRecord
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.LensRecordSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** U-E4-12: the capture lens record round-trips through the sidecar, old sidecars read null, and a re-save keeps it. */
class CaptureLensSidecarTest {
    private val fs = FakeFileSystem()
    private val manager = ImageSidecarManager(fs)
    private val notes = EffectiveNotesPath("/g")

    private val record = CaptureLensRecord(
        cameraId = "0", physicalId = "2", focalLengthMm = 2.2, focal35mmEq = 14.0, zoomRatio = 1.0,
        source = LensRecordSource.PREVIEW_RESULT_AT_SHUTTER, exifFocalMm = 2.3,
    )

    private fun image(lens: CaptureLensRecord?) = ImageAnnotation(
        uuid = "lensimg1", blockUuid = "lensblk1", pageUuid = "lenspg01", graphPath = "/g", filePath = "/g/l.jpg",
        calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100, CalibrationBasis.NATIVE_PIXELS),
        sensorData = ImageSensorData(focalLengthMm = 2.3, lensRecord = lens),
    )

    @Test
    fun lensRecord_should_RoundTrip_AndOldSidecarReadNull_AndCoexistWithCalibrationKeys() {
        manager.writeSidecar(image(record), emptyList())
        val file = manager.readSidecar(notes, "lensimg1").getOrNull()!!
        assertEquals(2, file.schemaVersion)
        assertEquals(record, file.toDomainAnnotation().sensorData.lensRecord)
        assertEquals(100, file.imageAnnotation.calibrationConfidencePct)

        fs.writeFile(manager.sidecarPath(notes, "oldimg01"), SidecarFixtures.V0113.replace("goldenimg1", "oldimg01"))
        val old = manager.readSidecar(notes, "oldimg01").getOrNull()!!
        assertNull(old.toDomainAnnotation().sensorData.lensRecord)
        assertNull(manager.readLensRecord(notes, "oldimg01"))
    }

    @Test
    fun schemaVersion_should_StayOne_When_NoLensRecord() {
        manager.writeSidecar(image(null), emptyList())
        assertEquals(1, manager.readSidecar(notes, "lensimg1").getOrNull()!!.schemaVersion)
    }

    @Test
    fun resave_should_KeepTheStoredLensRecord_When_TheEditorRebuildsTheImageWithoutIt() {
        manager.writeSidecar(image(record), emptyList())
        manager.writeSidecar(image(null), emptyList())
        val lens = manager.readLensRecord(notes, "lensimg1")
        assertNotNull(lens)
        assertEquals("2", lens.physicalId)
        assertEquals(2, manager.readSidecar(notes, "lensimg1").getOrNull()!!.schemaVersion)
        assertTrue(fs.readFile(manager.sidecarPath(notes, "lensimg1"))!!.contains("\"lensRecord\""))
    }

    @Test
    fun readLensRecord_should_ReturnNull_When_TheSidecarIsMalformed_AndDefaultSourceWhenAbsent() {
        fs.writeFile(manager.sidecarPath(notes, "badimg01"), "{ this is not json")
        assertNull(manager.readLensRecord(notes, "badimg01"))

        manager.writeSidecar(image(record), emptyList())
        val path = manager.sidecarPath(notes, "lensimg1")
        fs.writeFile(path, fs.readFile(path)!!.replace(Regex("\\s*\"source\"\\s*:\\s*\"[A-Z_]+\",?"), ""))
        assertEquals(LensRecordSource.SELECTED_LENS, manager.readLensRecord(notes, "lensimg1")?.source)
    }

    @Test
    fun lensRecordFor_should_ReadTheRecordOffTheCallingThread() = kotlinx.coroutines.runBlocking {
        manager.writeSidecar(image(record), emptyList())
        assertEquals(record, manager.lensRecordFor(image(null)))
    }
}
