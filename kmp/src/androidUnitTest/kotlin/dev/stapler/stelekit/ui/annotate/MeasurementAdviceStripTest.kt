// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.calibration.LensMeasurement
import dev.stapler.stelekit.calibration.MeasurementReliability
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MeasurementAdviceStripTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun nothingToSayRendersNothing() {
        rule.setContent { MeasurementAdviceStrip(MeasurementReliability(0.0, emptyList())) }
        rule.onNodeWithText("Tips for accuracy").assertDoesNotExist()
    }

    @Test
    fun warningsShowWithTheTipsHiddenUntilRequested() {
        val reliability = MeasurementReliability(
            2.4, listOf("Short reference: test"), notes = listOf("Lens correction applied (k = -0.010)."),
            guidance = LensMeasurement.SHOOTING_GUIDANCE,
        )
        rule.setContent { MeasurementAdviceStrip(reliability) }
        rule.onNodeWithText("Short reference: test", substring = true).assertExists()
        rule.onNodeWithText("Lens correction applied", substring = true).assertExists()
        rule.onNodeWithText("lay the ruler beside the object", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Tips for accuracy").performClick()
        rule.onNodeWithText("lay the ruler beside the object", substring = true).assertExists()
    }

    @Test
    fun notesAloneStayHiddenSoThePhotoKeepsItsHeight() {
        rule.setContent { MeasurementAdviceStrip(MeasurementReliability(0.0, emptyList(), notes = listOf("Tilt unknown: x"))) }
        rule.onNodeWithText("Tilt unknown: x").assertDoesNotExist()
        rule.onNodeWithText("Tips for accuracy").assertDoesNotExist()
    }
}
