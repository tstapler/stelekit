// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import arrow.core.right
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SharePreviewPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsLoadingThenTextThenRecomputesOnKeyChange_andCopyFires() {
        val gate = CompletableDeferred<Unit>()
        var format by mutableStateOf("markdown")
        var copies = 0
        composeRule.setContent {
            MaterialTheme {
                SharePreviewPane(
                    previewKey = format,
                    compute = {
                        if (format == "markdown") gate.await()
                        (if (format == "html") "<p>raw &amp; source</p>" else "- item").right()
                    },
                    onCopy = { copies++ },
                )
            }
        }
        composeRule.onNodeWithText("Copy").assertIsNotEnabled()
        composeRule.onNodeWithText("- item").assertDoesNotExistOrFail()

        gate.complete(Unit)
        waitForText("- item")
        composeRule.onNodeWithText("Copy").assertIsEnabled().performClick()
        assertEquals(1, copies)

        format = "html"
        waitForText("<p>raw &amp; source</p>")
    }

    // compute runs on a background dispatcher, so idle-waiting alone can return before it finishes.
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun waitForText(text: String) {
        composeRule.waitUntilAtLeastOneExists(androidx.compose.ui.test.hasText(text), 5_000)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrFail() = assertDoesNotExist()

    @Test
    fun largeExport_computeRunsOffTheMainThread_andShowsLoadingFirst() {
        val gate = CompletableDeferred<Unit>()
        var computeThread: Thread? = null
        val mainThread = Thread.currentThread()
        val bigText = (1..600).joinToString("\n") { "- block $it" }
        composeRule.setContent {
            MaterialTheme {
                SharePreviewPane(
                    previewKey = "big",
                    compute = {
                        computeThread = Thread.currentThread()
                        gate.await()
                        bigText.right()
                    },
                    onCopy = {},
                )
            }
        }
        composeRule.onNodeWithText("Copy").assertIsNotEnabled() // loading while the 600-block export is pending
        composeRule.onNodeWithText("Copy").assertExists()
        gate.complete(Unit)
        composeRule.waitUntil(5_000) { computeThread != null }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Copy").assertIsEnabled()
        assertEquals(true, computeThread !== mainThread, "compute ran on the main thread")
        composeRule.onNodeWithText("- block 600", substring = true).assertExists()
    }
}
