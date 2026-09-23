// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.voice.VoiceSettings
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test

/**
 * Roborazzi screenshot test — split out of [VoiceCaptureSettingsTest] because it has no Bazel
 * integration (kept in Gradle only, see `kmp/src/jvmTest/kotlin/BUILD.bazel`'s
 * `*ScreenshotTest.kt` exclusion glob).
 */
class VoiceCaptureSettingsScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
                Column {
                    VoiceCaptureSettings(voiceSettings = voiceSettings, onRebuildPipeline = {})
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/voice_capture_settings_no_plaintext_keys.png")
    }
}
