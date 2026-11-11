// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic-photo proofs for lens-aware calibration. Ground truth lives on a flat plane at a known
 * scale; a polynomial FORWARD barrel model (deliberately not the division model the code inverts)
 * distorts it, so a pass is not the model grading itself.
 */
class LensDistortionSimulationTest {
    private val w = 4000.0
    private val h = 3000.0
    private val halfDiag = hypot(w, h) / 2.0
    private val worldPxPerMeter = 4000.0 / 0.6

    /** Forward barrel: distorted = c + u * (1 + a * r_u^2) with a < 0 pulling edges inward. */
    private fun distort(x: Double, y: Double, a: Double): NormalizedPoint {
        val dx = x - w / 2
        val dy = y - h / 2
        val f = 1 + a * (hypot(dx, dy) / halfDiag).let { it * it }
        val nx = (w / 2 + dx * f) / w
        val ny = (h / 2 + dy * f) / h
        // Clamping into the frame would fabricate bent rulers; a physical photo cannot contain such a point.
        require(nx in 0.0..1.0 && ny in 0.0..1.0) { "synthetic point ($x,$y) falls outside the frame" }
        return NormalizedPoint(nx, ny)
    }

    /** Photo of a straight segment of [lengthM] centred at world-pixel ([cx],[cy]) at [angle]. */
    private fun segment(cx: Double, cy: Double, angle: Double, lengthM: Double, a: Double, t0: Double = -0.5, t1: Double = 0.5) =
        listOf(t0, t1).map {
            val d = it * lengthM * worldPxPerMeter
            distort(cx + cos(angle) * d, cy + sin(angle) * d, a)
        }

    private fun ticks(cx: Double, cy: Double, angle: Double, rulerM: Double, count: Int, a: Double): List<CalibrationTick> =
        (0 until count).map { i ->
            val frac = i.toDouble() / (count - 1)
            val p = segment(cx, cy, angle, rulerM, a, frac - 0.5, frac - 0.5)[0]
            CalibrationTick(p.x, p.y, frac * rulerM)
        }

    private fun knifeCm(cal: Calibration, cx: Double, cy: Double, angle: Double, a: Double): Double {
        val (p, q) = segment(cx, cy, angle, 0.18, a)
        return LensMeasurement.distanceMeters(p, q, cal, w, h)!! * 100
    }

    private fun basic(rulerCx: Double, rulerCy: Double, a: Double): Calibration {
        val (p, q) = segment(rulerCx, rulerCy, 0.0, 0.30, a)
        return CalibrationService.computeFromReference(p, q, w, h, 0.30)!!
    }

    @Test
    fun basicSingleLine_should_ReadHigh_When_RulerIsNearerTheEdgeThanTheObject() {
        val cal = basic(2000.0, 2700.0, a = -0.01)
        val centre = knifeCm(cal, 2000.0, 1500.0, 0.0, -0.01)
        val edge = knifeCm(cal, 3400.0, 800.0, 0.0, -0.01)
        assertTrue(centre in 18.03..18.12, "centre knife reads high: $centre")
        assertTrue(edge < 17.95, "edge knife reads low: $edge")
    }

    @Test
    fun multiTickAutoK_should_CutTheError_When_RulerSpansTheFrame() {
        val a = -0.015
        val t = ticks(2000.0, 1500.0, 0.3, 0.55, count = 6, a = a)
        val cal = assertNotNull(MultiPointCalibration.calibrate(t, w, h, LensMode.Auto))
        assertEquals(CalibrationBasis.NATIVE_PIXELS_LENS, cal.basisVersion)
        assertTrue(cal.lens!!.kEstimated && cal.lens!!.k < 0.0, "k=${cal.lens!!.k}")
        val basicCal = basic(2000.0, 2700.0, a)
        val spots = listOf(Triple(2000.0, 1500.0, 0.0), Triple(3400.0, 800.0, 0.0), Triple(600.0, 2400.0, 1.0))
        for ((x, y, ang) in spots) {
            val fixed = abs(knifeCm(cal, x, y, ang, a) - 18.0)
            val naive = abs(knifeCm(basicCal, x, y, ang, a) - 18.0)
            assertTrue(fixed <= 0.06, "corrected error $fixed cm at ($x,$y)")
            assertTrue(fixed <= naive + 1e-9 || naive < 0.06, "corrected $fixed vs basic $naive at ($x,$y)")
        }
    }

