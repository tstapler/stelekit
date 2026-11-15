// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationBasis
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ConstraintFitRecord
import dev.stapler.stelekit.model.FitTier
import dev.stapler.stelekit.model.GeometryCalibration
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.model.PlaneCalibration
import dev.stapler.stelekit.model.PriorBasis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationHistoryTest {
    private fun constraint(id: String) =
        CalibrationConstraint(id, NormalizedPoint(0.1, 0.1), NormalizedPoint(0.6, 0.2), 0.9)

    private val paper = PlaneCalibration(List(9) { 1.0 }, "A4", 0.297, 0.21)
    private val prior = PriorBasis(CalibrationMethod.MANUAL_REFERENCE, 1500.0, 100, plane = paper)

    private fun calibration(constraints: List<CalibrationConstraint>) = Calibration(
        method = CalibrationMethod.MANUAL_REFERENCE,
        pixelsPerMeter = 1800.0,
        confidencePercent = 90,
        basisVersion = CalibrationBasis.NATIVE_PIXELS,
        geometry = GeometryCalibration(
            constraints = constraints,
            fit = ConstraintFitRecord(FitTier.SCALE, constraints.size, 1.0, priorBasis = prior),
        ),
    )

    @Test
    fun undo_should_RestoreConstraintsFitAndPriorBasis() {
        val three = calibration(listOf(constraint("a"), constraint("b"), constraint("c")))
        val history = CalibrationHistory.empty().push(CalibrationHistory.Entry(three, emptyList()))
        assertTrue(history.canUndo)

        val (entry, rest) = assertNotNull(history.pop())
        assertEquals(listOf("a", "b", "c"), entry.constraints.map { it.id })
        assertEquals(3, entry.fit?.n)
        assertEquals(prior, entry.priorBasis)
        assertEquals(paper, entry.priorBasis?.plane)
        assertFalse(rest.canUndo)
    }

    @Test
    fun history_should_KeepOnlyTheNewestEntriesUpToItsDepth() {
        val entries = listOf("a", "b", "c").map { CalibrationHistory.Entry(calibration(listOf(constraint(it))), emptyList()) }
        val single = entries.fold(CalibrationHistory.empty()) { h, e -> h.push(e) }
        assertEquals("c", single.pop()!!.first.constraints.single().id)
        assertFalse(single.pop()!!.second.canUndo)

        val deep = entries.fold(CalibrationHistory.empty(2)) { h, e -> h.push(e) }
        val (newest, rest) = deep.pop()!!
        assertEquals("c", newest.constraints.single().id)
        assertEquals("b", rest.pop()!!.first.constraints.single().id)
    }

    @Test
    fun emptyHistory_should_HaveNothingToUndo_AndPlainCalibrationsHaveNoFit() {
        assertNull(CalibrationHistory.empty().pop())
        val plain = CalibrationHistory.Entry(Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100), emptyList())
        assertTrue(plain.constraints.isEmpty())
        assertNull(plain.fit)
        assertNull(plain.priorBasis)
    }
}
