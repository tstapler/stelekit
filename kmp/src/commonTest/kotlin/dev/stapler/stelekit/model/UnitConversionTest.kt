package dev.stapler.stelekit.model

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitConversionTest {

    @Test
    fun metersToDisplay_should_returnCorrectValue_when_unitIsMillimeters() {
        val result = metersToDisplay(1.5, MeasurementUnit.MILLIMETERS)
        assertEquals(1500.0, result, 0.001)
    }

    @Test
    fun metersToDisplay_should_returnCorrectValue_when_unitIsFeet() {
        val result = metersToDisplay(1.0, MeasurementUnit.FEET)
        assertEquals(3.28084, result, 0.0001)
    }

    @Test
    fun metersToDisplay_should_handleZero_when_inputIsZero() {
        for (unit in MeasurementUnit.entries) {
            assertEquals(0.0, metersToDisplay(0.0, unit), 0.0001, "Unit $unit should return 0.0 for 0.0 input")
        }
    }

    @Test
    fun metersToDisplay_should_returnSameValue_when_unitIsMeters() {
        val result = metersToDisplay(3.14, MeasurementUnit.METERS)
        assertEquals(3.14, result, 0.0001)
    }

    @Test
    fun metersToDisplay_should_returnCorrectValue_when_unitIsCentimeters() {
        val result = metersToDisplay(2.5, MeasurementUnit.CENTIMETERS)
        assertEquals(250.0, result, 0.001)
    }

    @Test
    fun metersToDisplay_should_returnCorrectValue_when_unitIsInches() {
        val result = metersToDisplay(1.0, MeasurementUnit.INCHES)
        assertEquals(39.3701, result, 0.001)
    }
}
