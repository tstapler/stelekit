// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.ui.screens.copy.ConflictsOfTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** The conflict review waits for the target graph to open; it must never trap the user there. */
@RunWith(RobolectricTestRunner::class)
class ConflictsOfTargetTest {
    @get:Rule
    val rule = createComposeRule()

    private var retries = 0
    private var closes = 0

    private fun show() = rule.setContent {
        MaterialTheme {
            ConflictsOfTarget(
                openGraph = GraphId("src"),
                target = GraphId("dst"),
                factory = { null },
                onOpenPage = {},
                onRetry = { retries++ },
                onClose = { closes++ },
                openTimeoutMs = 5_000,
            )
        }
    }

    @Test
    fun while_opening_shows_progress_with_a_close_button() {
        show()
        rule.onNodeWithText("Opening graph...").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, closes)
    }

    @Test
    fun after_the_timeout_offers_retry_and_close() {
        show()
        rule.mainClock.advanceTimeBy(5_001)
        rule.onNodeWithText("Couldn't open the graph.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        rule.onNodeWithText("Opening graph...").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, closes)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun escape_closes() {
        show()
        rule.waitForIdle()
        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, closes)
    }
}
