// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.settings

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
import dev.stapler.stelekit.llm.ProjectAnalysisAcknowledgement
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.components.settings.PerFeatureProviderPicker
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProjectAnalysisPickerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    private class FakeProvider(override val id: String, override val displayName: String) : LlmProvider {
        override val kind = LlmProviderKind.REMOTE
        override val supportsLongFormOutput = true
        override val formatter = LlmFormatterProvider { _, _ -> LlmResult.Success("unused") }
        override suspend fun checkAvailability(): LlmProviderAvailability = LlmProviderAvailability.Available
    }

    private val registry = LlmProviderRegistry(listOf(FakeProvider("anthropic", "Anthropic Claude")))

    private fun show(settings: LlmSettings) = composeTestRule.setContent {
        MaterialTheme {
            PerFeatureProviderPicker(LlmFeature.PROJECT_ANALYSIS, registry, settings)
        }
    }

    @Test
    fun picker_should_ShowNotConfiguredAndNoAutoItem_When_Unset() {
        val settings = LlmSettings(MapSettings())
        show(settings)
        composeTestRule.onNodeWithText("Not configured").assertExists()
        composeTestRule.onNodeWithText("Not configured").performClick()
        composeTestRule.onNodeWithText("Auto (recommended)").assertDoesNotExist()
        assertNull(settings.resolveExplicitProvider(LlmFeature.PROJECT_ANALYSIS))
    }

    @Test
    fun picker_should_PersistOnlyAfterAcknowledgement_When_ProviderChosen() {
        val settings = LlmSettings(MapSettings())
        show(settings)
        composeTestRule.onNodeWithText("Not configured").performClick()
        composeTestRule.onNodeWithText("Anthropic Claude").performClick()
        composeTestRule.onNodeWithText(ProjectAnalysisAcknowledgement.TITLE).assertExists()
        assertNull(settings.getSelectedProviderId(LlmFeature.PROJECT_ANALYSIS))

        composeTestRule.onNodeWithText("I understand").performClick()
        assertEquals("anthropic", settings.resolveExplicitProvider(LlmFeature.PROJECT_ANALYSIS))
    }

    @Test
    fun picker_should_LeaveUnset_When_AcknowledgementCancelled() {
        val settings = LlmSettings(MapSettings())
        show(settings)
        composeTestRule.onNodeWithText("Not configured").performClick()
        composeTestRule.onNodeWithText("Anthropic Claude").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertNull(settings.getSelectedProviderId(LlmFeature.PROJECT_ANALYSIS))
    }
}
