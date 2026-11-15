// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.FitTier
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.LensCalibration
import dev.stapler.stelekit.model.PlaneCalibration
import dev.stapler.stelekit.model.PlaneKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One test per row of the ADR-003 precedence table, plus the refusal rules of Task 3.3c. */
class ConstraintFitApplierTest {
    private val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(5), 6, 9.0, 0.2, spreadFraction = 0.6))
    private val cs = scene.training.map { it.toConstraint() }
    private val frame = scene.frame()
    private val gradientOn = FitOptions(request = TierRequest.Only(FitTier.GRADIENT), gradientEnabled = true)

    private val paper = PlaneCalibration(List(9) { if (it % 4 == 0) 1.0 else 0.0 }, "A4 sheet", 0.297, 0.21, 0.4)
    private val oldScale = Calibration(CalibrationMethod.MANUAL_REFERENCE, 800.0, 100, CalibrationBasis.NATIVE_PIXELS_LENS, LensCalibration(k = 0.01))
    private val withPaper = oldScale.copy(geometry = GeometryCalibration(heightOffsetMeters = 0.02, plane = paper))

    private fun applied(o: ConstraintFitApplier.Outcome) = assertIs<ConstraintFitApplier.Outcome.Applied>(o)

    private fun scaleResponse() = ConstraintCalibrator.fit(cs, frame)
    private fun gradientResponse() = ConstraintCalibrator.fit(cs, frame, gradientOn)

    @Test
    fun scaleTier_should_WritePixelsPerMeterAndClearPlane_When_PaperPlaneExisted() {
        val out = applied(ConstraintFitApplier.apply(withPaper, cs, scaleResponse(), frame))
        val c = out.calibration
        assertEquals(FitTier.SCALE, out.record.tier)
        assertEquals(scaleResponse().effective!!.pixelsPerMeter, c.pixelsPerMeter)
        assertNull(c.geometry?.plane, "else pixelsPerMeter would be silently ignored")
        assertEquals(0.02, c.geometry?.heightOffsetMeters, "height offset kept")
        assertEquals(oldScale.lens, c.lens, "lens kept")
        assertEquals(cs, c.geometry?.constraints)
        assertEquals(out.record, c.geometry?.fit)
    }

    @Test
    fun everyTier_should_WriteManualReferenceAndFullConfidence_So_OldBuildsKeepMeasuring() {
        val scale = applied(ConstraintFitApplier.apply(oldScale, cs, scaleResponse(), frame)).calibration
        val grad = applied(ConstraintFitApplier.apply(oldScale, cs, gradientResponse(), frame)).calibration
        for (c in listOf(scale, grad)) {
            assertEquals(CalibrationMethod.MANUAL_REFERENCE, c.method)
            assertEquals(100, c.confidencePercent)
            assertEquals(c.method, CalibrationMethod.valueOf(c.method.name), "loaders map by name; no new enum value")
            assertTrue(c.pixelsPerMeter > 0.0)
        }
        assertEquals(setOf("NONE", "BLE_LASER", "MANUAL_REFERENCE", "ARCORE_DEPTH", "LIDAR_DEPTH", "EXIF_FOCAL", "MONOCULAR_ML"), CalibrationMethod.entries.map { it.name }.toSet())
    }

    @Test
    fun gradientTier_should_WriteConstraintFitPlaneWithNonZeroExtentsAndNoCentreCheck() {
        val out = applied(ConstraintFitApplier.apply(oldScale, cs, gradientResponse(), frame))
        val fit = gradientResponse().effective!!
        val plane = assertNotNull(out.calibration.geometry?.plane)
        assertEquals(FitTier.GRADIENT, out.record.tier)
        assertEquals(PlaneKind.CONSTRAINT_FIT, plane.kind)
        assertEquals("Known distances", plane.referenceName)
        assertEquals(fit.homography, plane.homography)
        assertTrue(plane.widthMeters > 0.0 && plane.heightMeters > 0.0)
        assertNull(plane.checkResidualMm)
        assertEquals(fit.pixelsPerMeter, out.calibration.pixelsPerMeter)
        assertNull(out.fallback)
    }

    @Test
    fun gradientAndKTier_should_WriteLensKAndSnapshotThePreviousK() {
        val g = assertNotNull(gradientResponse().effective)
        val kFit = g.copy(report = g.report.copy(tier = FitTier.GRADIENT_AND_K), k = -0.02)
        val out = applied(ConstraintFitApplier.apply(oldScale, cs, FitResponse(kFit.right(), kFit), frame))
        assertEquals(-0.02, out.calibration.lens?.k)
        assertEquals(0.01, out.record.priorBasis?.lens?.k, "previous k lives in priorBasis")
        assertNotNull(out.calibration.geometry?.plane)
    }

    @Test
    fun gradientAndKTier_should_CreateLensAndUpgradeBasis_When_PhotoHadNoLens() {
        val g = assertNotNull(gradientResponse().effective)
        val kFit = g.copy(report = g.report.copy(tier = FitTier.GRADIENT_AND_K), k = -0.02)
        val plain = Calibration(CalibrationMethod.MANUAL_REFERENCE, 800.0, 100, CalibrationBasis.NATIVE_PIXELS)
        val c = applied(ConstraintFitApplier.apply(plain, cs, FitResponse(kFit.right(), kFit), frame)).calibration
        assertEquals(-0.02, c.lens?.k)
        assertEquals(CalibrationBasis.NATIVE_PIXELS_LENS, c.basisVersion)
    }

    @Test
    fun applier_should_NotWriteAPlane_When_RequestedIsCroppedOrZoomed() {
        val cropped = ConstraintCalibrator.fit(cs, frame.copy(zoomRatio = 2.0), gradientOn)
        assertIs<CalibrationFailure.CroppedOrZoomedFrame>((cropped.requested as Either.Left).value)
        val out = applied(ConstraintFitApplier.apply(withPaper, cs, cropped, frame))
        assertNull(out.calibration.geometry?.plane?.takeIf { it.kind == PlaneKind.CONSTRAINT_FIT })
        assertNull(out.calibration.geometry?.plane, "the SCALE fallback clears the paper plane as a SCALE fit does")
        assertEquals(FitTier.SCALE, out.record.tier)
        assertIs<CalibrationFailure.CroppedOrZoomedFrame>(out.fallback)
    }

    @Test
    fun applier_should_NotWriteAPlane_When_RequestedIsTiltTooLarge() {
        val scale = assertNotNull(scaleResponse().effective)
        val fit = scale.copy(homography = List(9) { if (it % 4 == 0) 1.0 else 0.0 })
        val out = applied(ConstraintFitApplier.apply(oldScale, cs, FitResponse(CalibrationFailure.TiltTooLarge(14.0).left(), fit), frame))
        assertNull(out.calibration.geometry?.plane)
        assertIs<CalibrationFailure.TiltTooLarge>(out.fallback)
    }

    @Test
    fun applier_should_DeclineAndChangeNothing_When_NoFitExists() {
        val response = FitResponse(CalibrationFailure.Collinear(2.0).left(), null)
        val out = ConstraintFitApplier.apply(oldScale, cs, response, frame)
        assertEquals(ConstraintFitApplier.Outcome.Declined(CalibrationFailure.Collinear(2.0)), out)
    }

    @Test
    fun applier_should_Decline_When_BasisIsLegacy() {
        val legacy = Calibration(CalibrationMethod.MANUAL_REFERENCE, 800.0, 100, CalibrationBasis.LEGACY_DECODE_RELATIVE)
        assertEquals(ConstraintFitApplier.Outcome.Declined(CalibrationFailure.LegacyBasis), ConstraintFitApplier.apply(legacy, cs, scaleResponse(), frame))
    }

    @Test
    fun applier_should_CalibrateAnUncalibratedPhotoOnTheNativeBasis() {
        val c = applied(ConstraintFitApplier.apply(null, cs, scaleResponse(), frame)).calibration
        assertEquals(CalibrationBasis.NATIVE_PIXELS_LENS, c.basisVersion)
        assertEquals(CalibrationMethod.MANUAL_REFERENCE, c.method)
    }

    @Test
    fun priorBasis_should_SnapshotReplacedBasisAndRestoreItExactly() {
        val out = applied(ConstraintFitApplier.apply(withPaper, cs, gradientResponse(), frame))
        val prior = assertNotNull(out.record.priorBasis)
        assertEquals(paper, prior.plane)
        assertEquals(800.0, prior.pixelsPerMeter)
        val restored = ConstraintFitApplier.restorePriorBasis(out.calibration)
        assertEquals(withPaper, restored)
    }

    @Test
    fun priorBasis_should_KeepTheOriginal_When_RefittingAnExistingFit() {
        val first = applied(ConstraintFitApplier.apply(withPaper, cs, scaleResponse(), frame)).calibration
        val second = applied(ConstraintFitApplier.apply(first, cs + constraint("extra", 0.1, 0.1, 0.4, 0.1, 1.0), scaleResponse(), frame))
        assertEquals(paper, second.record.priorBasis?.plane)
        assertEquals(withPaper, ConstraintFitApplier.restorePriorBasis(second.calibration))
    }

    @Test
    fun restore_should_ClearConstraintsAndFitButKeepHeightOffset_When_PriorWasAnUncalibratedPhoto() {
        val fresh = Calibration()
        val fitted = applied(ConstraintFitApplier.apply(fresh, cs, scaleResponse(), frame)).calibration
        val withHeight = fitted.copy(geometry = fitted.geometry?.copy(heightOffsetMeters = 0.03))
        val restored = ConstraintFitApplier.restorePriorBasis(withHeight)
        assertEquals(CalibrationMethod.NONE, restored.method)
        assertEquals(emptyList(), restored.geometry?.constraints)
        assertNull(restored.geometry?.fit)
        assertEquals(0.03, restored.geometry?.heightOffsetMeters)
    }

    @Test
    fun lineOrRulerCalibration_should_ClearPlaneConstraintsAndFitButKeepHeightOffset() {
        val fitted = applied(ConstraintFitApplier.apply(withPaper, cs, gradientResponse(), frame)).calibration
        val ruler = Calibration(CalibrationMethod.MANUAL_REFERENCE, 900.0, 100, CalibrationBasis.NATIVE_PIXELS_LENS, LensCalibration())
        val c = ConstraintFitApplier.replacingBasis(fitted, ruler)
        assertEquals(900.0, c.pixelsPerMeter)
        assertNull(c.geometry?.plane)
        assertEquals(emptyList(), c.geometry?.constraints)
        assertNull(c.geometry?.fit)
        assertEquals(0.02, c.geometry?.heightOffsetMeters)
    }

    @Test
    fun lineOrRulerCalibration_should_LeaveNoGeometry_When_NothingToKeep() {
        val fitted = applied(ConstraintFitApplier.apply(oldScale, cs, scaleResponse(), frame)).calibration
        assertNull(ConstraintFitApplier.replacingBasis(fitted, oldScale).geometry)
    }

    @Test
    fun paperPlane_should_KeepItsPlaneAndClearConstraintsAndFit() {
        val fitted = applied(ConstraintFitApplier.apply(withPaper, cs, scaleResponse(), frame)).calibration
        val newPaper = oldScale.copy(geometry = GeometryCalibration(plane = paper.copy(referenceName = "Card")))
        val c = ConstraintFitApplier.replacingBasis(fitted, newPaper)
        assertEquals("Card", c.geometry?.plane?.referenceName)
        assertEquals(PlaneKind.PAPER_REFERENCE, c.geometry?.plane?.kind)
        assertEquals(emptyList(), c.geometry?.constraints)
        assertNull(c.geometry?.fit)
        assertEquals(0.02, c.geometry?.heightOffsetMeters)
    }

    @Test
    fun heightOffset_should_KeepPlaneConstraintsAndFit() {
        val fitted = applied(ConstraintFitApplier.apply(oldScale, cs, gradientResponse(), frame)).calibration
        val c = ConstraintFitApplier.withHeightOffset(fitted, 0.05, null)
        assertEquals(fitted.geometry?.plane, c.geometry?.plane)
        assertEquals(fitted.geometry?.constraints, c.geometry?.constraints)
        assertEquals(fitted.geometry?.fit, c.geometry?.fit)
        assertEquals(0.05, c.geometry?.heightOffsetMeters)
    }

    @Test
    fun heightOffset_should_NotNullTheGeometry_When_ScaleFitHasNoPlaneAndOffsetIsCleared() {
        val fitted = applied(ConstraintFitApplier.apply(oldScale, cs, scaleResponse(), frame)).calibration
        val edited = ConstraintFitApplier.withHeightOffset(ConstraintFitApplier.withHeightOffset(fitted, 0.05, null), 0.0, null)
        assertEquals(cs, edited.geometry?.constraints, "edit twice: constraints survive")
        assertNotNull(edited.geometry?.fit)
    }

    @Test
    fun heightOffset_should_LeaveNoGeometry_When_NothingIsSet() {
        assertNull(ConstraintFitApplier.withHeightOffset(oldScale, 0.0, null).geometry)
    }
}
