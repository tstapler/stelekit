// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LlmProviderSupportTest {

    // ─── wordCount ────────────────────────────────────────────────────────────

    @Test
    fun `wordCount returns 0 for empty string`() {
        assertEquals(0, LlmProviderSupport.wordCount(""))
    }

    @Test
    fun `wordCount counts single word`() {
        assertEquals(1, LlmProviderSupport.wordCount("hello"))
    }

    @Test
    fun `wordCount ignores leading and trailing whitespace`() {
        assertEquals(2, LlmProviderSupport.wordCount("  hello world  "))
    }

    // ─── estimateMaxTokens ────────────────────────────────────────────────────

    @Test
    fun `estimateMaxTokens clamps to 512 for empty transcript`() {
        assertEquals(512, LlmProviderSupport.estimateMaxTokens(""))
    }

    @Test
    fun `estimateMaxTokens clamps to 512 for 1-word transcript`() {
        assertEquals(512, LlmProviderSupport.estimateMaxTokens("hello"))
    }

    @Test
    fun `estimateMaxTokens returns wordCount times 2 for mid-range input`() {
        val transcript = "word ".repeat(300).trim() // 300 words → 600 tokens
        assertEquals(600, LlmProviderSupport.estimateMaxTokens(transcript))
    }

    @Test
    fun `estimateMaxTokens clamps to 4096 for very long transcript`() {
        val transcript = "word ".repeat(3000).trim() // 3000 words → 6000 clamped to 4096
        assertEquals(4096, LlmProviderSupport.estimateMaxTokens(transcript))
    }

    // ─── detectTruncation ────────────────────────────────────────────────────

    @Test
    fun `detectTruncation returns false for empty string`() {
        assertFalse(LlmProviderSupport.detectTruncation(""))
    }

    @Test
    fun `detectTruncation returns false for sentence ending with period`() {
        assertFalse(LlmProviderSupport.detectTruncation("Done."))
    }

    @Test
    fun `detectTruncation returns false for sentence ending with question mark`() {
        assertFalse(LlmProviderSupport.detectTruncation("Ready?"))
    }

    @Test
    fun `detectTruncation returns false for sentence ending with exclamation`() {
        assertFalse(LlmProviderSupport.detectTruncation("Great!"))
    }

    @Test
    fun `detectTruncation returns false for text ending with newline`() {
        assertFalse(LlmProviderSupport.detectTruncation("Done\n"))
    }

    @Test
    fun `detectTruncation returns true for mid-sentence cut`() {
        assertTrue(LlmProviderSupport.detectTruncation("I was going to"))
    }

    @Test
    fun `detectTruncation returns true for text ending with a letter`() {
        assertTrue(LlmProviderSupport.detectTruncation("incomplete sentenc"))
    }

    // ─── mapHttpError ────────────────────────────────────────────────────────

    @Test
    fun `mapHttpError 401 returns ApiError with Invalid API key`() {
        val result = LlmProviderSupport.mapHttpError(401)
        assertIs<LlmResult.Failure.ApiError>(result)
        assertEquals(401, result.code)
        assertTrue(result.message.contains("Invalid API key", ignoreCase = true))
    }

    @Test
    fun `mapHttpError 429 returns ApiError with Rate limit message`() {
        val result = LlmProviderSupport.mapHttpError(429)
        assertIs<LlmResult.Failure.ApiError>(result)
        assertEquals(429, result.code)
        assertTrue(result.message.contains("Rate limit", ignoreCase = true))
    }

    @Test
    fun `mapHttpError unknown status returns generic error with code`() {
        val result = LlmProviderSupport.mapHttpError(503)
        assertIs<LlmResult.Failure.ApiError>(result)
        assertEquals(503, result.code)
    }
}
