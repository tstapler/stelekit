package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.model.ImageSensorData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * iOS motion sensor provider stub.
 *
 * Full implementation requires bridging to:
 * - CoreMotion.CMMotionManager — for pitch, roll, and device motion
 * - CoreLocation.CLLocationManager — for GPS coordinates and compass bearing
 *
 * These frameworks use ObjC/Swift APIs that require native bridging via
 * Kotlin/Native interop. The full implementation is deferred to a later story
 * when the iOS native bridging layer is in place.
 *
 * Current behavior: returns [emptyFlow] — all sensor fields will be null in
 * captured images on iOS until the full implementation is added.
 *
 * Future implementation notes:
 * - CMMotionManager.startDeviceMotionUpdates(to:withHandler:) for pitch/roll
 * - CLLocationManager.requestLocation() for GPS (requires NSLocationWhenInUseUsageDescription)
 * - CLLocationManager.startUpdatingHeading() for compass bearing
 * - Combine all three into a merged Flow using callbackFlow { }
 * - Rate-limit to ~10 Hz via a conflated channel or distinctUntilChanged + throttleFirst
 */
class IOSMotionSensorProvider : MotionSensorProvider {

    override val sensorDataFlow: Flow<ImageSensorData> = emptyFlow()

    override fun startSensing() {
        // Stub: full CMMotionManager + CLLocationManager implementation pending
    }

    override fun stopSensing() {
        // Stub: no listeners to unregister
    }
}
