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
        composeRule.waitForIdle()
        composeRule.onNodeWithText("- item").assertExists()
        composeRule.onNodeWithText("Copy").assertIsEnabled().performClick()
        assertEquals(1, copies)

        format = "html"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("<p>raw &amp; source</p>").assertExists()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrFail() = assertDoesNotExist()
}
