// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.components.VOICE_CAPTURE_UNSUPPORTED_DESCRIPTION
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

    // GAP-002 (project_plans/rich-editing-experience/implementation/gap-backlog.md): on platforms
    // with no real AudioRecorder, the Idle button must render as disabled with a clear
    // "not available" affordance instead of a fully-interactive-looking control that silently
    // records nothing. Semantics-only assertions (no golden image) — this is a behavior/state
    // check, not a visual regression check.
    @Test
    fun voiceCaptureButton_idle_unsupported_isDisabled() {
        var tapCount = 0
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                VoiceCaptureButton(
                    state = VoiceCaptureState.Idle,
                    onTap = { tapCount++ },
                    onDismissError = {},
                    onAutoReset = {},
                    isSupported = false,
                )
            }
        }
        composeTestRule.waitForIdle()

        val node = composeTestRule.onNodeWithContentDescription(VOICE_CAPTURE_UNSUPPORTED_DESCRIPTION)
        node.assertIsNotEnabled()
        node.performClick()
        composeTestRule.waitForIdle()

        assert(tapCount == 0) { "onTap must not fire when voice capture is unsupported" }
    }

    @Test
    fun voiceCaptureButton_idle_supported_isEnabledByDefault() {
        var tapCount = 0
        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                VoiceCaptureButton(
                    state = VoiceCaptureState.Idle,
                    onTap = { tapCount++ },
                    onDismissError = {},
                    onAutoReset = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Start recording").performClick()
        composeTestRule.waitForIdle()

        assert(tapCount == 1) { "onTap must fire normally when isSupported defaults to true" }
    }
}
