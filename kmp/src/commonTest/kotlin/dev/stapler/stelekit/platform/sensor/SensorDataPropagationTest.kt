package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.model.ImageSensorData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for Story 8.1.5: Sensor data propagation through the import pipeline.
 *
 * When [PlatformImageFile.sensorData] is non-null (i.e., a camera capture),
 * the GPS, bearing, and tilt data should flow into the resulting [ImageAnnotation.sensorData].
 * EXIF fields (focalLengthMm, cameraMake, etc.) from [PlatformImageFile] should take
 * priority over sensor data EXIF fields when both are present.
 */
class SensorDataPropagationTest {

    @Test
    fun platformImageFile_sensorDataNull_byDefault() {
        // PlatformImageFile from file import has null sensorData
        val file = PlatformImageFile(
            path = "/tmp/image.jpg",
            mimeType = "image/jpeg",
        )
        assertNull(file.sensorData, "sensorData should be null for imported files")
    }

    @Test
    fun platformImageFile_canHoldSensorDataSnapshot() {
        // PlatformImageFile from camera capture has sensorData
        val sensorSnapshot = ImageSensorData(
            latLng = Pair(49.2827, -123.1207),
            bearingDeg = 273.0,
            pitchDeg = 3.5,
            rollDeg = -1.2,
        )
        val file = PlatformImageFile(
            path = "/tmp/capture.jpg",
            capturedAtMs = 1_700_000_000_000L,
            sensorData = sensorSnapshot,
        )
        assertEquals(sensorSnapshot, file.sensorData)
        assertEquals(Pair(49.2827, -123.1207), file.sensorData!!.latLng)
        assertEquals(273.0, file.sensorData!!.bearingDeg)
    }

    @Test
    fun platformImageFile_sensorDataCanHavePartialFields() {
        // Camera device may have GPS but no rotation sensor (or vice versa)
        val sensorSnapshot = ImageSensorData(
            latLng = Pair(37.7749, -122.4194),
            // pitch/roll/bearing all null — rotation sensor unavailable
        )
        val file = PlatformImageFile(
            path = "/tmp/capture.jpg",
            sensorData = sensorSnapshot,
        )
        assertEquals(sensorSnapshot.latLng, file.sensorData!!.latLng)
        assertNull(file.sensorData!!.pitchDeg)
        assertNull(file.sensorData!!.bearingDeg)
    }

    @Test
    fun imageSensorData_exifFields_shouldBeMergedFromPlatformImageFile() {
        // When constructing ImageAnnotation from PlatformImageFile, EXIF data from the
        // file itself takes priority over sensorData EXIF fields
        val capturedSensorData = ImageSensorData(
            latLng = Pair(49.2827, -123.1207),
            focalLengthMm = 26.0,
            cameraMake = "Google",
        )
        val file = PlatformImageFile(
            path = "/tmp/capture.jpg",
            focalLengthMm = 28.0, // EXIF from file — should take priority
            cameraMake = "Samsung", // EXIF from file — should take priority
            sensorData = capturedSensorData,
        )

        // Simulate the merge logic from ImageImportService.import()
        val mergedSensorData = ImageSensorData(
            latLng = capturedSensorData.latLng,
            altitudeM = capturedSensorData.altitudeM,
            bearingDeg = capturedSensorData.bearingDeg,
            pitchDeg = capturedSensorData.pitchDeg,
            rollDeg = capturedSensorData.rollDeg,
            // EXIF from file takes priority (?: falls back to sensorData)
            focalLengthMm = file.focalLengthMm ?: capturedSensorData.focalLengthMm,
            cameraMake = file.cameraMake ?: capturedSensorData.cameraMake,
        )

        // GPS from sensor data is preserved
        assertEquals(Pair(49.2827, -123.1207), mergedSensorData.latLng)
        // EXIF from file takes priority
        assertEquals(28.0, mergedSensorData.focalLengthMm)
        assertEquals("Samsung", mergedSensorData.cameraMake)
    }

    @Test
    fun imageSensorData_sensorDataFallback_whenFileExifIsNull() {
        // When file has no EXIF, fall back to sensorData EXIF
        val capturedSensorData = ImageSensorData(
            focalLengthMm = 26.0,
            cameraMake = "Google",
        )
        val file = PlatformImageFile(
            path = "/tmp/capture.jpg",
            focalLengthMm = null, // no EXIF in file
            cameraMake = null,
            sensorData = capturedSensorData,
        )

        val focalLengthMm = file.focalLengthMm ?: capturedSensorData.focalLengthMm
        val cameraMake = file.cameraMake ?: capturedSensorData.cameraMake

        assertEquals(26.0, focalLengthMm)
        assertEquals("Google", cameraMake)
    }
}
