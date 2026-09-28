package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.model.ImageSensorData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [MotionSensorProvider] interface contract and [NoOpMotionSensorProvider].
 *
 * These tests verify:
 * - NoOpMotionSensorProvider behaves correctly on all platforms (commonTest)
 * - [SensorModule.motionSensorProvider] defaults to NoOp
 * - [ImageSensorData] nullable contract is enforced
 */
class MotionSensorProviderTest {

    private val noOp = NoOpMotionSensorProvider()

    @Test
    fun noOpMotionSensorProvider_sensorDataFlow_shouldEmitNothing() = runTest {
        // emptyFlow() should produce no emissions
        val emissions = noOp.sensorDataFlow.toList()
        assertTrue(emissions.isEmpty(), "NoOpMotionSensorProvider should emit no sensor data")
    }

    @Test
    fun noOpMotionSensorProvider_startSensing_shouldNotThrow() {
        // startSensing() is a no-op and must not throw
        noOp.startSensing()
        noOp.startSensing() // idempotent — second call also safe
    }

    @Test
    fun noOpMotionSensorProvider_stopSensing_shouldNotThrow() {
        // stopSensing() is a no-op and must not throw, even when called before start
        noOp.stopSensing()
        noOp.stopSensing() // idempotent — second call also safe
    }

    @Test
    fun noOpMotionSensorProvider_startStop_shouldBeIdempotent() {
        noOp.startSensing()
        noOp.stopSensing()
        noOp.startSensing()
        noOp.stopSensing()
        // No assertions needed — the test passes if no exception is thrown
    }

    @Test
    fun sensorModule_defaultMotionProvider_shouldBeNoOp() {
        // SensorModule defaults to NoOpMotionSensorProvider before platform wiring
        assertTrue(
            actual = SensorModule.motionSensorProvider is NoOpMotionSensorProvider,
            message = "SensorModule should default to NoOpMotionSensorProvider before platform wiring",
        )
    }

    @Test
    fun imageSensorData_allNulls_shouldBeValidDefault() {
        // ImageSensorData with all nulls is valid — sensors may be unavailable
        val data = ImageSensorData()
        assertEquals(null, data.latLng)
        assertEquals(null, data.altitudeM)
        assertEquals(null, data.bearingDeg)
        assertEquals(null, data.pitchDeg)
        assertEquals(null, data.rollDeg)
    }

    @Test
    fun imageSensorData_withGps_shouldHoldCoordinates() {
        val data = ImageSensorData(
            latLng = Pair(49.2827, -123.1207),
            altitudeM = 45.0,
            bearingDeg = 273.0,
            pitchDeg = 5.0,
            rollDeg = -2.0,
        )
        assertEquals(49.2827, data.latLng!!.first)
        assertEquals(-123.1207, data.latLng!!.second)
        assertEquals(45.0, data.altitudeM)
        assertEquals(273.0, data.bearingDeg)
        assertEquals(5.0, data.pitchDeg)
        assertEquals(-2.0, data.rollDeg)
    }

    @Test
    fun imageSensorData_gpsOnlyWithNullOrient_shouldBeValid() {
        // GPS may be available without orientation (e.g. rotation vector sensor denied)
        val data = ImageSensorData(
            latLng = Pair(37.7749, -122.4194),
        )
        assertEquals(37.7749, data.latLng!!.first)
        assertEquals(null, data.bearingDeg)
        assertEquals(null, data.pitchDeg)
        assertEquals(null, data.rollDeg)
    }

    /**
     * A fake mirroring the real pre-[MotionSensorProvider.startSensing] state: a
     * [MutableSharedFlow] with no replay and nothing ever emitted into it, so any collector
     * suspends forever without a bound. This is the exact hang [snapshotSensorData] guards
     * against — see CameraViewfinderDialog.android.kt / AndroidCameraProvider.capturePhoto().
     */
    private class NeverEmittingMotionSensorProvider : MotionSensorProvider {
        override val sensorDataFlow: Flow<ImageSensorData> = MutableSharedFlow()
        override fun startSensing() {}
        override fun stopSensing() {}
    }

    @Test
    fun snapshotSensorData_shouldReturnNull_When_FlowNeverEmits() = runTest {
        val provider = NeverEmittingMotionSensorProvider()

        val result = provider.snapshotSensorData(timeoutMs = 500L)

        assertNull(result, "must bound the snapshot instead of hanging when sensing was never started")
    }

    @Test
    fun snapshotSensorData_shouldReturnLatestReading_When_FlowHasEmitted() = runTest {
        val data = ImageSensorData(bearingDeg = 12.0)
        val flow = MutableSharedFlow<ImageSensorData>(replay = 1)
        flow.emit(data)
        val provider = object : MotionSensorProvider {
            override val sensorDataFlow: Flow<ImageSensorData> = flow
            override fun startSensing() {}
            override fun stopSensing() {}
        }

        val result = provider.snapshotSensorData(timeoutMs = 500L)

        assertEquals(data, result)
    }
}
