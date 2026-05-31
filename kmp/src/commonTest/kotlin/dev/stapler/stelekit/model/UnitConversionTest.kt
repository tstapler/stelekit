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

    // ── Imperial fractional display ───────────────────────────────────────────

    @Test
    fun formatFeetInches_should_format_whole_feet_and_zero_inches() {
        // 1.8288 m = exactly 6 feet
        assertEquals("6' 0\"", formatFeetInches(1.8288))
    }

    @Test
    fun formatFeetInches_should_format_feet_and_quarter_inch() {
        // 64.25 inches / 39.37 = 1.63195 m → 5' 4 1/4"
        assertEquals("5' 4 1/4\"", formatFeetInches(1.63195))
    }

    @Test
    fun formatFeetInches_should_format_half_inch() {
        // 12.5 inches / 39.37 = 0.3175 m → 1' 1/2" (zero whole inches omitted per convention)
        assertEquals("1' 1/2\"", formatFeetInches(0.3175))
    }

    @Test
    fun formatFeetInches_should_reduce_fraction_to_lowest_terms() {
        // 4/16 → 1/4, 8/16 → 1/2, 12/16 → 3/4
        val oneQuarterInch = 0.00635 // 1/4 inch in meters
        val result = formatFractionalInches(4.25) // 4 and 4/16 = 4 1/4
        assertEquals("4 1/4\"", result)
    }

    @Test
    fun formatFractionalInches_should_omit_fraction_for_whole_inches() {
        assertEquals("3\"", formatFractionalInches(3.0))
    }

    @Test
    fun formatFractionalInches_should_format_sixteenth() {
        // 0.0625 in = 1/16"
        assertEquals("1/16\"", formatFractionalInches(0.0625))
    }

    @Test
    fun formatFractionalInches_should_format_fifteen_sixteenths() {
        // 0.9375 in = 15/16" — not reducible, hardest fraction case
        assertEquals("15/16\"", formatFractionalInches(0.9375))
    }

    @Test
    fun metersToDisplayString_feetInches_produces_construction_format() {
        // 64.25 inches = 5' 4 1/4"
        assertEquals("5' 4 1/4\"", metersToDisplayString(1.63195, MeasurementUnit.FEET_INCHES))
    }

    @Test
    fun metersToDisplayString_inches_produces_fractional_format() {
        // 0.3175 m = 12.5 inches → "12 1/2\""
        assertEquals("12 1/2\"", metersToDisplayString(0.3175, MeasurementUnit.INCHES))
    }
}
