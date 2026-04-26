// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.stapler.stelekit.ui.components.VoiceCaptureButton
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.voice.PipelineStage
import dev.stapler.stelekit.voice.VoiceCaptureState
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot tests for each VoiceCaptureButton state.
 *
 * To record new golden images run:
 *   ./gradlew jvmTest -Proborazzi.test.record=true
 */
class VoiceCaptureButtonScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(state: VoiceCaptureState, themeMode: StelekitThemeMode = StelekitThemeMode.LIGHT) {
        composeTestRule.setContent {
            StelekitTheme(themeMode = themeMode) {
                VoiceCaptureButton(
                    state = state,
                    onTap = {},
                    onDismissError = {},
                    onAutoReset = {},
                )
            }
        }
    }

    @Test
    fun voiceCaptureButton_idle_light() {
        render(VoiceCaptureState.Idle)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/voice_button_idle_light.png")
    }

    @Test
    fun voiceCaptureButton_recording_light() {
        render(VoiceCaptureState.Recording)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/voice_button_recording_light.png")
    }

    @Test
    fun voiceCaptureButton_transcribing_light() {
        render(VoiceCaptureState.Transcribing)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/voice_button_transcribing_light.png")
    }

    @Test
    fun voiceCaptureButton_formatting_light() {
        render(VoiceCaptureState.Formatting)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/voice_button_formatting_light.png")
    }

    @Test
    fun voiceCaptureButton_done_light() {
        render(VoiceCaptureState.Done(insertedText = "- Test note", isLikelyTruncated = false))
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/voice_button_done_light.png")
    }

    @Test
    fun voiceCaptureButton_done_truncated_light() {
        render(VoiceCaptureState.Done(insertedText = "- Test note", isLikelyTruncated = true))
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/voice_button_done_truncated_light.png")
    }

    @Test
    fun voiceCaptureButton_error_light() {
        render(VoiceCaptureState.Error(PipelineStage.TRANSCRIBING, "Network error — check your connection"))
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/voice_button_error_light.png")
    }

    @Test
    fun voiceCaptureButton_idle_dark() {
        render(VoiceCaptureState.Idle, StelekitThemeMode.DARK)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/voice_button_idle_dark.png")
    }

    @Test
    fun voiceCaptureButton_error_dark() {
        render(VoiceCaptureState.Error(PipelineStage.TRANSCRIBING, "Network error — check your connection"), StelekitThemeMode.DARK)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/voice_button_error_dark.png")
    }
}
