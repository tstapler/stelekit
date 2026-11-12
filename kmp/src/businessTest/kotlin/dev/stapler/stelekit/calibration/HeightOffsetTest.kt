// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.GeometryCalibration
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeightOffsetTest {
    private val w = 4000.0
    private val h = 3000.0
    private val fPx = 3100.0

    /** 35 mm-equivalent focal length that yields [fPx] on a 4000x3000 frame. */
    private val focal35 = fPx / 5000.0 * 43.2666

    private fun native(ppm: Double) = Calibration(CalibrationMethod.MANUAL_REFERENCE, ppm, 100, CalibrationBasis.NATIVE_PIXELS)

    @Test
    fun factorIsDMinusHOverD() {
        assertEquals(0.99, HeightOffset.factor(1.0, 0.01)!!, 1e-12)
        assertEquals(1.0, HeightOffset.factor(0.6, 0.0)!!, 1e-12)
    }

    @Test
    fun factorRejectsNegativeLargeAndNonFiniteInput() {
        assertNull(HeightOffset.factor(0.6, -0.001))
        assertNull(HeightOffset.factor(0.6, 0.3))
        assertNull(HeightOffset.factor(0.6, 0.5))
        assertNull(HeightOffset.factor(0.0, 0.01))
        assertNull(HeightOffset.factor(Double.NaN, 0.01))
        assertNull(HeightOffset.factor(0.6, Double.POSITIVE_INFINITY))
    }

    @Test
    fun focalPixelsFromThirtyFiveMillimetreEquivalent() {
        assertEquals(fPx, HeightOffset.focalPixels(focal35, w, h)!!, 1e-6)
        assertNull(HeightOffset.focalPixels(null, w, h))
        assertNull(HeightOffset.focalPixels(0.0, w, h))
    }

    @Test
    fun cameraDistanceDerivesFromFocalLengthAndScale() {
        val ppm = fPx / 0.6
        val d = assertNotNull(HeightOffset.cameraDistance(null, native(ppm), focal35, w, h))
        assertEquals(0.6, d.meters, 1e-9)
        assertEquals(DistanceSource.FOCAL_LENGTH, d.source)
    }

    @Test
    fun manualDistanceWinsAndAFocalLengthIsOtherwiseRequired() {
        val manual = GeometryCalibration(0.01, 0.75)
        assertEquals(DistanceSource.MANUAL, HeightOffset.cameraDistance(manual, native(5000.0), focal35, w, h)?.source)
        val legacy = Calibration(CalibrationMethod.MANUAL_REFERENCE, 187.5, 100)
        assertNull(HeightOffset.cameraDistance(GeometryCalibration(0.01), legacy, null, w, h))
        assertNull(HeightOffset.cameraDistance(GeometryCalibration(0.01), Calibration(), focal35, w, h))
        assertEquals(0.75, HeightOffset.cameraDistance(manual, legacy, null, w, h)?.meters)
        assertNull(HeightOffset.cameraDistance(GeometryCalibration(0.01), native(5000.0), null, w, h))
    }

    @Test
    fun lengthFactorIsOneWithoutAnUsableOffset() {
        val ppm = fPx / 0.6
        assertEquals(1.0, HeightOffset.lengthFactor(native(ppm), focal35, w, h))
        assertEquals(1.0, HeightOffset.lengthFactor(native(ppm).copy(geometry = GeometryCalibration(0.0)), focal35, w, h))
        assertEquals(1.0, HeightOffset.lengthFactor(native(ppm).copy(geometry = GeometryCalibration(0.01)), null, w, h))
        assertEquals(1.0, HeightOffset.lengthFactor(native(ppm).copy(geometry = GeometryCalibration(0.4)), focal35, w, h))
        assertEquals(1.0, HeightOffset.lengthFactor(null, focal35, w, h))
    }

    /** Golden scene T-G4 maths: 18 cm edge 1 cm above the ruler plane at D = 60 cm reads +0.30 cm uncorrected. */
    @Test
    fun goldenScene_objectOneCentimetreAboveThePlane_isCorrectedWithinThreeTenthsOfAMillimetre() {
        val d = 0.6
        val ppm = fPx / d
        val trueLength = 0.18
        val apparent = trueLength * d / (d - 0.01)
        assertEquals(0.30, (apparent - trueLength) * 100, 0.01)
        val corrected = apparent * HeightOffset.lengthFactor(native(ppm).copy(geometry = GeometryCalibration(0.01)), focal35, w, h)
        assertEquals(trueLength, corrected, 0.0003)
    }

    @Test
    fun anyHeightAndDistanceRoundTripsToTheTrueLength() = runTest {
        checkAll(Arb.int(30, 300), Arb.int(0, 99), Arb.int(5, 60)) { distanceCm, heightMmTimes10, lengthCm ->
            val d = distanceCm / 100.0
            val height = heightMmTimes10 / 10000.0
            if (height < d * HeightOffset.MAX_HEIGHT_FRACTION) {
                val trueLength = lengthCm / 100.0
                val apparent = trueLength * d / (d - height)
                assertEquals(trueLength, apparent * HeightOffset.factor(d, height)!!, 1e-12)
            }
        }
    }

    @Test
    fun correctionNeverIncreasesAndShrinksWithHeight() = runTest {
        checkAll(Arb.int(0, 90), Arb.int(0, 90)) { a, b ->
            val d = 0.6
            val fa = HeightOffset.factor(d, a / 1000.0)!!
            val fb = HeightOffset.factor(d, b / 1000.0)!!
            assertTrue(fa <= 1.0 && fb <= 1.0)
            assertEquals(a <= b, fa >= fb)
        }
    }
}
