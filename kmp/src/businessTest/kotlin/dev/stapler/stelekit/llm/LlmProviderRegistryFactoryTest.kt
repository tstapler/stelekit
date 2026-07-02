// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmProviderRegistryFactoryTest {

    private class FakeCredentialAccess(keys: Map<String, String> = emptyMap()) : CredentialAccess {
        private val map = keys.mapKeys { "llm.${it.key}.api_key" }.toMutableMap()
        override fun retrieve(key: String): String? = map[key]
        override fun store(key: String, value: String) { map[key] = value }
        override fun delete(key: String) { map.remove(key) }
    }

    private fun FakeCredentialStore(keys: Map<String, String> = emptyMap()) =
        LlmCredentialStore(FakeCredentialAccess(keys))

    /** In-memory [Settings] fake — stores actual values, unlike a read-only stub. */
    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    private class FakeOnDeviceProvider : LlmProvider {
        override val id = "android-ondevice"
        override val displayName = "On-device"
        override val kind = LlmProviderKind.ON_DEVICE
        override val supportsLongFormOutput = false
        override val formatter: LlmFormatterProvider = LlmFormatterProvider { _, _ -> LlmResult.Success("unused") }
        override suspend fun checkAvailability(): LlmProviderAvailability = LlmProviderAvailability.Available
    }

    @Test
    fun `buildLlmProviderRegistry_should_ReturnEmptyRegistry_When_NoCredentialsAndNoOnDevice`() {
        val registry = buildLlmProviderRegistry(
            llmCredentialStore = FakeCredentialStore(),
            onDeviceProvider = { null },
        )
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `buildLlmProviderRegistry_should_ContainOneEntry_When_OnlyAnthropicKeyPresent`() {
        val registry = buildLlmProviderRegistry(
            llmCredentialStore = FakeCredentialStore(mapOf("anthropic" to "sk-test")),
            onDeviceProvider = { null },
        )
        assertEquals(listOf("anthropic"), registry.all().map { it.id })
    }

    @Test
    fun `buildLlmProviderRegistry_should_ContainOneEntry_When_OnlyOpenAiKeyPresent`() {
        val registry = buildLlmProviderRegistry(
            llmCredentialStore = FakeCredentialStore(mapOf("openai" to "sk-test")),
            onDeviceProvider = { null },
        )
        assertEquals(listOf("openai"), registry.all().map { it.id })
    }

    @Test
    fun `buildLlmProviderRegistry_should_ContainThreeEntries_When_BothKeysAndOnDevicePresent`() {
        val registry = buildLlmProviderRegistry(
            llmCredentialStore = FakeCredentialStore(mapOf("anthropic" to "sk-a", "openai" to "sk-o")),
            onDeviceProvider = { FakeOnDeviceProvider() },
        )
        assertEquals(setOf("anthropic", "openai", "android-ondevice"), registry.all().map { it.id }.toSet())
    }

    @Test
    fun `buildLlmProviderRegistry_should_OmitProvider_When_ApiKeyIsBlank`() {
        val registry = buildLlmProviderRegistry(
            llmCredentialStore = FakeCredentialStore(mapOf("anthropic" to "   ")),
            onDeviceProvider = { null },
        )
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `buildLlmProviderRegistry_should_IncludeGemini_When_GeminiKeyPresent`() {
        val registry = buildLlmProviderRegistry(
            llmCredentialStore = FakeCredentialStore(mapOf("gemini" to "sk-gem")),
            onDeviceProvider = { null },
        )
        assertEquals(listOf("gemini"), registry.all().map { it.id })
    }

    @Test
    fun `buildLlmProviderRegistry_should_ContainFourEntries_When_AllThreeKeysAndOnDevicePresent`() {
        val registry = buildLlmProviderRegistry(
            llmCredentialStore = FakeCredentialStore(
                mapOf("anthropic" to "sk-a", "openai" to "sk-o", "gemini" to "sk-g"),
            ),
            onDeviceProvider = { FakeOnDeviceProvider() },
        )
        assertEquals(
            setOf("anthropic", "openai", "gemini", "android-ondevice"),
            registry.all().map { it.id }.toSet(),
        )
    }

    @Test
    fun `buildLlmProviderRegistry_should_ConstructOneProviderPerCustomId_When_MultipleCustomConfigsExist`() {
        val settings = LlmSettings(MapSettings())
        settings.setCustomProviderConfig(
            CustomProviderConfig(
                id = "ollama-1",
                displayName = "Ollama (local)",
                baseUrl = "http://localhost:11434",
                model = "llama3.1",
                allowInsecureHttp = true,
            )
        )
        settings.setCustomProviderConfig(
            CustomProviderConfig(
                id = "openrouter-1",
                displayName = "OpenRouter",
                baseUrl = "https://openrouter.ai",
                model = "anthropic/claude-3.5-sonnet",
            )
        )

        val registry = buildLlmProviderRegistry(
            llmCredentialStore = FakeCredentialStore(),
            llmSettings = settings,
            onDeviceProvider = { null },
        )

        assertEquals(
            setOf("custom:ollama-1", "custom:openrouter-1"),
            registry.all().map { it.id }.toSet(),
        )
    }

    @Test
    fun `buildLlmProviderRegistry_should_OmitCustomProviders_When_LlmSettingsNotProvided`() {
        val registry = buildLlmProviderRegistry(
            llmCredentialStore = FakeCredentialStore(),
            onDeviceProvider = { null },
        )
        assertTrue(registry.all().isEmpty())
    }
}
