// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.runtime.settings.SnapPreferences
import dev.stapler.stelekit.sketch.v2.snap.GridLines
import dev.stapler.stelekit.sketch.v2.snap.GridPreset
import dev.stapler.stelekit.sketch.v2.snap.GridSettings
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKind
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKinds
import dev.stapler.stelekit.ui.sketch.v2.SnapChoices
import dev.stapler.stelekit.ui.sketch.v2.guide.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The coarse grid steps, which lines get drawn, and that the Snap menu's choices survive a restart. */
class SnapGridAndPrefsTest {
    // ---- grid presets and the lines drawn ---------------------------------------------------------------------------

    @Test
    fun presets_should_BeSixteenthsOfAnInch_FromAQuarterInchToAFoot() {
        assertEquals(listOf(1.0, 4.0, 16.0, 96.0, 192.0), GridPreset.entries.map { it.stepUnits })
        assertEquals(GridPreset.Sixteenth, GridPreset.Default, "the technical sheet keeps its sixteenth-inch snap until the step is changed")
        assertEquals(GridPreset.Quarter, GridPreset.Sixteenth.next())
        assertEquals(GridPreset.Sixteenth, GridPreset.Foot.next(), "the cycle wraps")
    }

    @Test
    fun coarseGrid_should_SnapToTheStep_AndCarryItsMajorInterval() {
        val foot = GridPreset.Foot.settings(enabled = true)
        assertEquals(192.0, foot.snap(100.0))
        assertEquals(192.0, foot.snap(230.0))
        assertEquals(GridPreset.Foot.majorEvery, foot.majorEvery)
        assertEquals(100.0, GridPreset.Foot.settings(enabled = false).snap(100.0), "an off grid snaps nothing")
    }

    @Test
    fun lines_should_MarkEveryMajorStep() {
        val inch = GridPreset.Quarter.settings(true)
        val lines = GridLines.along(inch, 0.0, 64.0, zoom = 4.0, pxPerDp = 1.0)
        assertEquals((0..16).map { it * 4.0 }, lines.map { it.position })
        assertEquals(listOf(0.0, 16.0, 32.0, 48.0, 64.0), lines.filter { it.major }.map { it.position })
    }

    @Test
    fun lines_should_BeThinned_When_ZoomedOutSoTheyNeverCrowd() {
        val fine = GridPreset.Sixteenth.settings(true)
        val lines = GridLines.along(fine, 0.0, 10_000.0, zoom = 0.1, pxPerDp = 3.0)
        val spacing = lines.zipWithNext { a, b -> (b.position - a.position) * 0.1 }
        assertTrue(spacing.all { it >= GridLines.MIN_SPACING_DP * 3.0 }, "closer than the floor: ${spacing.min()}")
        assertTrue(lines.size <= 10_000.0 * 0.1 / (GridLines.MIN_SPACING_DP * 3.0) + 2, "line count ${lines.size} is not bounded by the screen length")
    }

    @Test
    fun lines_should_NeverExceedTheScreenLengthOverTheSpacingFloor_ForAnyZoomAndStep() {
        for (preset in GridPreset.entries) for (zoom in listOf(0.01, 0.1, 0.5, 1.0, 4.0, 40.0)) {
            val from = -3_000.0
            val to = 3_000.0
            val lines = GridLines.along(preset.settings(true), from, to, zoom, pxPerDp = 2.0)
            assertTrue(lines.size <= ((to - from) * zoom / (GridLines.MIN_SPACING_DP * 2.0)).toInt() + 3, "$preset at $zoom drew ${lines.size}")
        }
    }

    @Test
    fun lines_should_BeEmpty_ForAnOffGrid_OrUnusableView() {
        assertTrue(GridLines.along(GridPreset.Inch.settings(false), 0.0, 100.0, 1.0, 1.0).isEmpty())
        assertTrue(GridLines.along(GridPreset.Inch.settings(true), 0.0, 100.0, 0.0, 1.0).isEmpty())
        assertTrue(GridLines.along(GridPreset.Inch.settings(true), Double.NaN, 100.0, 1.0, 1.0).isEmpty())
        assertTrue(GridLines.along(GridPreset.Inch.settings(true), 100.0, 0.0, 1.0, 1.0).isEmpty())
    }

    @Test
    fun gridSettings_should_KeepItsOldMeaning_When_NoMajorIntervalIsGiven() {
        assertEquals(GridSettings(true, 1.0), GridSettings.technical())
        assertEquals(0, GridSettings.diagram(true).majorEvery)
    }

    // ---- persistence per device ------------------------------------------------------------------------------------

    @Test
    fun preferences_should_DefaultEveryKindOn_AndTheGridToTheSixteenth() {
        val prefs = SnapPreferences(MapSettings())
        assertEquals(PointSnapKinds.All, prefs.kinds)
        assertTrue(prefs.alignment)
        assertEquals(GridPreset.Default, prefs.gridPreset)
    }

    @Test
    fun choices_should_SurviveARestart_ForEveryKindTheAlignmentAndTheGridStep() {
        val store = MapSettings()
        val first = SnapChoices(SnapPreferences(store))
        first.toggle(PointSnapKind.Tangent)
        first.toggle(PointSnapKind.Midpoint)
        first.toggleAlignment()
        first.cycleGrid()
        first.cycleGrid()
        val second = SnapChoices(SnapPreferences(store))
        assertFalse(PointSnapKind.Tangent in second.kinds)
        assertFalse(PointSnapKind.Midpoint in second.kinds)
        assertTrue(PointSnapKind.Endpoint in second.kinds)
        assertFalse(second.alignment)
        assertEquals(GridPreset.Inch, second.gridPreset)
        second.toggle(PointSnapKind.Tangent)
        assertTrue(PointSnapKind.Tangent in SnapChoices(SnapPreferences(store)).kinds, "turning a kind back on is stored too")
    }

    @Test
    fun choices_should_WorkForTheSession_When_TheHostStoresNothing() {
        val choices = SnapChoices(null)
        assertEquals(PointSnapKinds.All, choices.kinds)
        choices.toggle(PointSnapKind.Centre)
        assertFalse(PointSnapKind.Centre in choices.kinds)
    }
}
