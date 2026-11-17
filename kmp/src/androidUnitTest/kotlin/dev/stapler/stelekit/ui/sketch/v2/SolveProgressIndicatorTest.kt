// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveState
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Story 4.1.4, Task 4.1.4b (UX-36): the "Solving..." indicator and its Cancel appear only once a solve is slow, as static text.
 * Robolectric; Gradle-only like the other Compose host tests (see BUILD.bazel).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class SolveProgressIndicatorTest {
    @get:Rule
    val rule = createComposeRule()

    private var state by mutableStateOf(SolveState())
    private var cancels = 0

    private fun show() {
        rule.setContent { SolveProgressIndicator(state, onCancel = { cancels++ }) }
        rule.waitForIdle()
    }

    @Test
    fun indicator_should_BeAbsent_UntilTheSolveIsSlow() {
        show()
        rule.onNodeWithTag(SolveProgressTags.INDICATOR).assertDoesNotExist()

        state = SolveState(solving = true, slow = false)
        rule.waitForIdle()
        rule.onNodeWithTag(SolveProgressTags.INDICATOR).assertDoesNotExist()

        state = SolveState(solving = true, slow = true)
        rule.waitForIdle()
        rule.onNodeWithTag(SolveProgressTags.INDICATOR).assertIsDisplayed()
    }

    @Test
    fun slowSolve_should_ShowSolvingTextAndACancelButtonThatCancels() {
        state = SolveState(solving = true, slow = true)
        show()

        rule.onNodeWithText(SketchStrings.Solve.SOLVING).assertIsDisplayed()
        rule.onNodeWithTag(SolveProgressTags.CANCEL).assertHasClickAction().assertTextEquals(SketchStrings.Solve.CANCEL).performClick()

        assertEquals(1, cancels)
    }

    @Test
    fun indicator_should_Disappear_WhenTheSolveEnds() {
        state = SolveState(solving = true, slow = true)
        show()
        rule.onNodeWithTag(SolveProgressTags.INDICATOR).assertIsDisplayed()

        state = SolveState()
        rule.waitForIdle()

        rule.onNodeWithTag(SolveProgressTags.INDICATOR).assertDoesNotExist()
    }

    @Test
    fun cancelChip_should_CarryTheSameLabelForTheActionChipRow() {
        var cancelled = 0
        val chip = SolveMessages.cancelChip { cancelled++ }

        chip.onClick()

        assertEquals(SketchStrings.Solve.CANCEL, chip.label)
        assertEquals(SketchStrings.Solve.CANCEL_NAME, chip.accessibleName)
        assertEquals(1, cancelled)
    }
}
