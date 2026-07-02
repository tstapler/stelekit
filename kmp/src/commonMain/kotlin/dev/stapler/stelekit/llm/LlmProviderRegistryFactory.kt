// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.ClaudeLlmFormatterProvider
import dev.stapler.stelekit.voice.GeminiLlmFormatterProvider
import dev.stapler.stelekit.voice.OpenAiLlmFormatterProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Composition root assembling the [LlmProviderRegistry] from credential-backed
 * Claude/OpenAI/Gemini wrappers, any number of user-configured [CustomProviderConfig]
 * instances, plus the platform on-device provider. Single call site for Epic 6 (Settings UI)
 * and Epic 8 (consumer migration) to depend on.
 *
 * Epic 8 migrated `App.kt`'s tag-suggestion (`buildLlmFormatterForTags`, now deleted) and
 * `VoicePipelineFactory`'s voice-formatting selection onto this registry — it is the single
 * production-code composition root for both consumers now, not just the Settings UI.
 *
 * Only providers that have credentials/capability are included — an absent Anthropic key
 * means no `"anthropic"` entry in [LlmProviderRegistry.all], not an entry that fails on first
 * use.
 *
 * [onDeviceProvider] defaults to the real platform hook ([platformOnDeviceLlmProvider]) but is
 * injectable so tests can supply a fake on-device provider without depending on a real
 * ML Kit/Foundation Models SDK (which cannot run in `businessTest`/`jvmTest`).
 *
 * [llmSettings] is optional — when `null` (the default), no custom OpenAI-compatible
 * providers are constructed. Passing a real [LlmSettings] instance enables one
 * [CustomOpenAiCompatibleLlmProvider] per id returned by [LlmSettings.getCustomProviderIds].
 */
fun buildLlmProviderRegistry(
    llmCredentialStore: LlmCredentialStore,
    llmSettings: LlmSettings? = null,
    onDeviceProvider: () -> LlmProvider? = ::platformOnDeviceLlmProvider,
): LlmProviderRegistry {
    val providers = mutableListOf<LlmProvider>()

    llmCredentialStore.getApiKey("anthropic")?.takeIf { it.isNotBlank() }?.let { key ->
        providers += RemoteLlmProvider(
            id = "anthropic",
            displayName = "Anthropic Claude",
            formatter = ClaudeLlmFormatterProvider.withDefaults(key),
        )
    }

    llmCredentialStore.getApiKey("openai")?.takeIf { it.isNotBlank() }?.let { key ->
        providers += RemoteLlmProvider(
            id = "openai",
            displayName = "OpenAI",
            formatter = OpenAiLlmFormatterProvider.withDefaults(key),
        )
    }

    llmCredentialStore.getApiKey("gemini")?.takeIf { it.isNotBlank() }?.let { key ->
        providers += RemoteLlmProvider(
            id = "gemini",
            displayName = "Google Gemini",
            formatter = GeminiLlmFormatterProvider.withDefaults(key),
        )
    }

    llmSettings?.getCustomProviderIds()?.forEach { id ->
        val config = llmSettings.getCustomProviderConfig(id) ?: return@forEach
        val apiKey = llmCredentialStore.getApiKey("custom.$id")
        providers += CustomOpenAiCompatibleLlmProvider(
            config = config,
            apiKey = apiKey,
            httpClient = defaultCustomProviderHttpClient(),
        )
    }

    onDeviceProvider()?.let { providers += it }

    return LlmProviderRegistry(providers)
}

private fun defaultCustomProviderHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}
