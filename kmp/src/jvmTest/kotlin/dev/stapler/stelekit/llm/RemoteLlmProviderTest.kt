// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class RemoteLlmProviderTest {

    private class FakeFormatter(val result: LlmResult) : LlmFormatterProvider {
        var lastTranscript: String? = null
        var lastSystemPrompt: String? = null
        override suspend fun format(transcript: String, systemPrompt: String): LlmResult {
            lastTranscript = transcript
            lastSystemPrompt = systemPrompt
            return result
        }
    }

    @Test
    fun `checkAvailability_should_AlwaysReturnAvailable`() = runTest {
        val provider = RemoteLlmProvider(
            id = "anthropic",
            displayName = "Anthropic Claude",
            formatter = FakeFormatter(LlmResult.Success("ignored")),
        )
        assertEquals(LlmProviderAvailability.Available, provider.checkAvailability())
    }

    @Test
    fun `kind_should_BeRemote`() {
        val provider = RemoteLlmProvider(
            id = "openai",
            displayName = "OpenAI",
            formatter = FakeFormatter(LlmResult.Success("ignored")),
        )
        assertEquals(LlmProviderKind.REMOTE, provider.kind)
    }

    @Test
    fun `formatter_should_DelegateUnchanged_ToWrappedLlmFormatterProvider`() = runTest {
        val fake = FakeFormatter(LlmResult.Success("formatted output"))
        val provider = RemoteLlmProvider(id = "anthropic", displayName = "Anthropic Claude", formatter = fake)

        val result = provider.formatter.format("raw transcript", "system prompt")

        assertIs<LlmResult.Success>(result)
        assertEquals("formatted output", result.formattedText)
        assertEquals("raw transcript", fake.lastTranscript)
        assertEquals("system prompt", fake.lastSystemPrompt)
        assertSame(fake, provider.formatter)
    }
}
