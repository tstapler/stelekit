package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.model.ImageSensorData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Abstraction over platform motion and location sensors.
 *
 * Provides a continuous stream of [ImageSensorData] during active sensing, covering:
 * - GPS coordinates via [ImageSensorData.latLng] and [ImageSensorData.altitudeM]
 * - Compass bearing via [ImageSensorData.bearingDeg]
 * - Accelerometer pitch/roll via [ImageSensorData.pitchDeg] and [ImageSensorData.rollDeg]
 *
 * Platform implementations:
 * - Android: [AndroidMotionSensorProvider] (SensorManager + FusedLocationProviderClient)
 * - iOS: [IOSMotionSensorProvider] (CMMotionManager + CLLocationManager stub)
 * - JVM desktop / WASM: [NoOpMotionSensorProvider] (empty Flow — no motion sensors)
 *
 * Register the platform implementation in [SensorModule] at app startup.
 *
 * All sensor data is best-effort: fields in [ImageSensorData] are null when the
 * corresponding sensor was unavailable or permission was denied. This provider
 * never throws exceptions for missing sensor data.
 *
 * Lifecycle contract:
 * - Call [startSensing] when entering capture mode (e.g. camera open, annotation editor open).
 * - Call [stopSensing] when leaving capture mode (e.g. app backgrounded, editor closed).
 * - [sensorDataFlow] emits only while sensing is active; it emits nothing before
 *   [startSensing] is called and after [stopSensing] is called.
 */
interface MotionSensorProvider {

    /**
     * A hot [Flow] of sensor readings during active sensing.
     *
     * Target emission rate: 10 Hz on Android/iOS. On JVM/WASM this is [emptyFlow].
     * Each emission is a snapshot of all available sensor data at that instant.
     * Fields are null when the sensor is unavailable or permission is denied.
     *
     * The flow is cold until [startSensing] is called. Collectors may miss emissions
     * between [stopSensing] and the next [startSensing] call.
     */
    val sensorDataFlow: Flow<ImageSensorData>

    /**
     * Begin sensor sampling. Safe to call multiple times (idempotent).
     *
     * On Android: registers [SensorManager] listeners and requests location updates.
     * GPS requires [android.Manifest.permission.ACCESS_FINE_LOCATION]; if denied, GPS
     * fields in emitted [ImageSensorData] will be null but other sensors still work.
     */
    fun startSensing()

    /**
     * Stop sensor sampling and release hardware resources. Safe to call multiple times (idempotent).
     *
     * Must be called when the app backgrounds or the owning screen is dismissed to
     * prevent battery drain and sensor resource leaks.
     */
    fun stopSensing()
}

/**
 * Snapshot the most recent sensor reading, bounded by [timeoutMs].
 *
 * A camera capture path must never hang waiting on sensor data. If [startSensing] was never
 * called (or the provider is otherwise stalled), [sensorDataFlow] never emits and a bare
 * `sensorDataFlow.firstOrNull()` would suspend forever. This returns `null` instead once
 * [timeoutMs] elapses. Shared by both Android capture call sites
 * ([dev.stapler.stelekit.ui.components.CameraViewfinderDialog] and [AndroidCameraProvider])
 * so the bound can't drift between the two.
 */
suspend fun MotionSensorProvider.snapshotSensorData(timeoutMs: Long = 500L): ImageSensorData? =
    withTimeoutOrNull(timeoutMs) { sensorDataFlow.firstOrNull() }

/**
 * No-op motion sensor provider for JVM desktop and WASM targets.
 *
 * These platforms have no IMU or GPS hardware accessible from Kotlin/JVM code.
 * All captured images will have null sensor fields.
 */
class NoOpMotionSensorProvider : MotionSensorProvider {

    override val sensorDataFlow: Flow<ImageSensorData> = emptyFlow()

    override fun startSensing() {
        // No-op: no sensors available on this platform
    }

    override fun stopSensing() {
        // No-op: no sensors to stop
    }
}
