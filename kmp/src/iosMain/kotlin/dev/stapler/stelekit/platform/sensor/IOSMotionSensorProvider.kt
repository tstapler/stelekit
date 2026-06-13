package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.model.ImageSensorData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreMotion.CMDeviceMotion
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSError
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObject
import kotlin.math.PI

/**
 * iOS motion and location sensor provider using CoreMotion and CoreLocation.
 *
 * Provides ~10 Hz [ImageSensorData] emissions combining:
 * - Pitch and roll (degrees) from [CMMotionManager.deviceMotion.attitude]
 * - GPS coordinates, altitude, and compass bearing from [CLLocationManager]
 *
 * Info.plist requirements (must be added to the iOS app target manually):
 * - `NSLocationWhenInUseUsageDescription` — required for [CLLocationManager] authorization
 * - `NSMotionUsageDescription` — required for [CMMotionManager] device motion
 *
 * Lifecycle contract:
 * - Call [startSensing] when entering capture mode; [stopSensing] when leaving.
 * - The [sensorDataFlow] begins emitting after [startSensing] and stops after [stopSensing].
 */
class IOSMotionSensorProvider : MotionSensorProvider, NSObject(), CLLocationManagerDelegateProtocol {

    private val motionManager = CMMotionManager()
    private val locationManager = CLLocationManager()

    /**
     * Shared mutable state updated independently by CMMotionManager (at ~10 Hz) and
     * CLLocationManager (on each location fix). Downstream collectors see the merged
     * snapshot on every motion update.
     */
    private val _state = MutableStateFlow(ImageSensorData())

    override val sensorDataFlow: Flow<ImageSensorData> = callbackFlow {
        // Forward every state update into the channel so collectors receive merged snapshots.
        _state.collect { trySend(it) }
        awaitClose { /* state collection cancelled when channel closes */ }
    }.distinctUntilChanged()

    override fun startSensing() {
        // ── CoreMotion ────────────────────────────────────────────────────────────
        if (motionManager.isDeviceMotionAvailable) {
            motionManager.deviceMotionUpdateInterval = 0.1 // 10 Hz
            motionManager.startDeviceMotionUpdatesToQueue(NSOperationQueue.mainQueue) { motion: CMDeviceMotion?, _: NSError? ->
                motion?.attitude?.let { attitude ->
                    val pitchDeg = attitude.pitch * (180.0 / PI)
                    val rollDeg = attitude.roll * (180.0 / PI)
                    _state.update { it.copy(pitchDeg = pitchDeg, rollDeg = rollDeg) }
                }
            }
        }

        // ── CoreLocation ──────────────────────────────────────────────────────────
        locationManager.delegate = this
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = kCLDistanceFilterNone
        locationManager.headingFilter = 1.0 // emit heading updates only when bearing changes ≥1°

        val authStatus = CLLocationManager.authorizationStatus()
        if (authStatus == kCLAuthorizationStatusNotDetermined) {
            locationManager.requestWhenInUseAuthorization()
        }
        if (authStatus == kCLAuthorizationStatusAuthorizedWhenInUse ||
            authStatus == kCLAuthorizationStatusNotDetermined
        ) {
            locationManager.startUpdatingLocation()
            locationManager.startUpdatingHeading()
        }
    }

    override fun stopSensing() {
        if (motionManager.isDeviceMotionActive) {
            motionManager.stopDeviceMotionUpdates()
        }
        locationManager.stopUpdatingLocation()
        locationManager.stopUpdatingHeading()
    }

    // ── CLLocationManagerDelegate ────────────────────────────────────────────────

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location = (didUpdateLocations.lastOrNull() as? CLLocation) ?: return
        val lat = location.coordinate.useContents { latitude }
        val lng = location.coordinate.useContents { longitude }
        _state.update { current ->
            current.copy(
                latLng = Pair(lat, lng),
                altitudeM = location.altitude,
            )
        }
    }

    override fun locationManager(manager: CLLocationManager, didUpdateHeading: platform.CoreLocation.CLHeading) {
        val bearing = didUpdateHeading.trueHeading.takeIf { it >= 0 }
            ?: didUpdateHeading.magneticHeading
        _state.update { it.copy(bearingDeg = bearing) }
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        // Location failure is non-fatal — GPS fields remain null in the next emission.
    }
}
