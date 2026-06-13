package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageSensorData
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [ExifCalibrationService.estimate].
 *
 * Reference device EXIF values:
 * - Google Pixel 8: focal length = 6.81 mm, 35mm equivalent = 24 mm
 * - Samsung Galaxy S24: focal length = 6.3 mm, 35mm equivalent = 22 mm
 *
 * The pinhole camera model used:
 *   horizontalFOV/2 = atan(36 / (2 * focal35mm))
 *   pixelsPerMeter = imageWidth / (2 * depth * tan(fov/2))
 */
class ExifCalibrationServiceTest {

    // ── Google Pixel 8 ────────────────────────────────────────────────────────

    @Test
    fun `Pixel 8 EXIF at 2m depth produces sensible pixelsPerMeter`() {
        // Pixel 8 main camera: 24mm equivalent
        val sensorData = ImageSensorData(
            focalLengthMm = 6.81,
            focalLength35mmEq = 24.0,
        )
        val cal = ExifCalibrationService.estimate(sensorData, imageWidthPx = 4000.0, depthHintMeters = 2.0)
        assertNotNull(cal)
        assertEquals(CalibrationMethod.EXIF_FOCAL, cal.method)
        assertEquals(20, cal.confidencePercent)
        // Sanity: at 2m with 24mm equiv, ~500–2000 px/m range is plausible for 4000px wide image
        assert(cal.pixelsPerMeter > 100.0) { "pixelsPerMeter should be positive and reasonable: ${cal.pixelsPerMeter}" }
        assert(cal.pixelsPerMeter < 10_000.0) { "pixelsPerMeter too large: ${cal.pixelsPerMeter}" }
    }

    @Test
    fun `Pixel 8 EXIF exact formula verification`() {
        // Pixel 8: 35mm equiv = 24 mm, depth = 2 m, imageWidth = 4032 px
        // fovHalf = atan(36 / (2 * 24)) = atan(0.75)
        // tan(fovHalf) = 0.75
        // pixelsPerMeter = 4032 / (2 * 2 * 0.75) = 4032 / 3 = 1344
        val sensorData = ImageSensorData(
            focalLengthMm = 6.81,
            focalLength35mmEq = 24.0,
        )
        val cal = ExifCalibrationService.estimate(sensorData, imageWidthPx = 4032.0, depthHintMeters = 2.0)
        assertNotNull(cal)
        // Expected: 4032 / (2 * 2 * tan(atan(0.75))) = 4032 / 3 = 1344
        assertApprox(1344.0, cal.pixelsPerMeter, tolerance = 1.0)
    }

    // ── Samsung Galaxy S24 ────────────────────────────────────────────────────

    @Test
    fun `Samsung S24 EXIF at 2m depth produces sensible pixelsPerMeter`() {
        // S24 wide camera: 22mm equivalent
        val sensorData = ImageSensorData(
            focalLengthMm = 6.3,
            focalLength35mmEq = 22.0,
        )
        val cal = ExifCalibrationService.estimate(sensorData, imageWidthPx = 4000.0, depthHintMeters = 2.0)
        assertNotNull(cal)
        assertEquals(CalibrationMethod.EXIF_FOCAL, cal.method)
        assertEquals(20, cal.confidencePercent)
        assert(cal.pixelsPerMeter > 100.0)
        assert(cal.pixelsPerMeter < 10_000.0)
    }

    @Test
    fun `Samsung S24 EXIF exact formula verification`() {
        // S24: 35mm equiv = 22 mm, depth = 3 m, imageWidth = 4000 px
        // fovHalf = atan(36 / (2 * 22)) = atan(36/44) = atan(0.81818...)
        // tan(fovHalf) = 0.81818...
        // pixelsPerMeter = 4000 / (2 * 3 * 0.81818...) = 4000 / 4.909... ≈ 814.8
        val sensorData = ImageSensorData(
            focalLengthMm = 6.3,
            focalLength35mmEq = 22.0,
        )
        val cal = ExifCalibrationService.estimate(sensorData, imageWidthPx = 4000.0, depthHintMeters = 3.0)
        assertNotNull(cal)
        // tan(atan(36/44)) = 36/44 exactly, so:
        // 4000 / (2 * 3 * (36/44)) = 4000 * 44 / (6 * 36) = 176000 / 216 ≈ 814.81
        assertApprox(814.81, cal.pixelsPerMeter, tolerance = 1.0)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `no focal length data returns null`() {
        val sensorData = ImageSensorData(
            focalLengthMm = null,
            focalLength35mmEq = null,
        )
        val cal = ExifCalibrationService.estimate(sensorData, imageWidthPx = 4000.0)
        assertNull(cal)
    }

    @Test
    fun `zero imageWidth returns null`() {
        val sensorData = ImageSensorData(focalLength35mmEq = 24.0)
        val cal = ExifCalibrationService.estimate(sensorData, imageWidthPx = 0.0)
        assertNull(cal)
    }

    @Test
    fun `negative depthHint returns null`() {
        val sensorData = ImageSensorData(focalLength35mmEq = 24.0)
        val cal = ExifCalibrationService.estimate(sensorData, imageWidthPx = 4000.0, depthHintMeters = -1.0)
        assertNull(cal)
    }

    @Test
    fun `null depthHint falls back to 2m default`() {
        val sensorData = ImageSensorData(focalLength35mmEq = 24.0)
        val calDefault = ExifCalibrationService.estimate(sensorData, imageWidthPx = 4000.0, depthHintMeters = null)
        val calExplicit2m = ExifCalibrationService.estimate(sensorData, imageWidthPx = 4000.0, depthHintMeters = 2.0)
        assertNotNull(calDefault)
        assertNotNull(calExplicit2m)
        assertApprox(calExplicit2m.pixelsPerMeter, calDefault.pixelsPerMeter, tolerance = 0.001)
    }

    @Test
    fun `uses actual focal length with 6_4mm sensor when 35mm equiv absent`() {
        // Actual focal = 6.4 mm, sensor width = 6.4 mm (assumed default in ExifCalibrationService)
        // fovHalf = atan(6.4 / (2 * 6.4)) = atan(0.5)
        // tan(atan(0.5)) = 0.5
        // pixelsPerMeter = 1000 / (2 * 1.0 * 0.5) = 1000
        val sensorData = ImageSensorData(
            focalLengthMm = 6.4,
            focalLength35mmEq = null,
        )
        val cal = ExifCalibrationService.estimate(sensorData, imageWidthPx = 1000.0, depthHintMeters = 1.0)
        assertNotNull(cal)
        assertApprox(1000.0, cal.pixelsPerMeter, tolerance = 0.01)
    }
}

// ── Test helper ───────────────────────────────────────────────────────────────

private fun assertApprox(expected: Double, actual: Double, tolerance: Double) {
    val diff = abs(expected - actual)
    if (diff > tolerance) {
        throw AssertionError("Expected $expected ±$tolerance but was $actual (diff=$diff)")
    }
}
