// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.tags

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Error mapping, prompt inputs and short-circuits of [LlmTagProvider] beyond `LlmTagProviderTest`. */
class LlmTagProviderBranchesTest {
    private class Recording(private val reply: suspend () -> LlmResult) : LlmFormatterProvider {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun format(transcript: String, systemPrompt: String): LlmResult {
            calls += transcript to systemPrompt
            return reply()
        }
    }

    private fun request(content: String = "kotlin coroutines guide", vocabulary: List<String> = listOf("Kotlin", "Cooking")) =
        TagSuggestionRequest(blockUuid = "b", blockContent = content, pageVocabulary = vocabulary)

    @Test
    fun apiErrorKeepsStatusCodeAndMessage() = runTest {
        val provider = LlmTagProvider(Recording { LlmResult.Failure.ApiError(429, "rate limited") })

        assertEquals(DomainError.NetworkError.HttpError(429, "rate limited"), provider.suggestTags(request()).leftOrNull())
    }

    @Test
    fun contentRejectionIsNonRetryableAndKeepsTheReason() = runTest {
        val provider = LlmTagProvider(Recording { LlmResult.Failure.ContentRejected("guardrail") })

        assertEquals(
            DomainError.NetworkError.RequestFailed("Content rejected: guardrail"),
            provider.suggestTags(request()).leftOrNull(),
        )
    }

    @Test
    fun anExceptionFromTheProviderBecomesRequestFailedWithItsMessage() = runTest {
        val provider = LlmTagProvider(Recording { error("socket closed") })

        assertEquals(DomainError.NetworkError.RequestFailed("socket closed"), provider.suggestTags(request()).leftOrNull())
    }

    @Test
    fun aStalledProviderTimesOutWithTheConfiguredBudget() = runTest {
        val provider = LlmTagProvider(Recording { awaitCancellation() }, timeoutSeconds = 3)

        assertEquals(
            DomainError.NetworkError.Timeout("LLM tag suggestion timed out after 3s"),
            provider.suggestTags(request()).leftOrNull(),
        )
    }

    @Test
    fun callerCancellationIsNotConvertedToAnError() = runTest {
        val provider = LlmTagProvider(Recording { throw CancellationException("caller gave up") })

        assertFailsWith<CancellationException> { provider.suggestTags(request()) }
    }

    @Test
    fun noVocabularyOverlapSkipsTheModelEntirely() = runTest {
        val recording = Recording { LlmResult.Success("Cooking") }

        val result = LlmTagProvider(recording).suggestTags(request(content = "kotlin coroutines", vocabulary = listOf("Cooking", "Travel")))

        assertEquals(emptyList(), result.getOrNull())
        assertEquals(0, recording.calls.size)
    }

    @Test
    fun onlyOverlappingVocabularyReachesThePromptAndOnlyThoseNamesCanBeReturned() = runTest {
        val recording = Recording { LlmResult.Success("Kotlin\nCooking") }

        val result = LlmTagProvider(recording).suggestTags(request(vocabulary = listOf("Kotlin", "Cooking", "Coroutines Guide")))

        val prompt = recording.calls.single().second
        assertEquals(true, "- Kotlin" in prompt && "- Coroutines Guide" in prompt, prompt)
        assertEquals(false, "- Cooking" in prompt, prompt)
        assertEquals(listOf("Kotlin"), result.getOrNull()?.map { it.term })
    }

    @Test
    fun blankBlockTextPassesTheWholeVocabularyThrough() = runTest {
        val recording = Recording { LlmResult.Success("Travel") }

        val result = LlmTagProvider(recording).suggestTags(request(content = "  ", vocabulary = listOf("Travel", "Cooking")))

        assertEquals(listOf("Travel"), result.getOrNull()?.map { it.term })
    }

    @Test
    fun blockTextIsTruncatedBeforeItReachesTheModel() = runTest {
        val recording = Recording { LlmResult.Success("") }

        LlmTagProvider(recording).suggestTags(request(content = "kotlin " + "x".repeat(2_000)))

        assertEquals(500, recording.calls.single().first.length)
    }

    @Test
    fun vocabularyIsCappedAtTwoHundredNames() = runTest {
        val recording = Recording { LlmResult.Success("") }
        val vocabulary = (1..300).map { "Kotlin topic $it" }

        LlmTagProvider(recording).suggestTags(request(vocabulary = vocabulary))

        val listed = recording.calls.single().second.lines().count { it.startsWith("- Kotlin topic") }
        assertEquals(200, listed)
    }

    @Test
    fun preloadSwallowsProviderFailures() = runTest {
        val recording = Recording { error("model not ready") }

        LlmTagProvider(recording).preload()

        assertEquals(1, recording.calls.size)
    }
}
