// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.tags

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct regression coverage for [LlmTagProvider.suggestTags]'s [DomainError] mapping —
 * in particular the bug named in requirements.md's Root Cause section, where the
 * `retryable` signal on [LlmResult.Failure.OnDeviceUnavailable] was silently dropped when
 * mapped to [DomainError.NetworkError.RequestFailed], collapsing every on-device-unavailable
 * failure (including transient "still downloading" states) to non-retryable.
 */
class LlmTagProviderTest {

    @Test
    fun `suggestTags maps a retryable OnDeviceUnavailable to a retryable RequestFailed`() = runTest {
        val formatter = LlmFormatterProvider { _, _ ->
            LlmResult.Failure.OnDeviceUnavailable(
                "Downloading on-device model — this may take a few minutes",
                retryable = true,
            )
        }
        val provider = LlmTagProvider(formatter, timeoutSeconds = 5)

        val result = provider.suggestTags(
            TagSuggestionRequest(
                blockUuid = "block-1",
                blockContent = "Kotlin is great",
                pageVocabulary = listOf("Kotlin"),
            ),
        )

        assertTrue(result.isLeft())
        assertEquals(
            DomainError.NetworkError.RequestFailed(
                message = "Downloading on-device model — this may take a few minutes",
                retryable = true,
            ),
            result.leftOrNull(),
        )
    }

    /**
     * Regression coverage for the same retryable-dropping bug class, this time triggered by a
     * plain [LlmResult.Failure.NetworkError] rather than [LlmResult.Failure.OnDeviceUnavailable].
     * A transient network error is a textbook retryable case — collapsing it to
     * `retryable = false` reproduces this PR's "frozen, no way forward" bug for a different
     * trigger (no retry button, and requestSuggestions' cache check treats it as terminal).
     */
    @Test
    fun `suggestTags maps a NetworkError to a retryable RequestFailed`() = runTest {
        val formatter = LlmFormatterProvider { _, _ -> LlmResult.Failure.NetworkError }
        val provider = LlmTagProvider(formatter, timeoutSeconds = 5)

        val result = provider.suggestTags(
            TagSuggestionRequest(
                blockUuid = "block-1",
                blockContent = "Kotlin is great",
                pageVocabulary = listOf("Kotlin"),
            ),
        )

        assertTrue(result.isLeft())
        assertEquals(
            DomainError.NetworkError.RequestFailed(
                message = "Network error",
                retryable = true,
            ),
            result.leftOrNull(),
        )
    }

    @Test
    fun `suggestTags tolerates bullets, numbering, wiki links and comma lists`() = runTest {
        val formatter = LlmFormatterProvider { _, _ ->
            LlmResult.Success("1. Galvanic Corrosion\n* [[Aluminum]]\n\"steel\", `Fastener`.\nUnknown", false)
        }
        val provider = LlmTagProvider(formatter, timeoutSeconds = 5)

        val result = provider.suggestTags(
            TagSuggestionRequest(
                blockUuid = "b",
                blockContent = "galvanic corrosion aluminum steel fastener",
                pageVocabulary = listOf("Galvanic Corrosion", "Aluminum", "Steel", "Fastener"),
            ),
        )

        assertEquals(
            listOf("Galvanic Corrosion", "Aluminum", "Steel", "Fastener"),
            result.getOrNull()?.map { it.term },
        )
    }

    private val parser = LlmTagProvider(LlmFormatterProvider { _, _ -> LlmResult.Success("", false) })

    @Test
    fun `parseResponse matches a comma-containing vocabulary name as a whole line`() {
        val result = parser.parseResponse("Smith, John", listOf("Smith, John", "Smith", "John"))

        assertEquals(listOf("Smith, John"), result.map { it.term })
    }

    @Test
    fun `parseResponse falls back to comma splitting when the whole line is not a name`() {
        val result = parser.parseResponse("Kotlin, Compose", listOf("Kotlin", "Compose"))

        assertEquals(listOf("Kotlin", "Compose"), result.map { it.term })
    }

    @Test
    fun `parseResponse keeps names with leading or trailing dots`() {
        val result = parser.parseResponse(".NET\nSt.", listOf(".NET", "St."))

        assertEquals(listOf(".NET", "St."), result.map { it.term })
    }

    @Test
    fun `parseResponse dedupes repeated names`() {
        val result = parser.parseResponse("Kotlin\n[[kotlin]]\n- KOTLIN", listOf("Kotlin"))

        assertEquals(listOf("Kotlin"), result.map { it.term })
    }

    @Test
    fun `parseResponse confidence decays only for matched lines`() {
        val result = parser.parseResponse("Kotlin\nNot a tag\nCompose", listOf("Kotlin", "Compose"))

        assertEquals(2, result.size)
        assertEquals(0.85f, result[0].confidence, 1e-4f)
        assertEquals(0.83f, result[1].confidence, 1e-4f)
    }

    @Test
    fun `parseResponse returns empty for a blank response`() {
        assertTrue(parser.parseResponse("  \n\n ", listOf("Kotlin")).isEmpty())
    }
}
