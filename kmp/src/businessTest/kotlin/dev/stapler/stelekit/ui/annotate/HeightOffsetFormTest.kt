// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.MeasurementUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeightOffsetFormTest {
    private val w = 4000.0
    private val h = 3000.0
    private val focal35 = 3100.0 / 5000.0 * 43.2666
    private val cal = Calibration(CalibrationMethod.MANUAL_REFERENCE, 3100.0 / 0.6, 100, CalibrationBasis.NATIVE_PIXELS)

    private fun preview(draft: HeightOffsetDraft, focal: Double? = focal35, calibration: Calibration = cal) =
        draft.preview(calibration, focal, w, h)

    @Test
    fun blankHeightClearsTheOffset() {
        val p = preview(HeightOffsetDraft())
        assertTrue(p.canApply)
        assertEquals(0.0, p.geometry?.heightOffsetMeters)
    }

    @Test
    fun heightWithDerivedDistanceShowsTheShrinkAndApplies() {
        val p = preview(HeightOffsetDraft(heightText = "10"))
        val g = assertNotNull(p.geometry)
        assertEquals(0.01, g.heightOffsetMeters, 1e-12)
        assertNull(g.cameraDistanceMeters)
        assertTrue(p.message.contains("Camera distance about 60 cm (from the focal length)"), p.message)
        assertTrue(p.message.contains("shrink by 1.7% (x0.983)"), p.message)
    }

    @Test
    fun unknownDistanceBlocksApplyUntilTyped() {
        val noFocal = HeightOffsetDraft(heightText = "5")
        assertFalse(preview(noFocal, focal = null).canApply)
        assertEquals(HeightOffsetCopy.NEEDS_DISTANCE, preview(noFocal, focal = null).message)
        val typed = preview(noFocal.copy(distanceText = "50"), focal = null)
        assertEquals(0.5, typed.geometry?.cameraDistanceMeters)
        assertTrue(typed.message.contains("(typed)"))
    }

    @Test
    fun invalidAndOversizedInputIsRejectedWithAReason() {
        assertFalse(preview(HeightOffsetDraft(heightText = "abc")).canApply)
        assertFalse(preview(HeightOffsetDraft(heightText = "-2")).canApply)
        assertFalse(preview(HeightOffsetDraft(heightText = "5", distanceText = "0")).canApply)
        val big = preview(HeightOffsetDraft(heightText = "400"))
        assertFalse(big.canApply)
        assertTrue(big.message.contains("too large"))
    }

    @Test
    fun unitsConvert() {
        val p = preview(HeightOffsetDraft(heightText = "0.5", heightUnit = MeasurementUnit.CENTIMETERS))
        assertEquals(0.005, p.geometry!!.heightOffsetMeters, 1e-12)
    }

    @Test
    fun draftReproducesASavedOffset() {
        val d = draftFor(GeometryCalibration(0.0025, 0.6))
        assertEquals("2.5", d.heightText)
        assertEquals("60", d.distanceText)
        assertEquals(HeightOffsetDraft(), draftFor(null))
    }
}
