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
class CalibrationNudgeBannerTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun firstUse_showsFullBanner_andCalibrateNowInvokesCallback() {
        var clicks = 0
        rule.setContent { CalibrationNudgeBanner(isFirstUse = true, onCalibrateClick = { clicks++ }) }
        rule.onNodeWithText("Set a scale to measure distances").assertExists()
        rule.onNodeWithText("Tap a known length on the photo to calibrate.").assertExists()
        rule.onNodeWithText("tap to calibrate").assertDoesNotExist()
        rule.onNodeWithText("Calibrate Now").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun notFirstUse_showsCompactBanner_andTapToCalibrateInvokesCallback() {
        var clicks = 0
        rule.setContent { CalibrationNudgeBanner(isFirstUse = false, onCalibrateClick = { clicks++ }) }
        rule.onNodeWithText("Scale not set — ").assertExists()
        rule.onNodeWithText("Calibrate Now").assertDoesNotExist()
        rule.onNodeWithText("tap to calibrate").performClick()
        assertEquals(1, clicks)
    }
}
