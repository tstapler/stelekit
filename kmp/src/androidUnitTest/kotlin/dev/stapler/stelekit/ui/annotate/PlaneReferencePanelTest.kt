// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.calibration.PlaneReference
import dev.stapler.stelekit.calibration.PlaneSession
import dev.stapler.stelekit.calibration.ReferenceRectangle
import dev.stapler.stelekit.model.NormalizedPoint
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class PlaneReferencePanelTest {
    @get:Rule
    val rule = createComposeRule()

    private class Recorder {
        val rectangles = mutableListOf<ReferenceRectangle>()
        val edges = mutableListOf<Boolean>()
        var undone = 0
        var applied = 0
        var cancelled = 0

        fun actions() = PlaneReferenceActions(
            onRectangle = { rectangles += it },
            onFirstEdgeLong = { edges += it },
            onUndoCorner = { undone++ },
            onApply = { applied++ },
            onCancel = { cancelled++ },
        )
    }

    private fun show(session: PlaneSession, rec: Recorder) = rule.setContent {
        PlaneReferencePanel(session, PlaneReference.preview(session, 0.0, 4000.0, 3000.0), rec.actions())
    }

    @Test
    fun guidesCornerTappingAndBlocksApplyUntilFourCorners() {
        show(PlaneSession(corners = listOf(NormalizedPoint(0.1, 0.1))), Recorder())
        rule.onNodeWithText("Tap corner 2 of 4.").assertExists()
        rule.onNodeWithText("Apply").assertIsNotEnabled()
        rule.onNodeWithText("tap its four corners", substring = true).assertExists()
    }

    @Test
    fun fourCornersEnableApplyAndControlsReachCallbacks() {
        val corners = listOf(0.2 to 0.2, 0.7 to 0.2, 0.7 to 0.6, 0.2 to 0.6).map { NormalizedPoint(it.first, it.second) }
        val rec = Recorder()
        show(PlaneSession(corners = corners), rec)
        rule.onNodeWithText("Apply").assertIsEnabled().performClick()
        rule.onNodeWithText("Letter").performClick()
        rule.onNodeWithText("short edge").performClick()
        rule.onNodeWithText("Undo corner").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, rec.applied)
        assertEquals(listOf(ReferenceRectangle.LETTER), rec.rectangles)
        assertEquals(listOf(false), rec.edges)
        assertEquals(1, rec.undone)
        assertEquals(1, rec.cancelled)
    }
}
