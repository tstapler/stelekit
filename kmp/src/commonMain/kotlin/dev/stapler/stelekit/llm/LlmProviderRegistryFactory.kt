// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.ClaudeLlmFormatterProvider
import dev.stapler.stelekit.voice.OpenAiLlmFormatterProvider

/**
 * Composition root assembling the [LlmProviderRegistry] from credential-backed Claude/OpenAI
 * wrappers plus the platform on-device provider. Single call site for Epic 6 (Settings UI) and
 * Epic 8 (consumer migration) to depend on.
 *
 * Does **not** touch `App.kt`'s existing `buildLlmFormatterForTags` or any current wiring —
 * this factory is added side-by-side, unused by production code until Epic 8.
 *
 * Only providers that have credentials/capability are included — an absent Anthropic key
 * means no `"anthropic"` entry in [LlmProviderRegistry.all], not an entry that fails on first
 * use.
 *
 * [onDeviceProvider] defaults to the real platform hook ([platformOnDeviceLlmProvider]) but is
 * injectable so tests can supply a fake on-device provider without depending on a real
 * ML Kit/Foundation Models SDK (which cannot run in `businessTest`/`jvmTest`).
 */
fun buildLlmProviderRegistry(
    llmCredentialStore: LlmCredentialStore,
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

    onDeviceProvider()?.let { providers += it }

    return LlmProviderRegistry(providers)
}
