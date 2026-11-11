// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult

/** Stubbed model for tests: records every call so "never reached the provider" is assertable. */
internal class FakeLlmProvider(
    override val id: String,
    override val supportsLongFormOutput: Boolean = true,
    private val response: LlmResult = LlmResult.Success(""),
) : LlmProvider {
    class Call(val transcript: String, val systemPrompt: String)

    val calls = mutableListOf<Call>()
    override val displayName: String = id
    override val kind: LlmProviderKind = LlmProviderKind.REMOTE
    override val formatter: LlmFormatterProvider = LlmFormatterProvider { transcript, systemPrompt ->
        calls += Call(transcript, systemPrompt)
        response
    }

    override suspend fun checkAvailability(): LlmProviderAvailability = LlmProviderAvailability.Available
}
