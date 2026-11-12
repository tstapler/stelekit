// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.LensCalibration
import dev.stapler.stelekit.model.NormalizedPoint
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasurementAdvisorTest {
    private val w = 4000.0
    private val h = 3000.0
    private val object18 = listOf(NormalizedPoint(0.45, 0.5), NormalizedPoint(0.55, 0.5))

    /** A horizontal ruler through the image centre with ticks every [stepPx] px starting at 400 px. */
    private fun calibration(count: Int, stepPx: Double, k: Double = 0.0, residual: Double = 0.0): Calibration {
        val ticks = (0 until count).map { CalibrationTick((400.0 + stepPx * it) / w, 0.5, it * 0.1) }
        return Calibration(
            method = CalibrationMethod.MANUAL_REFERENCE,
            pixelsPerMeter = stepPx / 0.1,
            confidencePercent = 100,
            basisVersion = CalibrationBasis.NATIVE_PIXELS_LENS,
            lens = LensCalibration(k, ticks, residual),
        )
    }

    private fun assess(cal: Calibration, pitch: Double? = 0.0, roll: Double? = 0.0) =
        MeasurementAdvisor.assess(object18, cal, w, h, pitch, roll)

    @Test
    fun longRulerFlatCameraSaysNothing() {
        val r = assess(calibration(4, 800.0))
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
        assertTrue(r.notes.isEmpty())
        assertNull(r.guidance)
    }

    @Test
    fun shortReferenceWarnsWithTheTapErrorShare() {
        // 60 px reference (golden scene T-G7): sqrt(2) / 60 = 2.4% per pixel of tap error.
        val r = assess(calibration(2, 60.0))
        val warning = r.warnings.single { it.startsWith("Short reference") }
        assertTrue(warning.contains("2.4%"), warning)
        assertEquals(2.36, r.estimatedErrorPercent, 0.01)
        assertEquals(LensMeasurement.SHOOTING_GUIDANCE, r.guidance)
    }

    @Test
    fun referenceExactlyAtTheThresholdDoesNotWarn() {
        val spanPx = MeasurementAdvisor.SHORT_REFERENCE_FRACTION * w
        assertTrue(assess(calibration(2, spanPx)).warnings.none { it.startsWith("Short reference") })
    }

    @Test
    fun anySpanUnderFifteenPercentWarnsAndAnyOverDoesNot() = runTest {
        checkAll(Arb.int(10, 1200)) { spanPx ->
            val warned = assess(calibration(2, spanPx.toDouble())).warnings.any { it.startsWith("Short reference") }
            assertEquals(spanPx < MeasurementAdvisor.SHORT_REFERENCE_FRACTION * w, warned, "span $spanPx")
        }
    }

    @Test
    fun twoTicksSuggestAThirdWithoutWarning() {
        val r = assess(calibration(2, 800.0))
        assertTrue(r.warnings.isEmpty())
        assertTrue(r.notes.single().startsWith("Only 2 ruler marks"))
    }

    @Test
    fun appliedLensCorrectionIsNoted() {
        val r = assess(calibration(4, 800.0, k = -0.01))
        assertTrue(r.notes.contains("Lens correction applied (k = -0.010)."), r.notes.toString())
    }

    @Test
    fun highResidualWarnsViaTheLensAssessment() {
        val r = assess(calibration(4, 800.0, residual = 2.5))
        assertTrue(r.warnings.single().startsWith("Ruler marks disagree by 2.5%"))
        assertEquals(2.5, r.estimatedErrorPercent, 1e-9)
    }

    @Test
    fun calibrationLevelCheckStillFlagsResidualWithoutAnObject() {
        val r = MeasurementAdvisor.assess(emptyList(), calibration(4, 800.0, residual = 2.5), w, h, 0.0, 0.0)
        assertTrue(r.hasWarning)
    }

    @Test
    fun tiltTiersGiveMessagesAndUnknownIsANote() {
        val cal = calibration(4, 800.0)
        assertTrue(assess(cal, 2.0, 1.0).warnings.isEmpty())
        val mild = assess(cal, 5.0, 0.0)
        assertTrue(mild.warnings.single().startsWith("Camera tilt about 5° may add up to about 3.5%"), mild.warnings.toString())
        assertTrue(assess(cal, 20.0, 0.0).hasWarning)
        val unknown = assess(cal, null, null)
        assertTrue(unknown.warnings.isEmpty())
        assertEquals(listOf(MeasurementAdvisor.TILT_UNKNOWN_NOTE), unknown.notes)
    }

    @Test
    fun calibrationsWithoutTicksOnlyGetTiltAdvice() {
        val basic = Calibration(CalibrationMethod.MANUAL_REFERENCE, 4000.0, 100, CalibrationBasis.NATIVE_PIXELS)
        assertTrue(assess(basic).warnings.isEmpty())
        assertTrue(assess(basic, 8.0, 0.0).hasWarning)
        assertNotNull(assess(basic, null, null).notes.firstOrNull())
    }

    @Test
    fun estimatedErrorIsTheLargestContributor() {
        val r = assess(calibration(2, 60.0, residual = 0.2), pitch = 10.0, roll = 0.0)
        assertEquals(maxOf(2.36, TiltAssessment.acrossFramePercent(10.0)), r.estimatedErrorPercent, 0.01)
    }

    @Test
    fun pillGetsTheWarningMarkOnlyWhenWarned() {
        assertEquals("⚠ 18.0 cm", MeasurementAdvisor.pillText("18.0 cm", true))
        assertEquals("18.0 cm", MeasurementAdvisor.pillText("18.0 cm", false))
        assertFalse(MeasurementAdvisor.pillText("x", false).contains("⚠"))
    }
}
