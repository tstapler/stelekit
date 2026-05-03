// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import dev.stapler.stelekit.platform.Settings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceSettingsTest {

    // In-memory Settings for testing — stores actual values unlike the read-only FakeSettings.
    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
    }

    // --- useDeviceStt ---

    @Test
    fun `getUseDeviceStt is true by default so on-device STT is used without configuration`() {
        // Regression: was false — caused Whisper API-key error on every fresh Android install.
        val settings = VoiceSettings(MapSettings())
        assertTrue(settings.getUseDeviceStt())
    }

    @Test
    fun `getUseDeviceStt persists an explicit false after setUseDeviceStt`() {
        val settings = VoiceSettings(MapSettings())
        settings.setUseDeviceStt(false)
        assertFalse(settings.getUseDeviceStt())
    }

    @Test
    fun `getUseDeviceStt persists an explicit true after setUseDeviceStt`() {
        val settings = VoiceSettings(MapSettings())
        settings.setUseDeviceStt(true)
        assertTrue(settings.getUseDeviceStt())
    }

    // --- useDeviceLlm stays false by default (unchanged) ---

    @Test
    fun `getUseDeviceLlm is false by default`() {
        val settings = VoiceSettings(MapSettings())
        assertFalse(settings.getUseDeviceLlm())
    }

    // --- llmEnabled stays true by default (unchanged) ---

    @Test
    fun `getLlmEnabled is true by default`() {
        val settings = VoiceSettings(MapSettings())
        assertTrue(settings.getLlmEnabled())
    }

    // --- includeRawTranscript ---

    @Test
    fun `getIncludeRawTranscript_should_return_true_by_default`() {
        val settings = VoiceSettings(MapSettings())
        assertTrue(settings.getIncludeRawTranscript(), "Default should be true")
    }

    @Test
    fun `setIncludeRawTranscript_should_persist_value_across_get_calls`() {
        val settings = VoiceSettings(MapSettings())
        settings.setIncludeRawTranscript(false)
        assertFalse(settings.getIncludeRawTranscript(), "Expected persisted false value")
        settings.setIncludeRawTranscript(true)
        assertTrue(settings.getIncludeRawTranscript(), "Expected persisted true value after re-setting to true")
    }
}
