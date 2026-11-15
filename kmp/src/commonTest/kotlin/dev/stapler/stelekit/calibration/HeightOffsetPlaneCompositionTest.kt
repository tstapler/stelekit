// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.PlaneCalibration
import dev.stapler.stelekit.model.PlaneKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins (Task 3.2a, U-E3-14) how the `(D - h) / D` height-offset factor composes with a plane:
 * the editor measures `LensMeasurement.distanceMeters(...) * HeightOffset.lengthFactor(...)`
 * (AnnotationEditorViewModel.computeMeasurement), so the factor is a uniform multiplier on whichever
 * metric result is active, and D comes from `pixelsPerMeter` (the plane's centre scale), not from the plane.
 */
class HeightOffsetPlaneCompositionTest {
    private val w = 4000.0
    private val h = 3000.0
    private val fPx = 3100.0
    private val focal35 = fPx / 5000.0 * 43.2666
    private val ppm = 2000.0
    private val a = NormalizedPoint(0.25, 0.5)
    private val b = NormalizedPoint(0.75, 0.5)

    /** Native pixels to metres, uniform scale 1/ppm: the CONSTRAINT_FIT plane a SCALE-equivalent fit would write. */
    private fun fitPlane(kind: PlaneKind) = PlaneCalibration(
        homography = listOf(1.0 / ppm, 0.0, 0.0, 0.0, 1.0 / ppm, 0.0, 0.0, 0.0, 1.0),
        referenceName = "Known distances",
        widthMeters = 2.0,
        heightMeters = 1.5,
        kind = kind,
    )

    private fun calibration(plane: PlaneCalibration?, heightOffset: Double, cameraDistance: Double? = null) = Calibration(
        method = CalibrationMethod.MANUAL_REFERENCE,
        pixelsPerMeter = ppm,
        confidencePercent = 90,
        basisVersion = CalibrationBasis.NATIVE_PIXELS,
        geometry = GeometryCalibration(heightOffsetMeters = heightOffset, cameraDistanceMeters = cameraDistance, plane = plane),
    )

    private fun measured(c: Calibration): Double {
        val metric = LensMeasurement.distanceMeters(a, b, c, w, h)!!
        return metric * HeightOffset.lengthFactor(c, focal35, w, h)
    }

    @Test
    fun heightOffset_should_MultiplyThePlaneResultByDMinusHOverD_WithDFromTheCentreScale() {
        val d = fPx / ppm
        val h0 = 0.01
        val expectedFactor = (d - h0) / d
        val plain = LensMeasurement.distanceMeters(a, b, calibration(fitPlane(PlaneKind.CONSTRAINT_FIT), 0.0), w, h)!!
        assertEquals(2000.0 / ppm, plain, 1e-9)
        assertEquals(plain * expectedFactor, measured(calibration(fitPlane(PlaneKind.CONSTRAINT_FIT), h0)), 1e-9)
    }

    @Test
    fun heightOffset_should_GiveTheSameFactorWithAndWithoutAPlane_ForTheSamePixelsPerMeter() {
        val withPlane = HeightOffset.lengthFactor(calibration(fitPlane(PlaneKind.CONSTRAINT_FIT), 0.02), focal35, w, h)
        val withoutPlane = HeightOffset.lengthFactor(calibration(null, 0.02), focal35, w, h)
        assertEquals(withoutPlane, withPlane, 1e-12)
    }

    @Test
    fun heightOffset_should_UseTheManualCameraDistance_OverThePlaneCentreScale() {
        val c = calibration(fitPlane(PlaneKind.CONSTRAINT_FIT), 0.02, cameraDistance = 1.0)
        assertEquals(0.98, HeightOffset.lengthFactor(c, focal35, w, h), 1e-12)
    }

    @Test
    fun planeKind_should_NotChangeWhatTheMeasurementPathComputes() {
        val paper = measured(calibration(fitPlane(PlaneKind.PAPER_REFERENCE), 0.01))
        val fit = measured(calibration(fitPlane(PlaneKind.CONSTRAINT_FIT), 0.01))
        assertEquals(paper, fit, 0.0)
    }

    @Test
    fun heightOffset_should_BeIgnoredWithoutAnUsableDistance_WhenAPlaneIsPresent() {
        val c = calibration(fitPlane(PlaneKind.CONSTRAINT_FIT), 0.01)
        assertEquals(1.0, HeightOffset.lengthFactor(c, null, w, h), 0.0)
    }
}
