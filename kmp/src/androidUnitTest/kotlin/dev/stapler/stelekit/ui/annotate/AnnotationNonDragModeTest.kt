// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.ui.placement.NudgeDirection
import dev.stapler.stelekit.ui.placement.NudgeStep
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AnnotationNonDragModeTest {
    @get:Rule
    val rule = createComposeRule()

    private val space = AnnotationPlacementSpace(1000, 1000)

    @Test
    fun padIsHiddenUntilToggledThenNudgesConfirmsAndCancels() {
        var session by mutableStateOf(AnnotationPlacementSession())
        var commits = 0
        rule.setContent {
            AnnotationPlacementControls(
                session = session,
                onToggle = { session = session.toggled(space) },
                onNudge = { d, s -> session = session.nudged(space, d, s) },
                onConfirm = { commits++ },
                onCancel = { session = AnnotationPlacementSession() },
            )
        }
        rule.onNodeWithText("Place point").assertDoesNotExist()
        rule.onNodeWithContentDescription("Place at crosshair mode").performClick()
        rule.onNodeWithContentDescription("Nudge right, 10 pixels step").performClick()
        assertEquals(NormalizedPoint(0.51, 0.5), session.state.let { (it as dev.stapler.stelekit.ui.placement.PlacementState.Crosshair).point })
        rule.onNodeWithContentDescription("Nudge step 1 pixel").performClick()
        rule.onNodeWithContentDescription("Nudge up, 1 pixel step").performClick()
        rule.onNodeWithText("Place point").performClick()
        assertEquals(1, commits)
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Place point").assertDoesNotExist()
    }

    @Test
    fun moveSessionShowsApplyAndCrosshairIsDescribedForTalkBack() {
        val moving = AnnotationPlacementSession().startMove(space, VertexTarget("a", 0), NormalizedPoint(0.25, 0.75))
        rule.setContent { AnnotationPlacementControls(moving, {}, { _: NudgeDirection, _: NudgeStep -> }, {}, {}) }
        rule.onNodeWithText("Apply").assertExists()
        rule.onNodeWithText("Cancel move").assertExists()
        assertEquals("Crosshair at 25 percent across, 75 percent down", crosshairDescription(NormalizedPoint(0.25, 0.75)))
    }
}
