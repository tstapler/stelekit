// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.voice.VoiceSettings
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test

/**
 * Epic 6 Story 6.6: [VoiceCaptureSettings] no longer reads/writes the plaintext Anthropic/
 * OpenAI key fields — those are retired in favor of the unified provider hub (Story 6.2/6.3).
 * A redirect note replaces the removed fields so the screen isn't a dead end.
 */
class VoiceCaptureSettingsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun screen_should_ShowRedirectNote_When_CredentialFieldsRemoved() {
        val voiceSettings = VoiceSettings(InMemorySettings())

        composeTestRule.setContent {
            MaterialTheme {
                VoiceCaptureSettings(
                    voiceSettings = voiceSettings,
                    onRebuildPipeline = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Configure AI provider keys in Settings → AI Providers.").assertExists()
        // The retired plaintext credential fields must not be present.
        composeTestRule.onNodeWithText("Anthropic (Claude) API key").assertDoesNotExist()
        composeTestRule.onNodeWithText("OpenAI / compatible API key").assertDoesNotExist()
    }

    @Test
    fun redirectNote_should_InvokeCallback_When_OpenTapped() {
        val voiceSettings = VoiceSettings(InMemorySettings())
        var navigated = false

        composeTestRule.setContent {
            MaterialTheme {
                VoiceCaptureSettings(
                    voiceSettings = voiceSettings,
                    onRebuildPipeline = {},
                    onNavigateToAiProviders = { navigated = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Open").performClick()
        assert(navigated) { "expected onNavigateToAiProviders to be invoked" }
    }

    @Test
    fun whisperKeyField_should_StillBePresent_UnaffectedByStory66() {
        val voiceSettings = VoiceSettings(InMemorySettings())

        composeTestRule.setContent {
            MaterialTheme {
                VoiceCaptureSettings(
                    voiceSettings = voiceSettings,
                    onRebuildPipeline = {},
                )
            }
        }

        composeTestRule.onNodeWithText("OpenAI / Whisper API key").assertExists()
    }

    /**
     * UX Acceptance Test (validation.md): "No dead ends — retiring VoiceCaptureSettings
     * credential fields leaves a redirect note." To record:
     * `./gradlew jvmTest -Proborazzi.test.record=true`
     */
    @Test
    fun screen_should_RenderCorrectly_WithoutRemovedFields() {
        val voiceSettings = VoiceSettings(InMemorySettings())

        composeTestRule.setContent {
            StelekitTheme(themeMode = StelekitThemeMode.LIGHT) {
                androidx.compose.foundation.layout.Column {
                    VoiceCaptureSettings(voiceSettings = voiceSettings, onRebuildPipeline = {})
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/voice_capture_settings_no_plaintext_keys.png")
    }
}
