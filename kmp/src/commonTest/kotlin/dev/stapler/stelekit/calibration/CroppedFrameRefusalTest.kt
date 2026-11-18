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

/** U-E3-09: GRADIENT is refused on a cropped or digitally zoomed frame and SCALE is kept (ADR-003 crop rule). */
class CroppedFrameRefusalTest {
    private val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(14), 6, 7.0, 0.2, spreadFraction = 0.5))
    private val cs = scene.training.map { it.toConstraint() }
    private val gradient = FitOptions(request = TierRequest.Only(FitTier.GRADIENT))

    private fun refused(frame: CaptureFrameInfo, options: FitOptions = gradient): CropReason {
        val r = ConstraintCalibrator.fit(cs, frame, options)
        val failure = r.requested.left()
        assertTrue(failure is CalibrationFailure.CroppedOrZoomedFrame, "got $failure")
        val effective = assertNotNull(r.effective)
        assertEquals(FitTier.SCALE, effective.tier)
        assertNull(effective.homography, "a cropped-frame result must not carry a plane")
        return failure.reason
    }

    @Test
    fun fit_should_KeepScale_When_SidecarLensRecordShowsZoom() {
        assertEquals(CropReason.ZOOM_RATIO, refused(scene.frame().copy(zoomRatio = 2.0)))
    }

    @Test
    fun fit_should_KeepScale_When_ExifDigitalZoomTagIsSet() {
        assertEquals(CropReason.EXIF_DIGITAL_ZOOM, refused(scene.frame().copy(exifDigitalZoomRatio = 1.5)))
    }

    @Test
    fun fit_should_KeepScale_When_AspectIsNeither43Nor32() {
        assertEquals(CropReason.ASPECT, refused(CaptureFrameInfo(4000.0, 2250.0)))
        assertEquals(CropReason.ASPECT, refused(CaptureFrameInfo(3000.0, 3000.0)))
    }

    @Test
    fun fit_should_RefuseTheKTierToo_When_FrameIsCropped() {
        val reason = refused(scene.frame().copy(zoomRatio = 1.4), FitOptions(request = TierRequest.Only(FitTier.GRADIENT_AND_K)))
        assertEquals(CropReason.ZOOM_RATIO, reason)
    }

    @Test
    fun cropReason_should_AllowFourThirdsThreeHalvesAndPortraitWithinTwoPercent() {
        assertNull(ConstraintCalibrator.cropReason(CaptureFrameInfo(4000.0, 3000.0)))
        assertNull(ConstraintCalibrator.cropReason(CaptureFrameInfo(3000.0, 4000.0)))
        assertNull(ConstraintCalibrator.cropReason(CaptureFrameInfo(4500.0, 3000.0)))
        assertNull(ConstraintCalibrator.cropReason(CaptureFrameInfo(4032.0, 3024.0)))
        assertNull(ConstraintCalibrator.cropReason(CaptureFrameInfo(4000.0, 3000.0, zoomRatio = 1.0)))
        assertEquals(CropReason.ASPECT, ConstraintCalibrator.cropReason(CaptureFrameInfo(4000.0, 2800.0)))
    }

    @Test
    fun scale_should_StillFit_When_FrameIsCropped() {
        val r = ConstraintCalibrator.fit(cs, scene.frame().copy(zoomRatio = 2.0))
        assertEquals(FitTier.SCALE, r.requested.right().tier)
    }

    @Test
    fun autoSelection_should_RecordTheCropRefusalAndKeepScale_When_GradientEnabled() {
        val fit = ConstraintCalibrator.fit(cs, scene.frame().copy(zoomRatio = 2.0), FitOptions(gradientEnabled = true)).requested.right()
        assertEquals(FitTier.SCALE, fit.tier)
        assertTrue(fit.declined.any { it.tier == FitTier.GRADIENT && it.failure is CalibrationFailure.CroppedOrZoomedFrame })
    }
}
