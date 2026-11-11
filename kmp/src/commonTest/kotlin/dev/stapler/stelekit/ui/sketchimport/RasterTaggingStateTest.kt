// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketchimport

import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.sketchimport.PngSize
import dev.stapler.stelekit.sketchimport.RasterInputError
import dev.stapler.stelekit.ui.placement.NudgeDirection
import dev.stapler.stelekit.ui.placement.NudgeStep
import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RasterTaggingStateTest {
    private val registry = DimensionRegistry(
        dimensions = listOf(
            DimensionEntry("closet.width", DimensionKind.LENGTH, "Closet width"),
            DimensionEntry("rod.height", DimensionKind.LENGTH, "Rod height"),
            DimensionEntry("rod.count", DimensionKind.COUNT, "Rods"),
        ),
    )
    private val fresh = RasterState(PngSize(1000, 500)).toggled()

    private fun RasterState.at(x: Double, y: Double) = tapped(NormalizedPoint(x, y)).placed()

    private fun twoPoints(s: RasterState, x2: Double) = s.at(0.1, 0.5).at(x2, 0.5)

    private fun calibrated(): RasterState = twoPoints(fresh, 0.6).typed("100\"").calibrated()

    @Test
    fun toggled_should_ShowAndHideTheCrosshair() {
        assertTrue(fresh.placement.active)
        assertFalse(fresh.toggled().placement.active)
    }

    @Test
    fun placed_should_AddPointsOnlyOnConfirm_AndStartANewLineOnTheThird() {
        val moved = fresh.tapped(NormalizedPoint(0.3, 0.3))
        assertTrue(moved.points.isEmpty())
        val two = twoPoints(fresh, 0.6)
        assertEquals(2, two.points.size)
        assertNotNull(two.line)
        assertEquals(listOf(NormalizedPoint(0.9, 0.5)), two.at(0.9, 0.5).points)
    }

    @Test
    fun placed_should_DoNothing_WhenTheCrosshairIsHidden() {
        assertTrue(fresh.toggled().placed().points.isEmpty())
    }

    @Test
    fun nudged_should_MoveTheCrosshairByTheStep() {
        val after = fresh.nudged(NudgeDirection.RIGHT, NudgeStep.TEN_PIXELS).placed()
        assertEquals(0.51, after.points.single().x, 1e-9)
    }

    @Test
    fun respaced_should_KeepThePoint() {
        assertEquals(fresh.placement.state, fresh.respaced().placement.state)
    }

    @Test
    fun calibrated_should_SetScaleAndReference_AndClearPoints() {
        val s = calibrated()
        assertTrue(s.isCalibrated)
        assertTrue(s.points.isEmpty())
        assertEquals(Rational(100), s.reference?.length)
        assertNotNull(s.session)
    }

    @Test
    fun calibrated_should_ReportErrors_WithoutChangingTheScale() {
        assertEquals(RasterInputError.PointsTooClose, fresh.calibrated().error)
        assertEquals(RasterInputError.NotALength, twoPoints(fresh, 0.6).typed("nope").calibrated().error)
        val close = fresh.at(0.1, 0.5).at(0.1001, 0.5).typed("10\"").calibrated()
        assertEquals(RasterInputError.PointsTooClose, close.error)
        assertFalse(close.isCalibrated)
    }

    @Test
    fun canCalibrate_should_NeedTwoPointsAndText() {
        assertFalse(fresh.canCalibrate)
        assertFalse(twoPoints(fresh, 0.6).canCalibrate)
        assertTrue(twoPoints(fresh, 0.6).typed("5\"").canCalibrate)
        assertFalse(calibrated().canCalibrate)
    }

    @Test
    fun measuredText_should_ShowTheLineLengthOnceCalibrated() {
        assertNull(fresh.measuredText)
        assertNull(calibrated().measuredText)
        assertEquals("4' 2\"", twoPoints(calibrated(), 0.35).measuredText)
    }

    @Test
    fun tagged_should_BindTheMeasuredLine_AndReplaceAnEarlierTagOfTheKey() {
        val first = twoPoints(calibrated(), 0.35).tagged(registry, "closet.width").getOrNull()!!
        assertEquals(Rational(50), first.tags.single().length)
        assertTrue(first.points.isEmpty())
        val second = twoPoints(first, 0.2).tagged(registry, "closet.width").getOrNull()!!
        assertEquals(Rational(20), second.tags.single().length)
    }

    @Test
    fun tagged_should_Refuse_ACountKeyAnUncalibratedSheetAndNoLine() {
        val line = twoPoints(calibrated(), 0.35)
        assertEquals(RasterInputError.NotALengthKey("rod.count"), line.tagged(registry, "rod.count").leftOrNull())
        assertEquals(RasterInputError.NotCalibrated, twoPoints(fresh, 0.35).tagged(registry, "closet.width").leftOrNull())
        assertEquals(RasterInputError.PointsTooClose, calibrated().tagged(registry, "closet.width").leftOrNull())
    }

    @Test
    fun referenceTagged_should_KeepTheExactTypedLength() {
        val s = calibrated().referenceTagged(registry, "closet.width").getOrNull()!!
        val tag = s.tags.single()
        assertEquals(Rational(100), tag.length)
        assertFalse(tag.measured)
        assertEquals(RasterInputError.NotALengthKey("rod.count"), calibrated().referenceTagged(registry, "rod.count").leftOrNull())
        assertEquals(RasterInputError.NotCalibrated, fresh.referenceTagged(registry, "closet.width").leftOrNull())
    }

    @Test
    fun untagged_recalibrating_clearedLine_and_failed_should_EditTheState() {
        val tagged = twoPoints(calibrated(), 0.35).tagged(registry, "rod.height").getOrNull()!!
        assertTrue(tagged.untagged("rod.height").tags.isEmpty())
        assertFalse(tagged.recalibrating().isCalibrated)
        assertTrue(tagged.recalibrating().tags.isEmpty())
        assertTrue(twoPoints(fresh, 0.6).clearedLine().points.isEmpty())
        assertEquals(RasterInputError.NotCalibrated, fresh.failed(RasterInputError.NotCalibrated).error)
    }
}
