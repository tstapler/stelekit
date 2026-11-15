// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.FitTier
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 3.3a: tiers SCALE and PER_AXIS, hard validation, and every typed failure (U-E3-03/04/05/11/12). */
class ConstraintCalibratorFailureTest {
    private val frame = FRAME_4000x3000

    /** Horizontal segment 0.5 of the frame wide (2000 px) at height [y]. */
    private fun row(id: String, y: Double, meters: Double, x1: Double = 0.2) = constraint(id, x1, y, x1 + 0.5, y, meters)

    @Test
    fun fit_should_ReturnZeroLengthSegment_When_BothEndsCoincide() {
        val r = ConstraintCalibrator.fit(listOf(constraint("z", 0.5, 0.5, 0.5, 0.5, 0.9), row("a", 0.3, 0.9)), frame)
        assertEquals(CalibrationFailure.ZeroLengthSegment("z"), r.requested.left())
        assertNull(r.effective)
    }

    @Test
    fun fit_should_ReturnNonPositiveDistance_When_DistanceNegativeZeroOrNaN() {
        for (bad in listOf(-0.5, 0.0, Double.NaN)) {
            assertEquals(CalibrationFailure.NonPositiveDistance("n"), ConstraintCalibrator.fit(listOf(row("n", 0.3, bad)), frame).requested.left())
        }
    }

    @Test
    fun fit_should_ReturnLegacyBasis_When_BasisVersionZero() {
        val r = ConstraintCalibrator.fit(listOf(row("a", 0.3, 0.9)), frame, FitOptions(basisVersion = CalibrationBasis.LEGACY_DECODE_RELATIVE))
        assertEquals(CalibrationFailure.LegacyBasis, r.requested.left())
    }

    @Test
    fun fit_should_ReturnTooFew_When_NoConstraints() {
        assertEquals(CalibrationFailure.TooFewConstraints(1, 0), ConstraintCalibrator.fit(emptyList(), frame).requested.left())
    }

    @Test
    fun fit_should_ReturnTooFew5_When_GradientRequestedWithTwoConstraints() {
        val r = ConstraintCalibrator.fit(
            listOf(row("a", 0.3, 0.9), row("b", 0.6, 0.9)), frame, FitOptions(request = TierRequest.Only(FitTier.GRADIENT)),
        )
        assertEquals(CalibrationFailure.TooFewConstraints(5, 2), r.requested.left())
        assertEquals(FitTier.SCALE, assertNotNull(r.effective).tier)
    }

    @Test
    fun gradient_should_BeIneligibleAndSayNeedsOneMore_When_FourConstraints() {
        val four = listOf(row("a", 0.2, 0.9), row("b", 0.4, 0.9), row("c", 0.6, 0.9), row("d", 0.8, 0.9))
        val fit = ConstraintCalibrator.fit(four, frame, FitOptions(gradientEnabled = true)).requested.right()
        assertEquals(FitTier.SCALE, fit.tier)
        assertEquals(CalibrationFailure.TooFewConstraints(5, 4), fit.declined.first { it.tier == FitTier.GRADIENT }.failure)
        assertTrue(fit.notes.any { it.contains("needs 1 more distance") }, fit.notes.toString())
    }

    @Test
    fun fit_should_ReturnScaleWithAgreementPctAndNullLoo_When_ExactlyTwoConstraints() {
        val fit = ConstraintCalibrator.fit(listOf(row("a", 0.3, 0.90), row("b", 0.6, 0.92)), frame).requested.right()
        assertEquals(FitTier.SCALE, fit.tier)
        assertNull(fit.report.looPct)
        assertEquals(2.197802, assertNotNull(fit.report.agreementPct), 1e-4) // |0.90-0.92| / 0.91
    }

    @Test
    fun fit_should_ReportApproxScaleWithoutLoo_When_OneConstraint() {
        val fit = ConstraintCalibrator.fit(listOf(row("a", 0.3, 1.0)), frame).requested.right()
        assertEquals(1, fit.report.n)
        assertNull(fit.report.looPct)
        assertNull(fit.report.agreementPct)
        assertEquals(2000.0, fit.pixelsPerMeter, 1e-6)
        assertNull(fit.homography)
    }

    @Test
    fun fit_should_ReturnCollinearAndScaleFallback_When_PerAxisRequestedOnParallelSegments() {
        val three = listOf(row("a", 0.3, 0.9), row("b", 0.5, 0.9), row("c", 0.7, 0.9))
        val r = ConstraintCalibrator.fit(three, frame, FitOptions(request = TierRequest.Only(FitTier.PER_AXIS)))
        assertEquals(CalibrationFailure.Collinear(0.0), r.requested.left())
        val eff = assertNotNull(r.effective)
        assertEquals(FitTier.SCALE, eff.tier)
        assertTrue(eff.notes.isNotEmpty())
    }

