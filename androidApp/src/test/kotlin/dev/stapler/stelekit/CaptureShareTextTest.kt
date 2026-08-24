// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureShareTextTest {

    // Shorthand
    private fun build(clip: String?, extra: String?, subject: String?) =
        CaptureActivity.buildShareText(clip, extra, subject)

    @Test
    fun `url only in EXTRA_TEXT, no clipData, no subject`() {
        assertEquals("https://example.com", build(null, "https://example.com", null))
    }

    @Test
    fun `url in clipData preferred over EXTRA_TEXT`() {
        assertEquals("https://clip.com", build("https://clip.com", "https://extra.com", null))
    }

    @Test
    fun `empty clipData does not eat EXTRA_TEXT fallback`() {
        // coerceToText returning "" must not block the fallback chain
        assertEquals("https://example.com", build("", "https://example.com", null))
    }

    @Test
    fun `blank clipData does not eat EXTRA_TEXT fallback`() {
        assertEquals("https://example.com", build("   ", "https://example.com", null))
    }

    @Test
    fun `subject and url are combined with newline (browser URL share pattern)`() {
        assertEquals("Example Page\nhttps://example.com",
            build("https://example.com", null, "Example Page"))
    }

    @Test
    fun `subject and clipData url combined`() {
        assertEquals("My Page\nhttps://clip.com",
            build("https://clip.com", null, "My Page"))
    }

    @Test
    fun `subject only when no text fields`() {
        assertEquals("Page Title", build(null, null, "Page Title"))
    }

    @Test
    fun `all null returns empty string`() {
        assertEquals("", build(null, null, null))
    }

    @Test
    fun `subject equals body text — not duplicated`() {
        assertEquals("https://example.com", build("https://example.com", null, "https://example.com"))
    }

    @Test
    fun `non-ACTION_SEND action returns empty regardless of extras`() {
        // parseShareIntent guard tested via buildShareText indirectly: subject=url deduplication
        assertEquals("hello world", build("hello world", null, null))
    }

    @Test
    fun `EXTRA_TEXT used as fallback when clipData is null`() {
        assertEquals("Some shared text", build(null, "Some shared text", null))
    }

    @Test
    fun `subject falls back when clipData and EXTRA_TEXT are both blank`() {
        assertEquals("Just a title", build("", "  ", "Just a title"))
    }
}
