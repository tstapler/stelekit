package dev.stapler.stelekit.platform.measurement

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.platform.measurement.keyboard.KeyboardEmulationDevice
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for Story 5.7: BLE measurement injection into annotation and calibration.
 *
 * Uses a fake [ExternalMeasurementDevice] that emits programmatically controlled readings.
 */
class MeasurementInjectionTest {

    // ── Fake device ───────────────────────────────────────────────────────────

    private class FakeMeasurementDevice(
        override val deviceName: String = "Test Laser",
        override val deviceId: String = "test-laser-001",
    ) : ExternalMeasurementDevice {

        private val _connectionState = MutableStateFlow(DeviceConnectionState.CONNECTED)
        override val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

        private val _readings = MutableSharedFlow<MeasurementReading>(extraBufferCapacity = 16)

        override suspend fun connect(): Either<DomainError, Unit> {
            _connectionState.value = DeviceConnectionState.CONNECTED
            return Unit.right()
        }

        override suspend fun disconnect() {
            _connectionState.value = DeviceConnectionState.DISCONNECTED
        }

        override fun measurementFlow(): Flow<MeasurementReading> = _readings

        fun emitReading(valueMeters: Double) {
            _readings.tryEmit(
                MeasurementReading(valueMeters = valueMeters, deviceId = deviceId)
            )
        }
    }

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun makeImageAnnotation(ppm: Double = 100.0) = ImageAnnotation(
        uuid = "img-001",
        blockUuid = "blk-001",
        pageUuid = "page-001",
        graphPath = "/test",
        filePath = "/test/image.jpg",
        calibration = Calibration(
            method = CalibrationMethod.NONE,
            pixelsPerMeter = ppm,
            confidencePercent = 0,
        ),
        unit = MeasurementUnit.METERS,
    )

