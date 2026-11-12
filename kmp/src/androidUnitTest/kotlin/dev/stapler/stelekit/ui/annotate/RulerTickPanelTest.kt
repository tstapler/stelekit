// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.stapler.stelekit.calibration.LensMode
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class RulerTickPanelTest {
    @get:Rule
    val rule = createComposeRule()

    private val session = RulerTickSession(
        unit = MeasurementUnit.CENTIMETERS,
        entries = listOf(
            RulerTickEntry(NormalizedPoint(0.1, 0.5), "0"),
            RulerTickEntry(NormalizedPoint(0.2, 0.5), "10"),
        ),
    )

    private class Recorder {
        val values = mutableListOf<Pair<Int, String>>()
        val removed = mutableListOf<Int>()
        val lens = mutableListOf<LensMode>()
        var applied = 0
        var cancelled = 0

        fun actions() = RulerTickPanelActions(
            onValueChange = { i, t -> values += i to t },
            onRemove = { removed += it },
            onUnitChange = {},
            onLensModeChange = { lens += it },
            onApply = { applied++ },
            onCancel = { cancelled++ },
        )
    }

    private fun show(session: RulerTickSession, rec: Recorder) = rule.setContent {
        RulerTickPanel(session, session.preview(4000.0, 3000.0), rec.actions())
    }

    @Test
    fun readyTicksEnableApplyAndShowTheFit() {
        val rec = Recorder()
        show(session, rec)
        rule.onNodeWithText("Apply calibration").assertIsEnabled().performClick()
        assertEquals(1, rec.applied)
        rule.onNodeWithText("2 ticks", substring = true).assertExists()
    }

    @Test
    fun tooFewTicksDisableApply() {
        show(session.copy(entries = session.entries.take(1)), Recorder())
        rule.onNodeWithText("Apply calibration").assertIsNotEnabled()
        rule.onNodeWithText("at least 2", substring = true).assertExists()
    }

    @Test
    fun editingRemovingLensAndCancelReachTheCallbacks() {
        val rec = Recorder()
        show(session, rec)
        rule.onAllNodesWithText("Position (cm)")[1].performTextReplacement("20")
        assertEquals(1 to "20", rec.values.last())
        rule.onNodeWithContentDescription("Remove tick 1").performClick()
        assertEquals(listOf(0), rec.removed)
        rule.onNodeWithText("Off").performClick()
        assertEquals(LensMode.Off, rec.lens.single())
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, rec.cancelled)
    }

    @Test
    fun eachTickShowsHowFarItIsOff() {
        val preview = session.preview(4000.0, 3000.0).copy(entryResidualsPx = listOf(0.4, 12.3))
        rule.setContent { RulerTickPanel(session, preview, Recorder().actions()) }
        rule.onNodeWithText("off by 0.4 px").assertExists()
        rule.onNodeWithText("off by 12 px").assertExists()
    }
}