    @Test
    fun perAxis_should_RecoverAnisotropicScale_When_SegmentsSpanDirections() {
        // 1 px = 1 mm horizontally and 1.5 mm vertically.
        fun seg(id: String, dxPx: Double, dyPx: Double, cx: Double, cy: Double): CalibrationConstraint {
            val m = hypot(dxPx * 0.001, dyPx * 0.0015)
            return constraint(id, (cx - dxPx / 2) / 4000.0, (cy - dyPx / 2) / 3000.0, (cx + dxPx / 2) / 4000.0, (cy + dyPx / 2) / 3000.0, m)
        }
        val cs = listOf(
            seg("a", 1000.0, 0.0, 1500.0, 1000.0), seg("b", 0.0, 800.0, 2500.0, 1500.0),
            seg("c", 700.0, 700.0, 2000.0, 2000.0), seg("d", 700.0, -700.0, 2200.0, 800.0),
        )
        val fit = ConstraintCalibrator.fit(cs, frame, FitOptions(request = TierRequest.Only(FitTier.PER_AXIS))).requested.right()
        assertEquals(FitTier.PER_AXIS, fit.tier)
        val h = assertNotNull(fit.homography)
        assertEquals(0.001, h[0], 1e-9)
        assertEquals(0.0015, h[4], 1e-9)
        assertTrue(fit.report.residualMeanPct < 1e-6)
    }

    @Test
    fun fit_should_NameWorstConstraintAndImpliedRatio_When_UnitTypo() {
        val cs = listOf(row("a", 0.3, 0.90), row("b", 0.5, 0.88), row("c", 0.7, 2.54))
        val fit = ConstraintCalibrator.fit(cs, frame).requested.right()
        val worst = assertNotNull(fit.report.worst)
        assertEquals("c", worst.id)
        assertEquals(2.54 / 0.90, worst.impliedScaleRatio, 0.03)
    }

    @Test
    fun fit_should_FlagMixedSurfaces_When_ResidualsSplitIntoTwoGroups() {
        val cs = listOf(row("a", 0.2, 1.00), row("b", 0.3, 1.00), row("c", 0.5, 1.10), row("d", 0.7, 1.10))
        assertTrue(ConstraintCalibrator.fit(cs, frame).requested.right().report.mixedSurfaces)
    }

    @Test
    fun fit_should_NotFlagMixedSurfaces_When_ResidualsAreSmall() {
        val cs = listOf(row("a", 0.2, 1.000), row("b", 0.3, 1.004), row("c", 0.5, 0.997), row("d", 0.7, 1.002))
        assertTrue(!ConstraintCalibrator.fit(cs, frame).requested.right().report.mixedSurfaces)
    }

    @Test
    fun fit_should_ReturnConflicting_When_TwoGroupsDisagreeByMoreThan2x() {
        val cs = listOf(row("a", 0.2, 0.5), row("b", 0.3, 0.5), row("c", 0.5, 1.5), row("d", 0.7, 1.5))
        val failure = ConstraintCalibrator.fit(cs, frame).requested.left()
        assertTrue(failure is CalibrationFailure.ConflictingConstraints, failure.toString())
        assertTrue((failure as CalibrationFailure.ConflictingConstraints).impliedScaleRatio >= 2.0)
    }

    @Test
    fun scale_should_ReportLoo_When_ThreeOrMoreConstraints() {
        val cs = listOf(row("a", 0.3, 1.0), row("b", 0.5, 1.01), row("c", 0.7, 0.99))
        val fit = ConstraintCalibrator.fit(cs, frame).requested.right()
        assertEquals(FitTier.SCALE, fit.tier)
        assertTrue(fit.report.looPct!! in 0.5..3.0, fit.report.looPct.toString())
    }

    @Test
    fun scale_should_RecoverTruthExactly_When_NoiseFreeFrontoParallel() {
        repeat(30) { i ->
            val scene = assertNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, Random(i), 6, 0.0, 0.0))
            val fit = ConstraintCalibrator.fit(scene.training.map { it.toConstraint() }, scene.frame()).requested.right()
            val h = scene.heldOut
            val lenPx = hypot((h.p2.x - h.p1.x) * scene.widthPx, (h.p2.y - h.p1.y) * scene.heightPx)
            assertTrue(abs(lenPx / fit.pixelsPerMeter - h.trueMeters) / h.trueMeters < 1e-6)
            assertTrue(fit.report.residualMeanPct < 1e-6)
        }
    }

    @Test
    fun everyFailureVariant_should_HaveAReadableDescription() {
        val all = listOf(
            CalibrationFailure.TooFewConstraints(5, 2), CalibrationFailure.Collinear(0.0), CalibrationFailure.ZeroLengthSegment("a"),
            CalibrationFailure.NonPositiveDistance("a"), CalibrationFailure.ConflictingConstraints("a", 3.0),
            CalibrationFailure.IllConditioned(IllConditionedAspect.POSITION_SPREAD), CalibrationFailure.NoMeasurableTilt(1.0, 5.0),
            CalibrationFailure.DidNotConverge, CalibrationFailure.LegacyBasis, CalibrationFailure.CroppedOrZoomedFrame(CropReason.ASPECT),
            CalibrationFailure.TiltTooLarge(12.0), CalibrationFailure.NotBetterThanSimpler(1.0, 1.0), CalibrationFailure.TierDisabled(FitTier.GRADIENT),
        )
        assertTrue(all.all { it.describe().isNotBlank() })
        assertEquals("needs 3 more distances", CalibrationFailure.TooFewConstraints(5, 2).describe())
    }
}
