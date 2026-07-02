// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.LlmFormatterProvider

/**
 * Generic concrete [LlmProvider] wrapper for remote providers. Used to wrap
 * `ClaudeLlmFormatterProvider`/`OpenAiLlmFormatterProvider` (and, later, Gemini/generic
 * OpenAI-compatible) instances without writing one bespoke class per remote provider.
 *
 * Remote providers are considered "available" once constructed with a key — reachability
 * is not probed on every [checkAvailability] call.
 */
class RemoteLlmProvider(
    override val id: String,
    override val displayName: String,
    override val formatter: LlmFormatterProvider,
) : LlmProvider {
    override val kind = LlmProviderKind.REMOTE
    override suspend fun checkAvailability(): LlmProviderAvailability = LlmProviderAvailability.Available
}
