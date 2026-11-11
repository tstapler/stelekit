// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import dev.stapler.stelekit.units.Rational
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RasterTaggingTest {
    private val size = PngSize(2000, 1000)
    private val a = NormalizedPoint(0.1, 0.5)
    private val b = NormalizedPoint(0.6, 0.5)
    private val registry = DimensionRegistry(
        dimensions = listOf(
            DimensionEntry("closet.width", DimensionKind.LENGTH, "Closet width"),
            DimensionEntry("rod.count", DimensionKind.COUNT, "Rods"),
        ),
    )

    @Test
    fun parseTypedLength_should_ReadFractionalInchesAndFeetInches() {
        assertEquals(Rational(43, 8), RasterTagging.parseTypedLength("5 3/8\"").getOrNull())
        assertEquals(Rational(150), RasterTagging.parseTypedLength("12'-6\"").getOrNull())
    }

    @Test
    fun parseTypedLength_should_RefuseEmptyZeroNegativeAndNonSingleLengths() {
        listOf("", "abc", "0\"", "-5\"", "10-12\"", "24 x 36\"").forEach {
            assertEquals(RasterInputError.NotALength, RasterTagging.parseTypedLength(it).leftOrNull(), it)
        }
    }

    @Test
    fun format_should_ShowFeetAndInchesAndRoundANonSixteenth() {
        assertEquals("12' 6\"", RasterTagging.format(Rational(150)))
        assertEquals("5 3/8\"", RasterTagging.format(Rational(43, 8)))
        assertEquals("1/16\"", RasterTagging.format(Rational(1, 17)))
    }

    @Test
    fun calibrate_should_UseNativePixelBasis_AndRefuseClosePoints() {
        val c = assertNotNull(RasterTagging.calibrate(size, a, b, "100\"").getOrNull())
        assertEquals(CalibrationBasis.NATIVE_PIXELS, c.basisVersion)
        assertEquals(RasterInputError.PointsTooClose, RasterTagging.calibrate(size, a, NormalizedPoint(0.1005, 0.5), "100\"").leftOrNull())
        assertEquals(RasterInputError.PointsTooClose, RasterTagging.calibrate(size, a, a, "100\"").leftOrNull())
        assertEquals(RasterInputError.NotALength, RasterTagging.calibrate(size, a, b, "x").leftOrNull())
    }

    @Test
    fun measure_should_BeNullForClosePoints() {
        val c = assertNotNull(RasterTagging.calibrate(size, a, b, "100\"").getOrNull())
        assertNull(RasterTagging.measure(c, size, a, a))
    }

    @Test
    fun calibrateThenMeasureReference_should_GiveBackTheTypedLength() = runTest {
        checkAll(300, Arb.int(100..4000), Arb.int(100..4000), Arb.int(0..9000), Arb.int(0..9000), Arb.int(1..2000)) { w, h, x, y, sixteenths ->
            val s = PngSize(w, h)
            val start = NormalizedPoint(x / 10000.0, y / 10000.0)
            val end = NormalizedPoint((x + 1000) / 10000.0, (y + 1000) / 10000.0)
            val typed = Rational(sixteenths.toLong(), 16)
            val c = assertNotNull(RasterTagging.calibrate(s, start, end, LengthFormatter.format(Inches(sixteenths.toLong()), LengthFormat.INCHES_MIXED)).getOrNull())
            assertEquals(typed, RasterTagging.measure(c, s, start, end))
        }
    }

    @Test
    fun measureOtherLine_should_ScaleWithPixelDistanceWithinOneSixteenth() = runTest {
        checkAll(300, Arb.int(1..9), Arb.int(20..400)) { factor, inches ->
            val c = assertNotNull(RasterTagging.calibrate(size, a, NormalizedPoint(0.2, 0.5), "$inches\"").getOrNull())
            val far = NormalizedPoint(0.1 + 0.1 * factor, 0.5)
            val measured = assertNotNull(RasterTagging.measure(c, size, a, far))
            assertTrue(abs(measured.num.toDouble() / measured.den - inches * factor) <= 1.0 / 16)
        }
    }

    @Test
    fun tag_should_RequireALengthKeyAndAScale() {
        val c = assertNotNull(RasterTagging.calibrate(size, a, b, "100\"").getOrNull())
        val line = RasterLine(a, NormalizedPoint(0.35, 0.5))
        assertEquals(RasterInputError.NotALengthKey("rod.count"), RasterTagging.tag(registry, c, size, "rod.count", line).leftOrNull())
        assertEquals(RasterInputError.NotALengthKey("nope"), RasterTagging.tag(registry, c, size, "nope", line).leftOrNull())
        assertEquals(RasterInputError.NotCalibrated, RasterTagging.tag(registry, null, size, "closet.width", line).leftOrNull())
        assertEquals(RasterInputError.PointsTooClose, RasterTagging.tag(registry, c, size, "closet.width", RasterLine(a, a)).leftOrNull())
        val tag = assertNotNull(RasterTagging.tag(registry, c, size, "closet.width", line).getOrNull())
        assertEquals(Rational(50), tag.length)
        assertTrue(tag.measured)
    }

    @Test
    fun upsert_should_KeepOneTagPerKey() {
        val first = RasterTag("closet.width", a, b, Rational(10), measured = true)
        val second = first.copy(length = Rational(20))
        assertEquals(listOf(second), RasterTagging.upsert(RasterTagging.upsert(emptyList(), first), second))
    }
}
