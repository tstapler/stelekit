// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmSettingsCustomProviderTest {

    /** In-memory [Settings] fake — stores actual values, unlike a read-only stub. */
    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
    }

    @Test
    fun `getCustomProviderIds should be empty when none configured`() {
        val settings = LlmSettings(MapSettings())

        assertTrue(settings.getCustomProviderIds().isEmpty())
    }

    @Test
    fun `customProviderConfig should round-trip and survive removal of sibling`() {
        val settings = LlmSettings(MapSettings())

        val ollama = CustomProviderConfig(
            id = "ollama-1",
            displayName = "Ollama (local)",
            baseUrl = "http://localhost:11434/v1",
            model = "llama3.1",
            allowInsecureHttp = true,
        )
        val openRouter = CustomProviderConfig(
            id = "openrouter-1",
            displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "anthropic/claude-3.5-sonnet",
            allowInsecureHttp = false,
        )

        settings.setCustomProviderConfig(ollama)
        settings.setCustomProviderConfig(openRouter)

        assertEquals(setOf("ollama-1", "openrouter-1"), settings.getCustomProviderIds().toSet())
        assertEquals(ollama, settings.getCustomProviderConfig("ollama-1"))
        assertEquals(openRouter, settings.getCustomProviderConfig("openrouter-1"))

        // Removing one config must not disturb the other.
        settings.removeCustomProviderConfig("ollama-1")

        assertEquals(listOf("openrouter-1"), settings.getCustomProviderIds())
        assertNull(settings.getCustomProviderConfig("ollama-1"))
        assertEquals(openRouter, settings.getCustomProviderConfig("openrouter-1"))
    }

    @Test
    fun `setCustomProviderConfig should overwrite an existing config for the same id without duplicating the id list`() {
        val settings = LlmSettings(MapSettings())
        val original = CustomProviderConfig("azure-1", "Azure v1", "https://a.example.com", "gpt-4o")
        val updated = CustomProviderConfig("azure-1", "Azure v2", "https://b.example.com", "gpt-4o-mini", allowInsecureHttp = true)

        settings.setCustomProviderConfig(original)
        settings.setCustomProviderConfig(updated)

        assertEquals(listOf("azure-1"), settings.getCustomProviderIds())
        assertEquals(updated, settings.getCustomProviderConfig("azure-1"))
    }

    @Test
    fun `getCustomProviderConfig should return null for an unknown id`() {
        val settings = LlmSettings(MapSettings())

        assertNull(settings.getCustomProviderConfig("nonexistent"))
    }
}
