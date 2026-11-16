// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.MeasurementValue
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.metersToDisplayString
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MeasurementValueEditTest {
    private fun meters(text: String, unit: MeasurementUnit) =
        (MeasurementValueEdit.parse(text, unit) as Either.Right).value

    private fun error(text: String, unit: MeasurementUnit = MeasurementUnit.METERS) =
        (MeasurementValueEdit.parse(text, unit) as Either.Left).value

    @Test
    fun parse_should_ReturnMeters_For_5ft3in_AndRejectEmptyZeroNegativeNonNumeric() {
        assertEquals(1.6002, meters("5'3\"", MeasurementUnit.FEET_INCHES), 1e-9)
        assertEquals(1.6002, meters("5' 3\"", MeasurementUnit.CENTIMETERS), 1e-9)
        assertEquals(MeasurementValueEdit.Error.EMPTY, error(""))
        assertEquals(MeasurementValueEdit.Error.EMPTY, error("   "))
        assertEquals(MeasurementValueEdit.Error.NOT_POSITIVE, error("0"))
        assertEquals(MeasurementValueEdit.Error.NOT_POSITIVE, error("-2"))
        assertEquals(MeasurementValueEdit.Error.NOT_A_NUMBER, error("abc"))
        assertEquals(MeasurementValueEdit.Error.NOT_A_NUMBER, error("NaN"))
        assertEquals(MeasurementValueEdit.Error.NOT_A_NUMBER, error("Infinity"))
    }

    @Test
    fun parse_should_UseTheTypedUnit_OverTheEntryUnit() {
        assertEquals(0.9, meters("0.90", MeasurementUnit.METERS), 1e-12)
        assertEquals(0.9, meters("90", MeasurementUnit.CENTIMETERS), 1e-12)
        assertEquals(0.9, meters("900 mm", MeasurementUnit.FEET), 1e-12)
        assertEquals(0.9, meters("90cm", MeasurementUnit.INCHES), 1e-12)
        assertEquals(0.762, meters("2.5", MeasurementUnit.FEET), 1e-9)
        assertEquals(0.762, meters("2.5 ft", MeasurementUnit.CENTIMETERS), 1e-9)
        assertEquals(0.0254 * 10.5, meters("10 1/2\"", MeasurementUnit.METERS), 1e-9)
        assertEquals(0.0254 * 12.0, meters("12", MeasurementUnit.INCHES), 1e-9)
    }

    @Test
    fun parse_should_RejectImplausibleMagnitudes() {
        assertEquals(MeasurementValueEdit.Error.IMPLAUSIBLE, error("500000"))
        assertEquals(MeasurementValueEdit.Error.IMPLAUSIBLE, error("0.00001"))
    }

    @Test
    fun apply_should_StoreOverriddenWithTheOriginal_AndKeepGeometry() {
        val pts = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.1))
        val base = MeasurementAnnotation("m1aaaaaa", "i1aaaaaa", AnnotationType.DISTANCE, pts, valueMeters = 0.8741, valueDisplay = "87.4 cm")
        val edited = MeasurementValueEdit.apply(base, 0.90, MeasurementUnit.CENTIMETERS)
        assertEquals(MeasurementValue.Overridden(0.90, 0.8741), edited.value)
        assertEquals(pts, edited.normalizedPoints)
        assertEquals(metersToDisplayString(0.90, MeasurementUnit.CENTIMETERS), edited.valueDisplay)

        // Editing an override again keeps the very first computed value as the original.
        val again = MeasurementValueEdit.apply(edited, 0.95, MeasurementUnit.CENTIMETERS)
        assertEquals(MeasurementValue.Overridden(0.95, 0.8741), again.value)
    }

    @Test
    fun apply_should_UseTheNewValueAsOriginal_When_NothingWasComputed() {
        val pts = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.1))
        val base = MeasurementAnnotation("m1aaaaaa", "i1aaaaaa", AnnotationType.DISTANCE, pts)
        assertEquals(MeasurementValue.Overridden(0.9, 0.9), MeasurementValueEdit.apply(base, 0.9, MeasurementUnit.METERS).value)
    }

    @Test
    fun parseOfFormat_should_RoundTripWithinTheDisplayResolution_ForEveryUnit() = runTest {
        checkAll(500, Arb.double(0.01, 500.0, includeNaNs = false), Arb.enum<MeasurementUnit>()) { m, unit ->
            val text = metersToDisplayString(m, unit)
            val parsed = assertIs<Either.Right<Double>>(MeasurementValueEdit.parse(text, unit)).value
            val tolerance = when (unit) {
                MeasurementUnit.FEET_INCHES, MeasurementUnit.INCHES -> 0.0254 / 32.0 + 1e-9
                MeasurementUnit.METERS, MeasurementUnit.FEET -> 0.0005 * (if (unit == MeasurementUnit.FEET) 0.3048 else 1.0) + 1e-9
                MeasurementUnit.CENTIMETERS -> 0.000005 + 1e-9
                MeasurementUnit.MILLIMETERS -> 0.0000005 + 1e-9
            }
            assertTrue(abs(parsed - m) <= tolerance, "$m -> '$text' -> $parsed (tol $tolerance, $unit)")
        }
    }
}
