// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.calibration.LensMode
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulerTickSessionTest {
    private val w = 4000.0
    private val h = 3000.0

    /** A straight horizontal ruler: [positionsCm] are real positions, 100 px per cm, starting at x = 400 px. */
    private fun session(positionsCm: List<String>, unit: MeasurementUnit = MeasurementUnit.CENTIMETERS): RulerTickSession =
        RulerTickSession(
            unit = unit,
            entries = positionsCm.mapIndexed { i, text ->
                val cm = text.toDoubleOrNull() ?: i.toDouble()
                RulerTickEntry(NormalizedPoint((400.0 + 100.0 * cm) / w, 0.5), text)
            },
        )

    @Test
    fun nextValueIsSuggestedFromTheLastTwoTicks() {
        var s = RulerTickSession()
        s = s.withTick(NormalizedPoint(0.1, 0.5))
        assertEquals("0", s.entries.last().valueText)
        s = s.withTick(NormalizedPoint(0.2, 0.5)).withValue(1, "10")
        s = s.withTick(NormalizedPoint(0.3, 0.5))
        assertEquals("20", s.entries.last().valueText)
        assertEquals("", RulerTickSession(entries = listOf(RulerTickEntry(NormalizedPoint(0.1, 0.1), "x"), RulerTickEntry(NormalizedPoint(0.2, 0.1), "y"))).suggestedNextValue())
    }

    @Test
    fun numbersFormatWithoutTrailingZeros() {
        assertEquals("5", session(listOf("0", "2.5")).suggestedNextValue())
        assertEquals("12.5", formatTickNumber(12.5))
        assertEquals("3", formatTickNumber(3.0))
        assertEquals("-2.5", formatTickNumber(-2.5))
    }

    @Test
    fun zeroIsAValidPositionAndUnitsConvert() {
        assertEquals(0.0, parseTickPositionToMeters("0", MeasurementUnit.CENTIMETERS))
        assertEquals(0.3, parseTickPositionToMeters("30", MeasurementUnit.CENTIMETERS)!!, 1e-12)
        assertEquals(0.0254, parseTickPositionToMeters("1", MeasurementUnit.INCHES)!!, 1e-12)
        assertNull(parseTickPositionToMeters("", MeasurementUnit.METERS))
        assertNull(parseTickPositionToMeters("abc", MeasurementUnit.METERS))
        assertNull(parseTickPositionToMeters("NaN", MeasurementUnit.METERS))
    }

    @Test
    fun removeAndEditIgnoreOutOfRangeIndexes() {
        val s = session(listOf("0", "10", "20"))
        assertEquals(s, s.withoutTick(7))
        assertEquals(s, s.withValue(-1, "5"))
        assertEquals(listOf("0", "20"), s.withoutTick(1).entries.map { it.valueText })
    }

    @Test
    fun twoTicksReproduceTheSingleLineScale() {
        val p = session(listOf("0", "30")).preview(w, h)
        val cal = assertNotNull(p.calibration)
        assertEquals(CalibrationBasis.NATIVE_PIXELS_LENS, cal.basisVersion)
        assertEquals(10000.0, cal.pixelsPerMeter, 1e-6) // 100 px per cm
        assertTrue(p.canApply)
    }

    @Test
    fun fewerThanTwoValidTicksCannotApply() {
        val p = session(listOf("0", "")).preview(w, h)
        assertFalse(p.canApply)
        assertTrue(p.summary.contains("at least 2"))
        assertFalse(RulerTickSession().preview(w, h).canApply)
    }

    @Test
    fun identicalPositionsCannotDefineAScale() {
        val p = session(listOf("5", "5", "5")).preview(w, h)
        assertFalse(p.canApply)
        assertTrue(p.summary.contains("cannot define a scale"))
    }

    @Test
    fun invalidEntriesAreSkippedAndReported() {
        val p = session(listOf("0", "10", "oops", "30")).preview(w, h)
        assertEquals(3, p.usableTickCount)
        assertTrue(p.canApply)
        assertTrue(p.summary.contains("1 tick(s) without a valid value are ignored"))
    }

    @Test
    fun bentRulerShowsWarningAndResidual() {
        val s = session(listOf("0", "10", "20", "30"))
        // Tick "20" is tapped one centimetre (100 px) too far along the ruler.
        val bent = s.copy(entries = s.entries.mapIndexed { i, e -> if (i == 2) e.copy(point = NormalizedPoint(e.point.x + 100.0 / w, 0.5)) else e }, lensMode = LensMode.Off)
        val p = bent.preview(w, h)
        assertTrue(p.canApply)
        assertTrue(p.summary.contains("Marks disagree"), p.summary)
    }

    @Test
    fun lensModeIsHonoured() {
        val p = session(listOf("0", "10", "20", "30")).copy(lensMode = LensMode.Fixed(-0.01)).preview(w, h)
        assertEquals(-0.01, assertNotNull(p.calibration?.lens).k, 1e-12)
        assertTrue(p.summary.contains("k = -0.010"), p.summary)
    }

    @Test
    fun anyStraightRulerRecoversItsScale() = runTest {
        checkAll(Arb.int(500, 4000), Arb.int(3, 8), Arb.int(0, 30)) { pxPerMeterInt, tickCount, angleTenths ->
            val pxPerM = pxPerMeterInt.toDouble()
            val angle = angleTenths / 10.0
            val dx = kotlin.math.cos(angle * 0.3)
            val dy = kotlin.math.sin(angle * 0.3)
            val entries = (0 until tickCount).map { i ->
                val meters = i * 0.05
                val px = 300.0 + meters * pxPerM
                RulerTickEntry(NormalizedPoint((600.0 + px * dx) / (w * 2), (300.0 + px * dy) / (h * 2)), formatTickNumber(meters * 100.0))
            }
            val cal = RulerTickSession(entries = entries, lensMode = LensMode.Off).preview(w * 2, h * 2).calibration
            assertNotNull(cal)
            assertTrue(abs(cal.pixelsPerMeter - pxPerM) / pxPerM < 1e-3, "got ${cal.pixelsPerMeter} want $pxPerM")
        }
    }

    @Test
    fun everyUsableEntryGetsItsOwnResidualAndSkippedOnesAreNull() {
        val s = session(listOf("0", "10", "oops", "30")).copy(lensMode = LensMode.Off)
        val p = s.preview(w, h)
        assertEquals(4, p.entryResidualsPx.size)
        assertNull(p.entryResidualsPx[2])
        assertTrue(p.entryResidualsPx.filterNotNull().all { it < 1e-6 })
    }

    @Test
    fun theMisTappedTickHasTheLargestResidual() {
        val s = session(listOf("0", "10", "20", "30"))
        val bent = s.copy(entries = s.entries.mapIndexed { i, e -> if (i == 2) e.copy(point = NormalizedPoint(e.point.x + 100.0 / w, 0.5)) else e }, lensMode = LensMode.Off)
        val r = bent.preview(w, h).entryResidualsPx.map { it ?: 0.0 }
        assertEquals(r.max(), r[2].coerceAtLeast(r.max()))
        assertTrue(r.max() > 20.0, r.toString())
    }

    @Test
    fun residualTextShowsTenthsUnderTenPixels() {
        assertEquals("0.4 px", tickResidualText(0.44))
        assertEquals("12 px", tickResidualText(12.3))
    }
}
