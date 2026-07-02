// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LlmSettingsTest {

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    @Test
    fun `getSelectedProviderId_should_RoundTrip_When_SetThenGet`() {
        val settings = LlmSettings(MapSettings())
        settings.setSelectedProviderId(LlmFeature.VOICE_FORMATTING, "anthropic")
        assertEquals("anthropic", settings.getSelectedProviderId(LlmFeature.VOICE_FORMATTING))
    }

    @Test
    fun `getSelectedProviderId_should_ReturnNull_When_Unset`() {
        val settings = LlmSettings(MapSettings())
        assertNull(settings.getSelectedProviderId(LlmFeature.TAG_SUGGESTION))
    }

    @Test
    fun `getSelectedProviderId_should_ReturnNull_When_ExplicitlySetToNull_MeaningAuto`() {
        val settings = LlmSettings(MapSettings())
        settings.setSelectedProviderId(LlmFeature.TAG_SUGGESTION, "openai")
        settings.setSelectedProviderId(LlmFeature.TAG_SUGGESTION, null)
        assertNull(settings.getSelectedProviderId(LlmFeature.TAG_SUGGESTION))
    }

    @Test
    fun `selection_should_BeIndependent_AcrossThreeLlmFeatureValues`() {
        val settings = LlmSettings(MapSettings())
        settings.setSelectedProviderId(LlmFeature.VOICE_FORMATTING, "anthropic")
        settings.setSelectedProviderId(LlmFeature.TAG_SUGGESTION, "android-ondevice")
        // GRAPH_EDIT_SYNTHESIS left unset.

        assertEquals("anthropic", settings.getSelectedProviderId(LlmFeature.VOICE_FORMATTING))
        assertEquals("android-ondevice", settings.getSelectedProviderId(LlmFeature.TAG_SUGGESTION))
        assertNull(settings.getSelectedProviderId(LlmFeature.GRAPH_EDIT_SYNTHESIS))
    }
}
