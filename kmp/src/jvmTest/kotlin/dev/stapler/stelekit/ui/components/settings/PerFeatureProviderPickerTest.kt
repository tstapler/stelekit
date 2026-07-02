// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmProvider
import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.llm.LlmProviderKind
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import org.junit.Rule
import org.junit.Test

class PerFeatureProviderPickerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeProvider(
        override val id: String,
        override val displayName: String,
        override val kind: LlmProviderKind = LlmProviderKind.REMOTE,
        override val supportsLongFormOutput: Boolean = true,
    ) : LlmProvider {
        override val formatter: LlmFormatterProvider = LlmFormatterProvider { _, _ -> LlmResult.Success("unused") }
        override suspend fun checkAvailability(): LlmProviderAvailability = LlmProviderAvailability.Available
    }

    @Test
    fun picker_should_ShowAutoOption_And_ExcludeShortFormOnly_When_FeatureIsSynthesis() {
        val remote = FakeProvider("anthropic", "Anthropic Claude")
        val onDeviceShortForm = FakeProvider(
            "android-ondevice", "Android On-Device",
            kind = LlmProviderKind.ON_DEVICE, supportsLongFormOutput = false,
        )
        val registry = LlmProviderRegistry(listOf(remote, onDeviceShortForm))
        val llmSettings = LlmSettings(InMemorySettings())

        composeTestRule.setContent {
            MaterialTheme {
                PerFeatureProviderPicker(
                    feature = LlmFeature.GRAPH_EDIT_SYNTHESIS,
                    registry = registry,
                    llmSettings = llmSettings,
                )
            }
        }
        composeTestRule.waitForIdle()

        // Default selection is "Auto".
        composeTestRule.onNodeWithText("Auto (recommended)").assertExists()

        composeTestRule.onNodeWithText("Auto (recommended)").performClick()
        composeTestRule.waitForIdle()

        // Dropdown must include the long-form remote provider...
        composeTestRule.onNodeWithText("Anthropic Claude").assertExists()
        // ...but must exclude the short-form-only on-device provider for synthesis.
        composeTestRule.onNodeWithText("Android On-Device").assertDoesNotExist()
    }

    @Test
    fun picker_should_IncludeShortFormOnlyProvider_When_FeatureIsNotSynthesis() {
        val onDeviceShortForm = FakeProvider(
            "android-ondevice", "Android On-Device",
            kind = LlmProviderKind.ON_DEVICE, supportsLongFormOutput = false,
        )
        val registry = LlmProviderRegistry(listOf(onDeviceShortForm))
        val llmSettings = LlmSettings(InMemorySettings())

        composeTestRule.setContent {
            MaterialTheme {
                PerFeatureProviderPicker(
                    feature = LlmFeature.TAG_SUGGESTION,
                    registry = registry,
                    llmSettings = llmSettings,
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Auto (recommended)").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Android On-Device").assertExists()
    }

    @Test
    fun selectingProvider_should_PersistViaLlmSettings() {
        val remote = FakeProvider("anthropic", "Anthropic Claude")
        val registry = LlmProviderRegistry(listOf(remote))
        val llmSettings = LlmSettings(InMemorySettings())

        composeTestRule.setContent {
            MaterialTheme {
                PerFeatureProviderPicker(
                    feature = LlmFeature.VOICE_FORMATTING,
                    registry = registry,
                    llmSettings = llmSettings,
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Auto (recommended)").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Anthropic Claude").performClick()
        composeTestRule.waitForIdle()

        assert(llmSettings.getSelectedProviderId(LlmFeature.VOICE_FORMATTING) == "anthropic")
    }
}
