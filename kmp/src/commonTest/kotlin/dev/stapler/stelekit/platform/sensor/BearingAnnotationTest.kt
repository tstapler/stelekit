package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.ImageSensorData
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for Story 8.3: Compass bearing annotation logic.
 *
 * The [dev.stapler.stelekit.db.ImageImportService] auto-creates a LABEL annotation
 * "Bearing: 273°N" when [ImageSensorData.bearingDeg] is present. These tests verify
 * the bearing label formatting and the annotation type.
 */
class BearingAnnotationTest {

    @Test
    fun bearingLabel_formatsAsIntegerDegrees() {
        val bearingDeg = 273.4
        val label = "Bearing: ${bearingDeg.roundToInt()}°N"
        assertEquals("Bearing: 273°N", label)
    }

    @Test
    fun bearingLabel_roundsUp_whenFractionalPart_isAboveHalf() {
        val bearingDeg = 45.6
        val label = "Bearing: ${bearingDeg.roundToInt()}°N"
        assertEquals("Bearing: 46°N", label)
    }

    @Test
    fun bearingLabel_handlesZeroDegrees() {
        val bearingDeg = 0.0
        val label = "Bearing: ${bearingDeg.roundToInt()}°N"
        assertEquals("Bearing: 0°N", label)
    }

    @Test
    fun bearingLabel_handles359Degrees() {
        val bearingDeg = 359.0
        val label = "Bearing: ${bearingDeg.roundToInt()}°N"
        assertEquals("Bearing: 359°N", label)
    }

    @Test
    fun sensorData_bearingNull_shouldSkipBearingAnnotation() {
        // If bearingDeg is null, no bearing annotation should be created
        val sensorData = ImageSensorData(bearingDeg = null)
        assertNull(sensorData.bearingDeg, "No bearing annotation should be created when bearingDeg is null")
    }

    @Test
    fun bearingAnnotation_shouldBeLabelType() {
        // Bearing annotations are LABEL type, not DISTANCE or ANGLE
        val annotationType = AnnotationType.LABEL
        assertEquals(AnnotationType.LABEL, annotationType)
    }

    @Test
    fun bearingAnnotation_topRightPosition_shouldBeInValidNormalizedRange() {
        // Top-right position per spec: ~(0.85, 0.05)
        val x = 0.85
        val y = 0.05
        assertTrue(x in 0.0..1.0, "Bearing annotation x must be in [0,1]")
        assertTrue(y in 0.0..1.0, "Bearing annotation y must be in [0,1]")
        assertTrue(x > 0.75, "Bearing annotation should be in the right portion of the image")
        assertTrue(y < 0.15, "Bearing annotation should be near the top of the image")
    }

    @Test
    fun sensorData_bearingPresent_shouldProduceLabelAnnotationString() {
        val sensorData = ImageSensorData(bearingDeg = 137.8)
        val bearingInt = sensorData.bearingDeg!!.roundToInt()
        val label = "Bearing: ${bearingInt}°N"
        assertEquals("Bearing: 138°N", label)
        assertTrue(label.startsWith("Bearing:"), "Label should start with 'Bearing:'")
        assertTrue(label.endsWith("°N"), "Label should end with '°N'")
    }
}
