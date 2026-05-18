package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.model.ImageSensorData
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for GPS tagging and tilt warning logic derived from [ImageSensorData].
 *
 * Story 8.2: GPS coordinate presence in sensor data
 * Story 8.4: Tilt warning threshold (pitch > 15° or roll > 10°)
 */
class GpsTaggingTest {

    // ── Story 8.2: GPS tagging ────────────────────────────────────────────────

    @Test
    fun gpsTagging_latLngPresent_whenSensorDataHasCoordinates() {
        val data = ImageSensorData(
            latLng = Pair(49.2827, -123.1207),
            altitudeM = 15.0,
        )
        assertTrue(data.latLng != null, "latLng should be set when GPS is available")
    }

    @Test
    fun gpsTagging_latLngNull_whenNoGps() {
        val data = ImageSensorData()
        assertFalse(data.latLng != null, "latLng should be null when GPS is unavailable")
    }

    @Test
    fun gpsTagging_altitudeOptional_whenLatLngPresent() {
        // Altitude may be null even when lat/lng are available (some devices)
        val data = ImageSensorData(
            latLng = Pair(37.7749, -122.4194),
            altitudeM = null,
        )
        assertTrue(data.latLng != null)
        assertFalse(data.altitudeM != null)
    }

    // ── Story 8.4: Tilt warning thresholds ───────────────────────────────────

    @Test
    fun tiltWarning_notTriggered_whenPitchAndRollAreSmall() {
        val data = ImageSensorData(pitchDeg = 5.0, rollDeg = 3.0)
        val isTiltExcessive = isTiltExcessive(data)
        assertFalse(isTiltExcessive, "Tilt warning should not trigger for small angles")
    }

    @Test
    fun tiltWarning_triggered_whenPitchExceedsThreshold() {
        val data = ImageSensorData(pitchDeg = 20.0, rollDeg = 0.0)
        val isTiltExcessive = isTiltExcessive(data)
        assertTrue(isTiltExcessive, "Tilt warning should trigger when |pitch| > 15°")
    }

    @Test
    fun tiltWarning_triggered_whenNegativePitchExceedsThreshold() {
        val data = ImageSensorData(pitchDeg = -16.0, rollDeg = 0.0)
        val isTiltExcessive = isTiltExcessive(data)
        assertTrue(isTiltExcessive, "Tilt warning should trigger when |pitch| > 15° (negative)")
    }

    @Test
    fun tiltWarning_triggered_whenRollExceedsThreshold() {
        val data = ImageSensorData(pitchDeg = 0.0, rollDeg = 11.0)
        val isTiltExcessive = isTiltExcessive(data)
        assertTrue(isTiltExcessive, "Tilt warning should trigger when |roll| > 10°")
    }

    @Test
    fun tiltWarning_triggered_whenNegativeRollExceedsThreshold() {
        val data = ImageSensorData(pitchDeg = 0.0, rollDeg = -15.0)
        val isTiltExcessive = isTiltExcessive(data)
        assertTrue(isTiltExcessive, "Tilt warning should trigger when |roll| > 10° (negative)")
    }

    @Test
    fun tiltWarning_notTriggered_exactlyAtThreshold() {
        // Threshold is STRICT (> 15, > 10), not >=
        val data = ImageSensorData(pitchDeg = 15.0, rollDeg = 10.0)
        val isTiltExcessive = isTiltExcessive(data)
        assertFalse(isTiltExcessive, "Tilt warning should not trigger at exactly the threshold")
    }

    @Test
    fun tiltWarning_notTriggered_whenBothAreNull() {
        // Both pitch and roll null means sensor data was unavailable — no warning
        val data = ImageSensorData(pitchDeg = null, rollDeg = null)
        val isTiltExcessive = isTiltExcessive(data)
        assertFalse(isTiltExcessive, "Tilt warning should not trigger when sensor data is null")
    }

    @Test
    fun tiltWarning_notTriggered_whenSensorDataIsNull() {
        val isTiltExcessive: Boolean = null.let { sd: ImageSensorData? ->
            sd != null &&
                ((sd.pitchDeg != null && abs(sd.pitchDeg) > 15.0) ||
                    (sd.rollDeg != null && abs(sd.rollDeg) > 10.0))
        }
        assertFalse(isTiltExcessive, "Null sensor data should not trigger tilt warning")
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Mirrors the tilt-excessive check in [AnnotationEditorScreen].
     * Extracted here so the logic can be tested without Compose.
     */
    private fun isTiltExcessive(data: ImageSensorData): Boolean =
        (data.pitchDeg != null && abs(data.pitchDeg) > 15.0) ||
            (data.rollDeg != null && abs(data.rollDeg) > 10.0)
}
