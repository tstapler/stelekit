package dev.stapler.stelekit.platform.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.stapler.stelekit.model.ImageSensorData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * Android motion sensor provider using [SensorManager] and the standard [LocationManager].
 *
 * Sensors used:
 * - [Sensor.TYPE_ROTATION_VECTOR]: device orientation from which pitch, roll, and compass
 *   bearing (azimuth) are derived via a rotation matrix.
 * - [LocationManager] with GPS_PROVIDER at ~1 Hz: provides latLng and altitudeM.
 *
 * GPS is best-effort: if [Manifest.permission.ACCESS_FINE_LOCATION] is denied,
 * GPS fields in emitted [ImageSensorData] will be null but orientation sensors still work.
 *
 * Sensor listeners and location callbacks are registered on [startSensing] and
 * unregistered on [stopSensing] — no battery drain when the editor is not active.
 *
 * Target emission rate: approximately 10 Hz (SENSOR_DELAY_UI ≈ 66–100 ms on most devices).
 *
 * Thread safety: [_latestData] is a [MutableStateFlow] — atomic and always holds the most
 * recent combined sensor snapshot. Sensor callbacks may arrive on any thread.
 */
class AndroidMotionSensorProvider(private val context: Context) : MotionSensorProvider {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Internal mutable state — combined from all sensor callbacks
    private val _latestData = MutableStateFlow<ImageSensorData?>(null)

    override val sensorDataFlow: Flow<ImageSensorData> = _latestData.filterNotNull()

    // Current location snapshot — updated by LocationListener callback
    @Volatile
    private var latLng: Pair<Double, Double>? = null

    @Volatile
    private var altitudeM: Double? = null

    // Current orientation snapshot — updated by rotation vector callback
    @Volatile
    private var bearingDeg: Double? = null

    @Volatile
    private var pitchDeg: Double? = null

    @Volatile
    private var rollDeg: Double? = null

    @Volatile
    private var isSensing = false

    // ── Rotation vector sensor listener ──────────────────────────────────────

    private val rotationVectorListener = object : SensorEventListener {
        private val rotationMatrix = FloatArray(9)
        private val orientationAngles = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

            // Compute rotation matrix from the rotation vector sensor values
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            // Get orientation angles: [azimuth, pitch, roll] in radians
            // azimuth  = orientationAngles[0]: device heading (compass bearing)
            // pitch    = orientationAngles[1]: front/back tilt (negative = nose up)
            // roll     = orientationAngles[2]: left/right tilt (positive = right side down)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            pitchDeg = Math.toDegrees(orientationAngles[1].toDouble())
            rollDeg = Math.toDegrees(orientationAngles[2].toDouble())

            // Normalize azimuth to [0, 360)
            val azimuthDeg = (Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0
            bearingDeg = azimuthDeg

            emitCombinedSnapshot()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            // No action needed — accuracy changes are informational only
        }
    }

    // ── Location listener ─────────────────────────────────────────────────────

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latLng = Pair(location.latitude, location.longitude)
            altitudeM = if (location.hasAltitude()) location.altitude else null
            emitCombinedSnapshot()
        }

        @Deprecated("Deprecated in LocationListener")
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
            // Deprecated in API 29+ — no action needed
        }

        override fun onProviderEnabled(provider: String) {
            // No action needed
        }

        override fun onProviderDisabled(provider: String) {
            // GPS was disabled by the user — clear location data
            latLng = null
            altitudeM = null
            emitCombinedSnapshot()
        }
    }

    // ── MotionSensorProvider implementation ───────────────────────────────────

    override fun startSensing() {
        if (isSensing) return
        isSensing = true

        // Register rotation vector sensor (fuses accelerometer + gyroscope + magnetometer
        // for the best-quality orientation data on Android)
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor != null) {
            sensorManager.registerListener(
                rotationVectorListener,
                rotationSensor,
                SensorManager.SENSOR_DELAY_UI, // ~60ms ≈ 16 Hz; OS may deliver slower
            )
        }

        // Start GPS location updates if ACCESS_FINE_LOCATION permission is granted.
        // Graceful fallback: if permission is denied, latLng/altitudeM remain null.
        if (hasLocationPermission()) {
            startLocationUpdates()
        }

        // Emit initial snapshot (all fields may be null until sensors deliver data)
        emitCombinedSnapshot()
    }

    override fun stopSensing() {
        if (!isSensing) return
        isSensing = false

        sensorManager.unregisterListener(rotationVectorListener)

        // Unconditionally attempt to remove location updates — safe even if
        // they were never registered (LocationManager ignores unknown listeners)
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
            // SecurityException can occur on some devices if permission was revoked
            // between startSensing() and stopSensing(). Safe to swallow.
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission") // Permission is checked in hasLocationPermission() before call
    private fun startLocationUpdates() {
        try {
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (isGpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1_000L, // minTimeMs: 1 Hz
                    0f,     // minDistanceMeters: emit on every update regardless of movement
                    locationListener,
                    Looper.getMainLooper(),
                )
            }
            // Also request NETWORK provider as a lower-accuracy fallback (works indoors)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2_000L, // 0.5 Hz — network location drains less battery than GPS
                    0f,
                    locationListener,
                    Looper.getMainLooper(),
                )
            }
        } catch (_: SecurityException) {
            // Permission was revoked between hasLocationPermission() and this call.
            // GPS fields will remain null — safe to continue without location.
        }
    }

    /**
     * Combine all sensor snapshots into a single [ImageSensorData] and emit to [sensorDataFlow].
     *
     * Called from sensor/location callbacks — must be fast; no blocking IO here.
     */
    private fun emitCombinedSnapshot() {
        _latestData.value = ImageSensorData(
            latLng = latLng,
            altitudeM = altitudeM,
            bearingDeg = bearingDeg,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
        )
    }
}
