// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import arrow.core.Either
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SheetCalibrationTest {
    private fun fixture(name: String): String =
        checkNotNull(SheetCalibrationTest::class.java.classLoader.getResourceAsStream("home-sketch/closet/$name")) {
            "fixture $name missing"
        }.bufferedReader().use { it.readText() }

    private fun calibrate(name: String): SheetCalibration =
        (calibrateSheet(readSvg(fixture(name))) as Either.Right).value

    @Test
    fun elevationScaleRecoveredFromTransform() {
        val c = calibrate("closet-elevation.svg")
        assertTrue(abs(c.pxPerInchTransform - 11.06382978723404) < 1e-4, "${c.pxPerInchTransform}")
    }

    @Test
    fun sectionScaleRecoveredFromTransform() {
        val c = calibrate("closet-section.svg")
        assertTrue(abs(c.pxPerInchTransform - 34.22885572139303) < 1e-4, "${c.pxPerInchTransform}")
    }

    @Test
    fun labelCalibrationAgreesWithTransformWithinTenthOfAPercent() {
        for (name in listOf("closet-elevation.svg", "closet-section.svg")) {
            val c = calibrate(name)
            assertTrue(c.labelAgreement < 0.001, "$name agreement ${c.labelAgreement}")
        }
    }

    @Test
    fun elevationIsDrawnRightToLeftAndSectionLeftToRight() {
        assertEquals(ViewDirection.RIGHT_TO_LEFT, calibrate("closet-elevation.svg").viewDirection)
        assertEquals(ViewDirection.LEFT_TO_RIGHT, calibrate("closet-section.svg").viewDirection)
    }

    @Test
    fun rejectsUnitAssumptionWithoutKnownDimension() {
        val noLabels = fixture("closet-elevation.svg").replace(Regex("<text[^>]*>[^<]*</text>"), "")
        val result = calibrateSheet(readSvg(noLabels))
        assertIs<CalibrationError.NoKnownDimension>((result as Either.Left).value)
    }

    @Test
    fun skewedSheetIsRejectedWithTransformError() {
        val skewed = fixture("closet-elevation.svg").replace("scale(11.06382978723404, -11.06382978723404)", "skewX(5)")
        assertIs<CalibrationError.Transform>((calibrateSheet(readSvg(skewed)) as Either.Left).value)
    }
}
