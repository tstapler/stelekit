// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Story 6.2b: [CalibrationService] records the basis and honours it so a decode-size change cannot rescale. */
class CalibrationBasisServiceTest {
    private fun reference(basis: Int? = null): Calibration? {
        val a = NormalizedPoint(0.0, 0.5)
        val b = NormalizedPoint(0.1, 0.5)
        return if (basis == null) CalibrationService.computeFromReference(a, b, 1000.0, 1000.0, 1.0)
        else CalibrationService.computeFromReference(a, b, 1000.0, 1000.0, 1.0, basis)
    }

    @Test
    fun computeFromReference_should_DefaultToLegacyBasis() {
        val cal = assertNotNull(reference())
        assertEquals(CalibrationBasis.LEGACY_DECODE_RELATIVE, cal.basisVersion)
        assertTrue(cal.isLegacyBasis)
        assertEquals(100.0, cal.pixelsPerMeter, 1e-9)
    }

    @Test
    fun computeFromReference_should_RecordNativeBasis_When_Requested() {
        val cal = assertNotNull(reference(CalibrationBasis.NATIVE_PIXELS))
        assertEquals(CalibrationBasis.NATIVE_PIXELS, cal.basisVersion)
        assertFalse(cal.isLegacyBasis)
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, cal.method)
    }

    @Test
    fun computeFromReference_should_Reject_UnknownBasis() {
        assertFailsWith<IllegalArgumentException> { reference(7) }
    }

    @Test
    fun pixelsPerMeterAt_should_NeverRescaleLegacyCalibration() {
        val legacy = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100)
        assertEquals(100.0, CalibrationService.pixelsPerMeterAt(legacy, 4000.0, 1000.0), 0.0)
        assertEquals(100.0, CalibrationService.pixelsPerMeterAt(legacy, 4000.0, 2000.0), 0.0)
    }

    @Test
    fun pixelsPerMeterAt_should_ScaleNativeCalibrationWithRenderedWidth() {
        val native = Calibration(CalibrationMethod.MANUAL_REFERENCE, 400.0, 100, CalibrationBasis.NATIVE_PIXELS)
        assertEquals(100.0, CalibrationService.pixelsPerMeterAt(native, 4000.0, 1000.0), 1e-9)
        assertEquals(400.0, CalibrationService.pixelsPerMeterAt(native, 4000.0, 4000.0), 1e-9)
    }

    @Test
    fun pixelsPerMeterAt_should_FallBackToStoredValue_When_WidthsAreInvalid() {
        val native = Calibration(CalibrationMethod.MANUAL_REFERENCE, 400.0, 100, CalibrationBasis.NATIVE_PIXELS)
        listOf(0.0 to 1000.0, 4000.0 to 0.0, Double.NaN to 1000.0, 4000.0 to Double.POSITIVE_INFINITY).forEach { (n, r) ->
            assertEquals(400.0, CalibrationService.pixelsPerMeterAt(native, n, r), 0.0)
        }
    }

    @Test
    fun isKnown_should_AcceptOnlyDefinedVersions() {
        // 2 became a defined basis (native pixels + lens model); the first undefined version is now 3.
        assertTrue(CalibrationBasis.isKnown(0) && CalibrationBasis.isKnown(1) && CalibrationBasis.isKnown(2))
        assertFalse(CalibrationBasis.isKnown(3))
        assertFalse(CalibrationBasis.isKnown(-1))
    }
}
