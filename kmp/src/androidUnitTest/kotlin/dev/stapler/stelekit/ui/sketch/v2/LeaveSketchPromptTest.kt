// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveFailure
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 1.4.4 (UX-72): leaving a sheet with unsaved or failed changes asks first. Robolectric, so no display is needed.
 * Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class LeaveSketchPromptTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show() {
        rule.setContent {
            LeaveSketchPrompt(
                onRetry = { calls += "retry" },
                onExportCopy = { calls += "export" },
                onDiscard = { calls += "discard" },
                onKeepEditing = { calls += "keep" },
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun prompt_should_OfferRetryExportAndDiscard() {
        show()

        rule.onNodeWithTag(LEAVE_SKETCH_RETRY_TAG).assertIsDisplayed()
        rule.onNodeWithTag(LEAVE_SKETCH_EXPORT_TAG).assertIsDisplayed()
        rule.onNodeWithTag(LEAVE_SKETCH_DISCARD_TAG).assertIsDisplayed()
    }

    @Test
    fun retry_should_BeTheDefaultAndFocusedFirst() {
        rule.setContent { LeaveSketchActions(onRetry = { calls += "retry" }, onExportCopy = { calls += "export" }, onDiscard = { calls += "discard" }) }
        rule.waitForIdle()

        rule.onNodeWithTag(LEAVE_SKETCH_RETRY_TAG).assertIsFocused()
        assertTrue(calls.isEmpty(), "showing the prompt must not act")
    }

    @Test
    fun eachAction_should_CallOnlyItsOwnCallback() {
        show()

        rule.onNodeWithTag(LEAVE_SKETCH_EXPORT_TAG).performClick()
        rule.onNodeWithTag(LEAVE_SKETCH_DISCARD_TAG).performClick()
        rule.onNodeWithTag(LEAVE_SKETCH_RETRY_TAG).performClick()

        assertEquals(listOf("export", "discard", "retry"), calls)
    }

    @Test
    fun leaving_should_AskOnlyWhileWorkIsNotOnDisk() {
        assertFalse(leaveNeedsPrompt(SaveState.Saved))
        assertTrue(leaveNeedsPrompt(SaveState.Pending))
        assertTrue(leaveNeedsPrompt(SaveState.Saving))
        assertTrue(leaveNeedsPrompt(SaveState.Failed(SaveFailure.Write)))
        assertTrue(leaveNeedsPrompt(SaveState.Conflict("conflicts/s2.v2.conflict-aaaaaaaa.json")))
    }
}
