// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.units.Rational
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PngSheetAdapterTest {
    private val size = PngSize(1000, 500)

    private fun reference(lengthInches: Long = 100) =
        RasterReference("total-width", NormalizedPoint(0.0, 0.5), NormalizedPoint(1.0, 0.5), Rational(lengthInches))

    @Test
    fun readSize_should_ParseIhdr() {
        assertEquals(PngSize(1000, 500), PngSheetAdapter.readSize(pngHeader(1000, 500)))
    }

    @Test
    fun readSize_should_ReturnNull_ForShortNonPngOrZeroSize() {
        assertNull(PngSheetAdapter.readSize(byteArrayOf(1, 2)))
        assertNull(PngSheetAdapter.readSize(pngHeader(1000, 500).also { it[1] = 0 }))
        assertNull(PngSheetAdapter.readSize(pngHeader(1000, 500).also { it[12] = 'X'.code.toByte() }))
        assertNull(PngSheetAdapter.readSize(pngHeader(0, 500)))
    }

    @Test
    fun calibrate_should_UseNativePixelBasis_AndStatedLength() {
        val c = assertNotNull(PngSheetAdapter.calibrate(size, reference()))
        assertEquals(CalibrationBasis.NATIVE_PIXELS, c.basisVersion)
        assertEquals(100, c.confidencePercent)
        val expected = 1000.0 / (100 * CalibratedLength.METERS_PER_INCH)
        assertTrue(abs(c.pixelsPerMeter - expected) < 1e-6)
    }

    @Test
    fun calibrate_should_ReturnNull_ForZeroLengthLineOrNonPositiveValue() {
        val point = NormalizedPoint(0.2, 0.2)
        assertNull(PngSheetAdapter.calibrate(size, reference().copy(start = point, end = point)))
        assertNull(PngSheetAdapter.calibrate(size, reference(0)))
    }

    @Test
    fun measuredInches_should_RecoverReferenceLength_AtNativeAndScaledWidth() {
        val c = assertNotNull(PngSheetAdapter.calibrate(size, reference()))
        assertEquals(Rational(100), PngSheetAdapter.measuredInches(c, size, 1000.0, 1000.0))
        assertEquals(Rational(100), PngSheetAdapter.measuredInches(c, size, 250.0, 250.0))
        assertEquals(Rational(50), PngSheetAdapter.measuredInches(c, size, 250.0, 125.0))
    }

    @Test
    fun measuredInches_should_NeverRescaleLegacyBasis() {
        val legacy = assertNotNull(PngSheetAdapter.calibrate(size, reference())).copy(basisVersion = CalibrationBasis.LEGACY_DECODE_RELATIVE)
        assertEquals(Rational(100), PngSheetAdapter.measuredInches(legacy, size, 1000.0, 1000.0))
        assertEquals(Rational(100), PngSheetAdapter.measuredInches(legacy, size, 250.0, 1000.0))
    }

    @Test
    fun measuredInches_should_ReturnNull_ForUncalibrated() {
        val none = assertNotNull(PngSheetAdapter.calibrate(size, reference())).copy(pixelsPerMeter = 0.0)
        assertNull(PngSheetAdapter.measuredInches(none, size, 1000.0, 10.0))
    }

    @Test
    fun metersToInches_should_RoundToSixteenths_AndRejectNonFinite() {
        assertEquals(Rational(1, 4), CalibratedLength.metersToInches(0.25 * CalibratedLength.METERS_PER_INCH))
        assertEquals(Rational(5, 16), CalibratedLength.metersToInches(0.3 * CalibratedLength.METERS_PER_INCH))
        assertNull(CalibratedLength.metersToInches(Double.NaN))
        assertNull(CalibratedLength.metersToInches(Double.POSITIVE_INFINITY))
        assertNull(CalibratedLength.metersToInches(1e30))
    }

    @Test
    fun referenceDimension_should_CarryStatedValueAndPngSource() {
        val d = PngSheetAdapter.referenceDimension(ProjectUuid("p1"), "u1", reference())
        assertEquals(Rational(100), d.value)
        assertEquals("png", d.sourceKind)
        assertEquals("total-width", d.key)
    }
}

/** IHDR-only PNG prefix: signature, chunk length 13, `IHDR`, width, height. */
internal fun pngHeader(width: Int, height: Int): ByteArray {
    val out = ArrayList<Byte>()
    out += listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map { it.toByte() }
    out += listOf(0, 0, 0, 13)
    out += "IHDR".map { it.code.toByte() }
    for (v in listOf(width, height)) out += listOf(v ushr 24, v ushr 16, v ushr 8, v).map { it.toByte() }
    out += listOf(8, 6, 0, 0, 0).map { it.toByte() }
    return out.toByteArray()
}
