// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ConstraintFitRecord
import dev.stapler.stelekit.model.ConstraintResidual
import dev.stapler.stelekit.model.FitReport
import dev.stapler.stelekit.model.FitTier
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationStatePresenterTest {
    private fun seg(id: String, x1: Double, y1: Double, x2: Double, y2: Double, label: String? = null) =
        CalibrationConstraint(id, NormalizedPoint(x1, y1), NormalizedPoint(x2, y2), 0.9, label)

    private val spread = listOf(
        seg("a", 0.05, 0.1, 0.3, 0.15),
        seg("b", 0.6, 0.8, 0.95, 0.85),
        seg("c", 0.1, 0.9, 0.2, 0.5),
    )

    private fun cal(method: CalibrationMethod, basis: Int = CalibrationBasis.NATIVE_PIXELS) =
        Calibration(method, 1000.0, 90, basis)

    private fun fit(n: Int, tier: FitTier = FitTier.SCALE, residual: Double = 1.2, loo: Double? = null) =
        ConstraintFitRecord(tier, n, residual, loo)

    private fun present(
        constraints: List<CalibrationConstraint>,
        calibration: Calibration? = cal(CalibrationMethod.MANUAL_REFERENCE),
        fit: ConstraintFitRecord? = null,
        report: FitReport? = null,
        gradient: Boolean = false,
        perAxis: Boolean = false,
    ) = CalibrationStatePresenter.present(calibration, constraints, fit, report, gradient, perAxis)

    @Test
    fun pill_should_ShowLadderTextAndIcon_For_0_1_2_3Plus_Legacy_AndBasis0() {
        val none = present(emptyList(), calibration = null)
        assertEquals("Not calibrated - add a known distance", none.pill.text)
        assertEquals(PillIcon.NONE, none.pill.icon)
        assertEquals("Not calibrated - add a known distance", present(emptyList(), cal(CalibrationMethod.NONE)).pill.text)

        val one = present(spread.take(1))
        assertEquals("Scale from 1 distance (approx.)", one.pill.text)
        assertEquals("Add another", one.pill.actionLabel)

        val two = present(spread.take(2), report = FitReport(FitTier.SCALE, 2, 0.0, agreementPct = 2.34))
        assertEquals("Scale from 2 distances, agree within 2.3%", two.pill.text)
        assertEquals("Scale from 2 distances", present(spread.take(2)).pill.text)

        val three = present(spread, fit = fit(3))
        assertEquals("Calibrated from 3 distances, +/-1.2%", three.pill.text)
        assertEquals(PillIcon.CHECK, three.pill.icon)
        assertTrue(three.pill.showConfidenceBadge)
        assertEquals("Model: scale. Estimated accuracy +/-1.2%", three.pill.detail)
        assertEquals("Calibrated from 3 distances, +/-1.8%", present(spread, fit = fit(3, loo = 1.8)).pill.text)

        val fellBack = present(spread, fit = fit(3), report = FitReport(FitTier.SCALE, 3, 1.0, fallbackReason = "The distances are in a line"))
        assertEquals("Scale from 3 distances (basic)", fellBack.pill.text)
        assertEquals("The distances are in a line", fellBack.pill.detail)
        assertFalse(fellBack.pill.showConfidenceBadge)
    }

    @Test
    fun legacyMethods_should_KeepTheirLabels_AndNeverReadNotCalibrated() {
        val labels = mapOf(
            CalibrationMethod.MANUAL_REFERENCE to "Scale set",
            CalibrationMethod.BLE_LASER to "Scale set",
            CalibrationMethod.ARCORE_DEPTH to "Scale estimated",
            CalibrationMethod.EXIF_FOCAL to "Scale uncertain",
            CalibrationMethod.MONOCULAR_ML to "Scale uncertain",
        )
        labels.forEach { (method, label) ->
            val p = present(emptyList(), cal(method)).pill
            assertEquals(label, p.text, "$method")
            assertFalse("Not calibrated" in p.text)
        }
        assertEquals("Scale set", present(emptyList(), cal(CalibrationMethod.MANUAL_REFERENCE)).emptyState!!.heading)
    }

    @Test
    fun basisZero_should_DisableKnownDistancesWithTheReason_ButOnlyForAnExistingCalibration() {
        val legacy = present(emptyList(), cal(CalibrationMethod.MANUAL_REFERENCE, CalibrationBasis.LEGACY_DECODE_RELATIVE))
        assertFalse(legacy.knownDistancesEnabled)
        assertEquals(CalibrationStatePresenter.LEGACY_BASIS_REASON, legacy.disabledReason)
        assertEquals("Scale set", legacy.pill.text)

        val notCalibrated = present(emptyList(), cal(CalibrationMethod.NONE, CalibrationBasis.LEGACY_DECODE_RELATIVE))
        assertTrue(notCalibrated.knownDistancesEnabled)
        assertNull(notCalibrated.disabledReason)
    }

    @Test
    fun emptyState_should_ExplainAndOfferBothActions_AndDisappearWithRows() {
        val empty = present(emptyList(), calibration = null).emptyState!!
        assertEquals("Known distances", empty.heading)
        assertTrue(empty.explanation.startsWith("A known distance is a length you know"))
        assertEquals("Draw a known distance", empty.primaryAction)
        assertEquals("Use a card or sheet of paper", empty.secondaryAction)
        assertNull(present(spread.take(1)).emptyState)
    }

    @Test
    fun rows_should_FlagTheWorstOffender_OnlyAboveThreeTimesTheMeanAndThreePercent() {
        val report = FitReport(
            tier = FitTier.SCALE, n = 3, residualMeanPct = 2.0,
            perConstraint = listOf(ConstraintResidual("a", 1.0), ConstraintResidual("b", 6.0), ConstraintResidual("c", 2.9)),
        )
        val rows = present(spread, fit = fit(3, residual = 2.0), report = report).rows
        assertEquals(listOf(false, true, false), rows.map { it.flagged })
        assertEquals("disagrees by 6.0% - re-measure or remove?", rows[1].flagText)
        assertEquals("Distance 1", rows[0].name)

        val below = report.copy(perConstraint = listOf(ConstraintResidual("a", 1.0), ConstraintResidual("b", 2.9), ConstraintResidual("c", 1.0)), residualMeanPct = 0.5)
        assertTrue(present(spread, report = below).rows.none { it.flagged })
    }

    @Test
    fun mixedSurfaces_should_ReplaceRowFlagsWithTheSurfaceHint() {
        val report = FitReport(
            tier = FitTier.SCALE, n = 3, residualMeanPct = 1.0, mixedSurfaces = true,
            perConstraint = listOf(ConstraintResidual("a", 1.0), ConstraintResidual("b", 9.0), ConstraintResidual("c", 1.0)),
        )
        val p = present(spread, report = report)
        assertTrue(p.rows.none { it.flagged })
        assertTrue(CalibrationStatePresenter.MIXED_SURFACES_HINT in p.hints)
    }

    @Test
    fun positionHint_should_ShowOnlyWhenGradientIsEnabledAndMidpointsCluster() {
        val clustered = listOf(seg("a", 0.40, 0.40, 0.45, 0.42), seg("b", 0.50, 0.50, 0.55, 0.52))
        assertTrue(CalibrationStatePresenter.POSITION_HINT in present(clustered, gradient = true).hints)
        assertTrue(present(clustered, gradient = false).hints.isEmpty())
        assertTrue(present(spread, gradient = true).hints.isEmpty())
        assertTrue(present(clustered.take(1), gradient = true).hints.isEmpty())
    }

    @Test
    fun directionHint_should_ShowOnlyWhenPerAxisIsEnabledAndDirectionsAreWithin15Degrees() {
        val parallel = listOf(seg("a", 0.05, 0.1, 0.35, 0.1), seg("b", 0.6, 0.8, 0.9, 0.82))
        assertTrue(CalibrationStatePresenter.DIRECTION_HINT in present(parallel, perAxis = true).hints)
        assertTrue(present(parallel, perAxis = false).hints.isEmpty())
        val varied = listOf(seg("a", 0.05, 0.1, 0.35, 0.1), seg("b", 0.6, 0.2, 0.62, 0.9))
        assertTrue(present(varied, perAxis = true).hints.isEmpty())
    }

    @Test
    fun duplicateSegment_should_BeFoundWithin2Px_InEitherOrder() {
        val a = spread[0]
        val width = 4000.0
        val height = 3000.0
        val eps = 1.5 / width
        fun dup(p1: NormalizedPoint, p2: NormalizedPoint) =
            CalibrationStatePresenter.findDuplicate(spread, p1, p2, width, height)

        assertNotNull(dup(NormalizedPoint(a.p1.x + eps, a.p1.y), a.p2))
        assertEquals(a, dup(a.p2, a.p1))
        assertNull(dup(NormalizedPoint(a.p1.x + 5.0 / width, a.p1.y), a.p2))
    }
}
