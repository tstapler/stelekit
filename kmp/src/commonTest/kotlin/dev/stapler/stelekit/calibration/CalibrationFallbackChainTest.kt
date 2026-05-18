package dev.stapler.stelekit.calibration

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageSensorData
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.platform.ml.MonocularDepthEstimator
import dev.stapler.stelekit.platform.sensor.DepthSensorProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [CalibrationFallbackChain] ordering and behavior.
 *
 * All tests use mocked providers to verify that the chain respects priority order
 * and logs each skip appropriately.
 */
class CalibrationFallbackChainTest {

    // ── Mock helpers ──────────────────────────────────────────────────────────

    private fun noOpDepth() = object : DepthSensorProvider {
        override val isAvailable = false
        override suspend fun acquireDepthFrame(): Either<DomainError.SensorError, DepthFrame?> = null.right()
    }

    private fun availableDepthWith(depthMm: Float, confidence: Float) = object : DepthSensorProvider {
        override val isAvailable = true
        override suspend fun acquireDepthFrame(): Either<DomainError.SensorError, DepthFrame?> {
            val w = 10; val h = 10
            return DepthFrame(
                depthMapMm = FloatArray(w * h) { depthMm },
                confidenceMap = FloatArray(w * h) { confidence },
                width = w,
                height = h,
            ).right()
        }
    }

    private fun failingDepth() = object : DepthSensorProvider {
        override val isAvailable = true
        override suspend fun acquireDepthFrame(): Either<DomainError.SensorError, DepthFrame?> =
            DomainError.SensorError.CaptureFailed("test failure").left()
    }

    private fun noOpML() = object : MonocularDepthEstimator {
        override val isAvailable = false
        override suspend fun initialize(): Either<DomainError, Unit> =
            DomainError.SensorError.HardwareUnavailable("ML").left()
        override suspend fun estimateDepth(imageBitmap: androidx.compose.ui.graphics.ImageBitmap): Either<DomainError, FloatArray> =
            DomainError.SensorError.HardwareUnavailable("ML").left()
    }

    // ── Priority ordering ─────────────────────────────────────────────────────

    @Test
    fun `BLE calibration wins when provided`() = runTest {
        val ble = Calibration(CalibrationMethod.BLE_LASER, 1000.0, 100)
        val chain = CalibrationFallbackChain(noOpDepth(), noOpML())
        val result = chain.resolve(bleCalibration = ble)
        assertEquals(CalibrationMethod.BLE_LASER, result.method)
    }

    @Test
    fun `manual reference wins when BLE absent`() = runTest {
        val manual = Calibration(CalibrationMethod.MANUAL_REFERENCE, 500.0, 100)
        val chain = CalibrationFallbackChain(noOpDepth(), noOpML())
        val result = chain.resolve(manualCalibration = manual)
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, result.method)
    }

    @Test
    fun `BLE wins over manual reference when both provided`() = runTest {
        val ble = Calibration(CalibrationMethod.BLE_LASER, 1000.0, 100)
        val manual = Calibration(CalibrationMethod.MANUAL_REFERENCE, 500.0, 100)
        val chain = CalibrationFallbackChain(noOpDepth(), noOpML())
        val result = chain.resolve(bleCalibration = ble, manualCalibration = manual)
        assertEquals(CalibrationMethod.BLE_LASER, result.method)
    }

    @Test
    fun `ARCore wins when BLE and manual absent`() = runTest {
        val depthProvider = availableDepthWith(2000f, 200f) // 2m depth, 78% confidence
        val chain = CalibrationFallbackChain(depthProvider, noOpML())
        val result = chain.resolve(
            imageWidthPx = 1000.0,
            depthTapPoint = NormalizedPoint(0.5, 0.5),
        )
        assertEquals(CalibrationMethod.ARCORE_DEPTH, result.method)
    }

    @Test
    fun `EXIF wins when BLE manual and ARCore absent`() = runTest {
        val sensorData = ImageSensorData(focalLength35mmEq = 24.0)
        val chain = CalibrationFallbackChain(noOpDepth(), noOpML())
        val result = chain.resolve(
            sensorData = sensorData,
            imageWidthPx = 4000.0,
        )
        assertEquals(CalibrationMethod.EXIF_FOCAL, result.method)
    }

    @Test
    fun `NONE returned when all methods fail`() = runTest {
        val chain = CalibrationFallbackChain(noOpDepth(), noOpML())
        val result = chain.resolve()
        assertEquals(CalibrationMethod.NONE, result.method)
        assertEquals(0, result.confidencePercent)
    }

    // ── Fault tolerance ────────────────────────────────────────────────────────

    @Test
    fun `failing depth sensor falls through to EXIF`() = runTest {
        val sensorData = ImageSensorData(focalLength35mmEq = 24.0)
        val chain = CalibrationFallbackChain(failingDepth(), noOpML())
        val result = chain.resolve(
            sensorData = sensorData,
            imageWidthPx = 4000.0,
            depthTapPoint = NormalizedPoint(0.5, 0.5),
        )
        // ARCore failed, should fall through to EXIF
        assertEquals(CalibrationMethod.EXIF_FOCAL, result.method)
    }

    @Test
    fun `depth sensor with zero depth falls through to EXIF`() = runTest {
        val depthProvider = availableDepthWith(0f, 255f) // zero depth everywhere
        val sensorData = ImageSensorData(focalLength35mmEq = 24.0)
        val chain = CalibrationFallbackChain(depthProvider, noOpML())
        val result = chain.resolve(
            sensorData = sensorData,
            imageWidthPx = 4000.0,
            depthTapPoint = NormalizedPoint(0.5, 0.5),
        )
        assertEquals(CalibrationMethod.EXIF_FOCAL, result.method)
    }

    @Test
    fun `NONE result has zero pixelsPerMeter`() = runTest {
        val chain = CalibrationFallbackChain(noOpDepth(), noOpML())
        val result = chain.resolve()
        assertEquals(0.0, result.pixelsPerMeter)
    }
}