    @Test
    fun property_correctedErrorStaysSmall_AcrossRandomKAndPositions() {
        val rnd = Random(20261111)
        repeat(150) {
            val a = -(0.004 + rnd.nextDouble() * 0.021)
            // |angle| <= 0.8 rad keeps the 55 cm ruler (3667 px) inside the 3000 px tall frame.
            val angle = (rnd.nextDouble() - 0.5) * 1.6
            val t = ticks(1900.0 + rnd.nextDouble() * 200, 1450.0 + rnd.nextDouble() * 100, angle, 0.55, 6, a)
            val cal = assertNotNull(MultiPointCalibration.calibrate(t, w, h, LensMode.Auto))
            val x = 900.0 + rnd.nextDouble() * 2200
            val y = 700.0 + rnd.nextDouble() * 1600
            val err = abs(knifeCm(cal, x, y, rnd.nextDouble() * 3.14159, a) - 18.0)
            assertTrue(err <= 0.08, "a=$a err=$err cm at ($x,$y)")
        }
    }

    @Test
    fun auto_should_FallBackToNoCorrection_When_TicksAreNotAStraightRuler() {
        // Ruler through the image centre with one tick 50 px off the line: radial correction cannot straighten it.
        val bent = (0..5).map { i ->
            CalibrationTick(0.2 + 0.12 * i, 0.5 + (if (i == 2) 50.0 / h else 0.0), 0.1 * i)
        }
        val cal = assertNotNull(MultiPointCalibration.calibrate(bent, w, h, LensMode.Auto))
        assertEquals(0.0, cal.lens!!.k, "inconsistent ticks must not produce a lens correction")
    }

    @Test
    fun auto_should_NotInventDistortion_When_TicksAreOnlyNoisy() {
        val rnd = Random(7)
        repeat(50) {
            val t = ticks(2000.0, 2600.0, 0.0, 0.30, 4, a = 0.0).map {
                it.copy(x = it.x + (rnd.nextDouble() - 0.5) * 1.2 / w, y = it.y + (rnd.nextDouble() - 0.5) * 1.2 / h)
            }
            val cal = assertNotNull(MultiPointCalibration.calibrate(t, w, h, LensMode.Auto))
            assertEquals(0.0, cal.lens!!.k, "noise alone must not trigger correction")
        }
    }

    @Test
    fun residualPercent_should_FlagTilt_AndBeNearZeroForAFlatSquareOnRuler() {
        fun tiltedTicks(tiltDeg: Double): List<CalibrationTick> {
            val tl = tan(tiltDeg * 3.14159265 / 180)
            val f = 3000.0
            return (0..6).map { i ->
                val xm = (i - 3) * 0.05
                val z = 0.6 + xm * tl
                CalibrationTick(0.5 + f * xm / z / w, 0.5 + f * 0.1 / z / h, i * 0.05)
            }
        }
        val flat = MultiPointCalibration.fitScale(tiltedTicks(0.0), 0.0, w, h)!!
        val tilted = MultiPointCalibration.fitScale(tiltedTicks(20.0), 0.0, w, h)!!
        assertTrue(flat.residualPercent < 0.05, "flat ${flat.residualPercent}")
        assertTrue(tilted.residualPercent > 1.0, "tilted ${tilted.residualPercent}")
    }

    @Test
    fun endpointError_should_ShrinkWithReferenceLength() {
        fun relErr(lenPx: Double): Double {
            val ppm = (lenPx + 2) / 0.30
            return abs(ppm - lenPx / 0.30) / (lenPx / 0.30)
        }
        assertTrue(relErr(60.0) > 0.03)
        assertTrue(relErr(2400.0) < 0.001)
        assertEquals(2.0 / 300.0, relErr(300.0), 1e-9)
    }

