// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.FitTier
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** U-E3-08: the EXIF focal is only an order-of-magnitude bound on the implied tilt, never a correction. */
class GradientSignificanceTest {
    private val gradient = FitOptions(request = TierRequest.Only(FitTier.GRADIENT))

    private fun scene(tilt: Double) = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(8), 6, tilt, 0.2, spreadFraction = 0.5))

    @Test
    fun gradientResult_should_ReportTiltTooLargeAndKeepScale_When_ImpliedTiltOver10DegViaExifBound() {
        val s = scene(8.0)
        // The photo's true focal is about 26.8 mm-eq; a wrong 70 mm EXIF value makes an 8 degree tilt read as over 10.
        val r = ConstraintCalibrator.fit(s.training.map { it.toConstraint() }, s.frame(exifFocal35mmEq = 70.0), gradient)
        val failure = r.requested.left()
        assertTrue(failure is CalibrationFailure.TiltTooLarge && failure.impliedTiltDeg > 10.0, "got $failure")
        val effective = assertNotNull(r.effective)
        assertEquals(FitTier.SCALE, effective.tier)
        assertNull(effective.homography, "no plane may be written for a TiltTooLarge result")
    }

    @Test
    fun gradientResult_should_BeAccepted_When_ExifFocalAgreesWithTheTilt() {
        val s = scene(8.0)
        val fit = ConstraintCalibrator.fit(s.training.map { it.toConstraint() }, s.frame(exifFocal35mmEq = 26.8), gradient).requested.right()
        val bound = assertNotNull(fit.diagnostics.first { it.tier == FitTier.GRADIENT }.tiltBound)
        assertTrue(bound is TiltBound.Within && bound.impliedTiltDeg in 5.0..10.0, "got $bound")
    }

    @Test
    fun tiltBound_should_BeReportedUnchecked_When_NoExifFocal() {
        val s = scene(8.0)
        val fit = ConstraintCalibrator.fit(s.training.map { it.toConstraint() }, s.frame(null), gradient).requested.right()
        assertEquals(TiltBound.Unchecked, fit.diagnostics.first { it.tier == FitTier.GRADIENT }.tiltBound)
    }

    @Test
    fun noMeasurableTilt_should_CarryTheStatisticAndThreshold() {
        val s = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(2), 6, 0.0, 1.0, spreadFraction = 0.5))
        val failure = ConstraintCalibrator.fit(s.training.map { it.toConstraint() }, s.frame(), gradient).requested.left()
        assertTrue(failure is CalibrationFailure.NoMeasurableTilt && failure.threshold == 5.841, "got $failure")
    }
}
