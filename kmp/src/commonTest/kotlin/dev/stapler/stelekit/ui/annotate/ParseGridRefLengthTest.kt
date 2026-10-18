package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.model.MeasurementUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ParseGridRefLengthTest {

    private fun parse(text: String, unit: MeasurementUnit) = parseGridRefLengthToMeters(text, unit)

    private fun assertClose(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertEquals(expected, actual, 1e-9)
    }

    @Test fun meters_isIdentity() = assertClose(2.5, parse("2.5", MeasurementUnit.METERS))
    @Test fun centimeters_dividesBy100() = assertClose(1.5, parse("150", MeasurementUnit.CENTIMETERS))
    @Test fun millimeters_dividesBy1000() = assertClose(0.25, parse("250", MeasurementUnit.MILLIMETERS))
    @Test fun feet_multipliesBy0_3048() = assertClose(3.048, parse("10", MeasurementUnit.FEET))
    @Test fun inches_multipliesBy0_0254() = assertClose(0.254, parse("10", MeasurementUnit.INCHES))
    @Test fun feetInches_treatedAsFeet() = assertClose(3.048, parse("10", MeasurementUnit.FEET_INCHES))

    @Test fun trimsWhitespace() = assertClose(4.0, parse("  4  ", MeasurementUnit.METERS))

    @Test fun blank_isNull() {
        MeasurementUnit.entries.forEach { assertNull(parse("", it)); assertNull(parse("   ", it)) }
    }

    @Test fun negative_isNull() {
        MeasurementUnit.entries.forEach { assertNull(parse("-1", it)) }
    }

    @Test fun zero_isNull() {
        MeasurementUnit.entries.forEach { assertNull(parse("0", it)); assertNull(parse("0.0", it)) }
    }

    @Test fun nonNumeric_isNull() {
        MeasurementUnit.entries.forEach { assertNull(parse("abc", it)); assertNull(parse("1,5", it)) }
    }
}
