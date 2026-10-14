package dev.stapler.stelekit.ui.annotate

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatMetersTest {
    @Test fun atLeastOneMeter_usesThreeDecimalMeters() {
        assertEquals("1.500 m", formatMeters(1.5))
        assertEquals("1.000 m", formatMeters(1.0))
        assertEquals("12.346 m", formatMeters(12.3456))
    }

    @Test fun belowOneMeter_usesMillimeters() {
        assertEquals("50 mm", formatMeters(0.05))
        assertEquals("999 mm", formatMeters(0.999))
        assertEquals("0 mm", formatMeters(0.0))
    }
}
