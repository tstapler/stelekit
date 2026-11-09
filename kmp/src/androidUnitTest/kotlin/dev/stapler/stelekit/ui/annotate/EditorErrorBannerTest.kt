// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class EditorErrorBannerTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun banner_should_ShowRetryAndCloseAndKeepMeasurements_When_ThrowableInScope() {
        var retries = 0
        var closes = 0
        rule.setContent { EditorErrorBanner(onRetry = { retries++ }, onClose = { closes++ }) }

        rule.onNodeWithText("Something went wrong. Your placed measurements are kept.").assertExists()
        rule.onNodeWithText("Retry").performClick()
        rule.onNodeWithText("Close editor").performClick()

        assertEquals(1, retries)
        assertEquals(1, closes)
    }
}
