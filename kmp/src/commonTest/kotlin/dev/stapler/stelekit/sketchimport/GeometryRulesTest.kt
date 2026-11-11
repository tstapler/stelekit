// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import arrow.core.Either
import dev.stapler.stelekit.units.Rational
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryRulesTest {
    private fun box(x0: Double, y0: Double, x1: Double, y1: Double) =
        """<path d="M$x0,$y0 L$x1,$y0 L$x1,$y1 L$x0,$y1 Z"/>"""

    /** Scale 10 px per inch, y flipped, and a 100" `Total Width` line so the sheet calibrates. */
    private fun sheet(vararg shapes: String, anchor: String = "<path d=\"M0,0 L100,0\"/>") =
        """<svg width="1000" height="1000"><g transform="scale(10,-10) translate(50,50)">$anchor${shapes.joinToString("")}</g>
           <text x="1" y="1">Total Width: 100"</text></svg>"""

    private fun measure(svg: String): List<GeometryMeasurement> {
        val doc = readSvg(svg)
        val cal = (calibrateSheet(doc) as Either.Right).value
        return (measureGeometry(doc, cal) as Either.Right).value
    }

    private fun List<GeometryMeasurement>.rule(name: String) = filter { it.rule == name }

    @Test
    fun exactInchesSnapsToSixtyFourthsOrKeepsSixDecimals() {
        assertEquals(Rational(693, 16), exactInches(43.3125 + 1e-9))
        assertEquals(Rational(41, 5), exactInches(8.2))
        assertNull(exactInches(Double.NaN))
        assertNull(exactInches(1e12))
    }

    @Test
    fun widestBoxGivesWidth() {
        val m = measure(sheet(box(0.0, 1.0, 12.5, 2.0), box(0.0, 5.0, 3.0, 9.0)))
        assertEquals(listOf(Rational(25, 2)), m.rule(GeometryRule.BBOX_WIDTH).map { it.value })
    }

    @Test
    fun ticksGiveHeightsAboveTheDatumTallestFirst() {
        val wall = """<path d="M0,0 L0,40"/>"""
        val m = measure(sheet(wall, box(1.0, 39.96, 3.0, 40.04), box(1.0, 29.96, 3.0, 30.04)))
        assertEquals(listOf("0.0" to Rational(40), "0.1" to Rational(30)), m.rule(GeometryRule.BBOX_HEIGHT).map { it.tag to it.value })
    }

    @Test
    fun unevenBandsGiveNoPitch() {
        val bands = listOf(0.0, 8.0, 17.0).map { box(0.0, it, 20.0, it + 1) }
        assertTrue(measure(sheet(*bands.toTypedArray())).rule(GeometryRule.BAND_PITCH).isEmpty())
    }

    @Test
    fun stripsAreMeasuredFromTheOriginSideOfTheView() {
        val strips = listOf(-30.0, -20.0, 0.0).map { box(it - 0.04, 0.0, it + 0.04, 20.0) }
        val got = measure(sheet(*strips.toTypedArray(), anchor = "<path d=\"M-100,0 L0,0\"/>")).rule(GeometryRule.STRIP_POSITIONS)
        assertEquals(listOf(Rational(0), Rational(20), Rational(30)), got.map { it.value })
    }

    @Test
    fun twoStripsAreNotEnoughToCallAStandardsRun() {
        val strips = listOf(-20.0, 0.0).map { box(it - 0.04, 0.0, it + 0.04, 20.0) }
        assertTrue(measure(sheet(*strips.toTypedArray())).rule(GeometryRule.STRIP_POSITIONS).isEmpty())
    }

    @Test
    fun circleDiameterIsTwiceTheRadiusAndCentreIsFromTheExtentCorner() {
        val m = measure(sheet(circle(10.0, 10.0, 1.25, 16)))
        val diameter = m.rule(GeometryRule.CIRCLE_DIAMETER).single()
        assertEquals(Rational(5, 2), diameter.value)
        assertEquals(listOf("0.x", "0.y"), m.rule(GeometryRule.CIRCLE_CENTRE).map { it.tag })
    }

    @Test
    fun openOrLumpyPolylinesAreNotCircles() {
        val open = """<path d="M0,0 L1,0 L2,1 L3,3 L4,6 L5,10 L6,15 L7,21 L8,28"/>"""
        assertTrue(measure(sheet(open)).rule(GeometryRule.CIRCLE_DIAMETER).isEmpty())
    }

    @Test
    fun skewedSheetIsReportedNotMeasured() {
        val svg = sheet().replace("scale(10,-10)", "skewX(5)")
        val doc = readSvg(svg)
        val cal = SheetCalibration(10.0, 10.0, 0.0, ViewDirection.LEFT_TO_RIGHT)
        assertTrue(measureGeometry(doc, cal) is Either.Left)
        assertTrue(measureGeometry(doc, cal.copy(pxPerInchTransform = 0.0)) is Either.Left)
    }

    @Test
    fun evenlySpacedBandsGiveExactPitchForAnyStepAndCount() = runTest {
        checkAll(60, Arb.int(1..160), Arb.int(3..7)) { sixteenths, count ->
            val pitch = Rational(sixteenths.toLong(), 16)
            val bands = (0 until count).map { box(0.0, it * sixteenths / 16.0, 20.0, it * sixteenths / 16.0 + 0.5) }
            val got = measure(sheet(*bands.toTypedArray())).rule(GeometryRule.BAND_PITCH)
            assertEquals(listOf(pitch), got.map { it.value }, "pitch $sixteenths/16 x$count")
        }
    }

    @Test
    fun stripOffsetsRoundTripForAnyIncreasingPositions() = runTest {
        checkAll(60, Arb.list(Arb.int(1..400), 2..6)) { steps ->
            val xs = steps.runningFold(0) { a, b -> a + b }.map { it / 16.0 }
            val strips = xs.map { box(-it - 0.04, 0.0, -it + 0.04, 20.0) }
            val got = measure(sheet(*strips.toTypedArray(), anchor = "<path d=\"M-100,0 L0,0\"/>")).rule(GeometryRule.STRIP_POSITIONS)
            assertEquals(xs.map { Rational((it * 16).toLong(), 16) }, got.map { it.value })
        }
    }

    @Test
    fun measuringNeverThrowsOnMutatedSheets() = runTest {
        val base = sheet(box(0.0, 0.0, 5.0, 5.0), circle(9.0, 9.0, 2.0, 12))
        checkAll(150, Arb.int(0..base.length), Arb.int(0..30), Arb.string(0..5)) { start, len, insert ->
            val mutated = base.substring(0, start) + insert + base.substring(minOf(base.length, start + len))
            val doc = readSvg(mutated)
            calibrateSheet(doc).onRight { measureGeometry(doc, it) }
        }
    }

    private fun circle(cx: Double, cy: Double, r: Double, corners: Int): String {
        val pts = (0 until corners).map { cx + r * cos(2 * PI * it / corners) to cy + r * sin(2 * PI * it / corners) }
        return """<path d="M${pts.first().first},${pts.first().second} ${
            (pts.drop(1) + pts.first()).joinToString(" ") { "L${it.first},${it.second}" }
        }"/>"""
    }
}