    private fun makeViewModel(): AnnotationEditorViewModel {
        val repo = InMemoryMeasurementAnnotationRepository()
        return AnnotationEditorViewModel(
            measurementRepository = repo,
            imageWidthPx = 1000.0,
            imageHeightPx = 1000.0,
        ).also { it.initialize(makeImageAnnotation()) }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `setActiveDevice wires device connection state into viewModel`() = runTest {
        val vm = makeViewModel()
        val device = FakeMeasurementDevice()

        vm.setActiveDevice(device)

        assertEquals(DeviceConnectionState.CONNECTED, vm.deviceConnectionState.value)
    }

    @Test
    fun `deviceConnectionState is DISCONNECTED when no device set`() = runTest {
        val vm = makeViewModel()
        assertEquals(DeviceConnectionState.DISCONNECTED, vm.deviceConnectionState.value)
    }

    @Test
    fun `injectMeasurementFromDevice updates annotation valueMeters from BLE reading`() {
        // injectMeasurementFromDevice() is non-suspending — it launches a coroutine on the
        // ViewModel's internal Dispatchers.Default scope. We call it directly (no extra launch
        // wrapper), emit the reading a tick later, then give Dispatchers.Default time to finish.
        val vm = makeViewModel()
        val device = FakeMeasurementDevice(deviceId = "leica-test")
        vm.setActiveDevice(device)

        kotlinx.coroutines.runBlocking {
            // Add a distance annotation (two points 0.1 apart on 1000px image = 100px).
            // A 2.0m reading → pixelsPerMeter = 100/2.0 = 50 px/m
            vm.addPoint(NormalizedPoint(0.1, 0.5))
            vm.addPoint(NormalizedPoint(0.2, 0.5))
            kotlinx.coroutines.delay(300) // allow commit coroutine to save to repo

            // Verify an annotation was committed.
            assertEquals(1, vm.state.value.committedAnnotations.size, "Expected 1 committed annotation")

            // Start injection — this synchronously launches a coroutine on Dispatchers.Default
            // that is now blocking on device.measurementFlow().first().
            vm.injectMeasurementFromDevice()
            // Give the VM coroutine time to reach .first() before we emit.
            kotlinx.coroutines.delay(100)
            device.emitReading(2.0)
            // Allow the injection + updateCalibration coroutines to complete on Dispatchers.Default.
            kotlinx.coroutines.delay(500)
        }

        // Verify calibration updated to BLE_LASER
        val calibration = vm.state.value.calibration
        assertNotNull(calibration, "Calibration should not be null after injection")
        assertEquals(CalibrationMethod.BLE_LASER, calibration.method)
        assertEquals(100, calibration.confidencePercent)

        // Verify the annotation was updated with BLE device ID
        val annotation = vm.state.value.committedAnnotations.last()
        assertEquals("leica-test", annotation.bleDeviceId)
    }

    @Test
    fun `injectMeasurementFromDevice updates calibration pixelsPerMeter correctly`() {
        val vm = makeViewModel()
        val device = FakeMeasurementDevice()
        vm.setActiveDevice(device)

        kotlinx.coroutines.runBlocking {
            // 100px line (0.1 of 1000px image width)
            vm.addPoint(NormalizedPoint(0.0, 0.5))
            vm.addPoint(NormalizedPoint(0.1, 0.5))
            kotlinx.coroutines.delay(300)

            vm.injectMeasurementFromDevice()
            kotlinx.coroutines.delay(100)
            device.emitReading(1.0) // 100px = 1.0m → 100 px/m
            kotlinx.coroutines.delay(500)
        }

        val calibration = vm.state.value.calibration
        assertNotNull(calibration)
        assertEquals(100.0, calibration.pixelsPerMeter, 0.001)
    }

    @Test
    fun `KeyboardEmulationDevice emits reading on submitText`() {
        val device = KeyboardEmulationDevice()
        val vm = makeViewModel()
        vm.setActiveDevice(device)

        kotlinx.coroutines.runBlocking {
            device.connect()

            // Add a distance annotation (100px line on 1000px-wide image)
            vm.addPoint(NormalizedPoint(0.0, 0.5))
            vm.addPoint(NormalizedPoint(0.1, 0.5))
            kotlinx.coroutines.delay(300) // allow commit to complete

            assertEquals(1, vm.state.value.committedAnnotations.size)

            // Start injection — synchronously launches a coroutine on Dispatchers.Default
            // that blocks on measurementFlow().first().
            vm.injectMeasurementFromDevice()
            kotlinx.coroutines.delay(100) // give VM coroutine time to reach .first()
            device.submitText("1.5 m")
            kotlinx.coroutines.delay(500) // allow updateCalibration coroutine to complete
        }

        val calibration = vm.state.value.calibration
        assertNotNull(calibration)
        assertEquals(CalibrationMethod.BLE_LASER, calibration.method)
    }

    @Test
    fun `injectMeasurementFromDevice is no-op when no device set`() {
        val vm = makeViewModel()

        kotlinx.coroutines.runBlocking {
            // Add an annotation
            vm.addPoint(NormalizedPoint(0.0, 0.5))
            vm.addPoint(NormalizedPoint(0.1, 0.5))
            kotlinx.coroutines.delay(200)
        }

        // No device set — injectMeasurementFromDevice should return immediately (no-op)
        vm.injectMeasurementFromDevice()
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        // Calibration should remain unchanged (NONE)
        val calibration = vm.state.value.calibration
        assertEquals(null, calibration?.method?.let { if (it != CalibrationMethod.NONE) it else null })
    }

    @Test
    fun `injectMeasurementFromDevice is no-op when no distance annotation committed`() {
        val vm = makeViewModel()
        val device = FakeMeasurementDevice()
        vm.setActiveDevice(device)

        // No annotations added — injection should be a no-op (returns immediately since
        // lastOrNull returns null and the function returns early)
        vm.injectMeasurementFromDevice()
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        // State should be unchanged
        assertEquals(emptyList(), vm.state.value.committedAnnotations)
    }
}