    @Test
    fun legacyAndBasicCalibrations_should_MeasureExactlyAsBefore() {
        val a = NormalizedPoint(0.1, 0.5)
        val b = NormalizedPoint(0.6, 0.5)
        val legacy = assertNotNull(CalibrationService.computeFromReference(a, b, 1000.0, 1000.0, 0.5))
        assertEquals(CalibrationBasis.LEGACY_DECODE_RELATIVE, legacy.basisVersion)
        assertNull(legacy.lens)
        assertEquals(1000.0, legacy.pixelsPerMeter, 1e-9)
        assertEquals(1000.0, CalibrationService.pixelsPerMeterAt(legacy, 4000.0, 800.0), 0.0)
        assertEquals(0.5, LensMeasurement.distanceMeters(a, b, legacy, 1000.0, 1000.0)!!, 1e-12)
        assertEquals(200.0, CalibrationService.pixelsPerMeterAt(legacy.copy(basisVersion = 2), 4000.0, 800.0), 0.0)
        assertFalse(LensMeasurement.assess(listOf(a, b), legacy, 1000.0, 1000.0).hasWarning)
    }

    @Test
    fun twoTickOff_should_MatchSingleLineCalibration() {
        val p = NormalizedPoint(0.2, 0.7)
        val q = NormalizedPoint(0.7, 0.55)
        val single = assertNotNull(CalibrationService.computeFromReference(p, q, w, h, 0.3))
        val multi = assertNotNull(
            MultiPointCalibration.calibrate(
                listOf(CalibrationTick(p.x, p.y, 0.0), CalibrationTick(q.x, q.y, 0.3)), w, h, LensMode.Off,
            ),
        )
        assertEquals(single.pixelsPerMeter, multi.pixelsPerMeter, 1e-6)
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, multi.method)
    }

    @Test
    fun degenerateTicks_should_ReturnNull() {
        val same = listOf(CalibrationTick(0.5, 0.5, 0.0), CalibrationTick(0.5, 0.5, 0.1))
        assertNull(MultiPointCalibration.calibrate(same, w, h, LensMode.Off))
        assertNull(MultiPointCalibration.calibrate(same.take(1), w, h, LensMode.Off))
        val sameLen = listOf(CalibrationTick(0.1, 0.5, 0.1), CalibrationTick(0.9, 0.5, 0.1))
        assertNull(MultiPointCalibration.calibrate(sameLen, w, h, LensMode.Off))
        assertNull(MultiPointCalibration.calibrate(
            listOf(CalibrationTick(0.1, 0.5, 0.0), CalibrationTick(0.9, 0.5, 0.0)), w, h, LensMode.Off,
        ))
    }

    @Test
    fun assess_should_WarnWhenObjectAndRulerAreAtDifferentRadii_AndWhenMarksDisagree() {
        val edgeRuler = ticks(2000.0, 2700.0, 0.0, 0.30, 3, a = -0.01)
        val cal = assertNotNull(MultiPointCalibration.calibrate(edgeRuler, w, h, LensMode.Off))
        val centreObject = listOf(NormalizedPoint(0.45, 0.5), NormalizedPoint(0.55, 0.5))
        val far = LensMeasurement.assess(centreObject, cal, w, h)
        assertTrue(far.hasWarning && far.warnings.any { it.contains("different distances from the image centre") })
        val near = LensMeasurement.assess(listOf(NormalizedPoint(0.4, 0.88), NormalizedPoint(0.6, 0.88)), cal, w, h)
        assertFalse(near.warnings.any { it.contains("different distances") })
        val bent = cal.copy(lens = cal.lens!!.copy(residualPercent = 2.34))
        assertTrue(LensMeasurement.assess(centreObject, bent, w, h).warnings.any { it.startsWith("Ruler marks disagree by 2.3%") })
    }

    @Test
    fun areaAndFixedPreset_should_UseUndistortedPoints() {
        val t = ticks(2000.0, 1500.0, 0.0, 0.5, 3, a = 0.0)
        val cal = assertNotNull(MultiPointCalibration.calibrate(t, w, h, LensMode.Fixed(-0.01)))
        assertEquals(-0.01, cal.lens!!.k, 0.0)
        val square = listOf(NormalizedPoint(0.4, 0.4), NormalizedPoint(0.6, 0.4), NormalizedPoint(0.6, 0.6), NormalizedPoint(0.4, 0.6))
        val area = assertNotNull(LensMeasurement.areaSquareMeters(square, cal, w, h))
        assertTrue(area > 0.0)
        assertNull(LensMeasurement.areaSquareMeters(square.take(2), cal, w, h))
        assertNull(LensMeasurement.distanceMeters(square[0], square[1], cal.copy(pixelsPerMeter = 0.0), w, h))
    }
}
