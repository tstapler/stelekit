// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectAnalysisSettingsTest {

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    private val feature = LlmFeature.PROJECT_ANALYSIS

    @Test
    fun `resolveExplicitProvider_should_ReturnNull_When_Unset`() {
        assertNull(LlmSettings(MapSettings()).resolveExplicitProvider(feature))
    }

    @Test
    fun `resolveExplicitProvider_should_ReturnNull_When_AutoIsStored`() {
        val settings = LlmSettings(MapSettings())
        settings.setSelectedProviderId(feature, null)
        assertNull(settings.resolveExplicitProvider(feature))
    }

    @Test
    fun `resolveExplicitProvider_should_ReturnNull_When_DisabledSentinel`() {
        val settings = LlmSettings(MapSettings())
        settings.setSelectedProviderId(feature, LlmProviderRegistry.DISABLED_SENTINEL)
        settings.acknowledge(feature, LlmProviderRegistry.DISABLED_SENTINEL, "")
        assertNull(settings.resolveExplicitProvider(feature))
    }

    @Test
    fun `resolveExplicitProvider_should_ReturnNull_When_ProviderChosenButNotAcknowledged`() {
        val settings = LlmSettings(MapSettings())
        settings.setSelectedProviderId(feature, "anthropic")
        assertNull(settings.resolveExplicitProvider(feature))
    }

    @Test
    fun `resolveExplicitProvider_should_ReturnProvider_When_ChosenAndAcknowledged`() {
        val settings = LlmSettings(MapSettings())
        settings.setSelectedProviderId(feature, "anthropic")
        settings.acknowledge(feature, "anthropic", "")
        assertEquals("anthropic", settings.resolveExplicitProvider(feature))
    }

    @Test
    fun `acknowledgement_should_Invalidate_When_CustomModelChanges`() {
        val settings = LlmSettings(MapSettings())
        settings.setCustomProviderConfig(CustomProviderConfig("local", "Local", "http://localhost:1234", "m1", true))
        settings.setSelectedProviderId(feature, "local")
        settings.acknowledge(feature, "local", settings.acknowledgementModelId("local"))
        assertEquals("local", settings.resolveExplicitProvider(feature))

        settings.setCustomProviderConfig(CustomProviderConfig("local", "Local", "http://localhost:1234", "m2", true))
        assertNull(settings.resolveExplicitProvider(feature))
    }

    @Test
    fun `acknowledgement_should_NotLeak_When_ProviderChanges`() {
        val settings = LlmSettings(MapSettings())
        settings.acknowledge(feature, "anthropic", "")
        assertFalse(settings.isAcknowledged(feature, "openai", ""))
        assertTrue(settings.isAcknowledged(feature, "anthropic", ""))
    }

    @Test
    fun `acknowledgement_should_BeFeatureScoped`() {
        val settings = LlmSettings(MapSettings())
        settings.acknowledge(LlmFeature.TAG_SUGGESTION, "anthropic", "")
        assertFalse(settings.isAcknowledged(feature, "anthropic", ""))
    }

    @Test
    fun `acknowledgement_text_should_DependOnProviderKind`() {
        val remote = ProjectAnalysisAcknowledgement.text(LlmProviderKind.REMOTE, isCustomEndpoint = false)
        val onDevice = ProjectAnalysisAcknowledgement.text(LlmProviderKind.ON_DEVICE, isCustomEndpoint = false)
        val custom = ProjectAnalysisAcknowledgement.text(LlmProviderKind.REMOTE, isCustomEndpoint = true)
        assertEquals(3, setOf(remote, onDevice, custom).size)
        assertTrue(custom.contains("Confirm where it runs"))
    }
}
