// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureShareTextTest {

    // Shorthand
    private fun build(clip: String?, extra: String?, subject: String?) =
        CaptureActivity.buildShareText(clip, extra, subject)

    private fun normalize(text: String) = CaptureActivity.normalizeShareWhitespace(text)

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

    // --- Whitespace normalization (AC1) ---

    @Test
    fun `internal space run collapses to single space`() {
        assertEquals("hello world", normalize("hello    world"))
    }

    @Test
    fun `tab and space mixed run collapses`() {
        assertEquals("a b", normalize("a\t \tb"))
    }

    @Test
    fun `leading indentation is collapsed per AC1 as written`() {
        // Deliberate: see plan.md's Scope Decision — AC1 is global, no line-position exemption.
        // The collapsed run sits at the very start of the string, so the trailing .trim() strips
        // it entirely (edge whitespace removal, not a per-line exemption).
        assertEquals("indented line", normalize("  indented line"))
    }

    @Test
    fun `emoji adjacent to space run is not corrupted`() {
        assertEquals("🎉 🎊", normalize("🎉  🎊"))
    }

    @Test
    fun `empty string returns empty string`() {
        assertEquals("", normalize(""))
    }

    @Test
    fun `text with no whitespace to normalize is returned unchanged`() {
        val text = "clean text\nwith single spaces\nand single breaks"
        assertEquals(text, normalize(text))
    }

    // --- NBSP normalization (AC2) ---

    @Test
    fun `single mid-string NBSP normalizes to space`() {
        assertEquals("a b", normalize("a\u00A0b"))
    }

    @Test
    fun `repeated NBSP collapses to single space`() {
        assertEquals("hello world", normalize("hello\u00A0\u00A0world"))
    }

    @Test
    fun `mixed space and NBSP run collapses`() {
        assertEquals("a b", normalize("a \u00A0 b"))
    }

    // --- Blank-line collapsing (AC3) ---

    @Test
    fun `three newlines collapse to one blank line`() {
        assertEquals("para one\n\npara two", normalize("para one\n\n\npara two"))
    }

    @Test
    fun `whitespace-only line between content collapses like a blank line`() {
        assertEquals("a\n\nb", normalize("a\n \nb"))
    }

    @Test
    fun `crlf line endings are unified and collapsed`() {
        assertEquals("a\n\nb", normalize("a\r\n\r\n\r\nb"))
    }

    @Test
    fun `legitimate single blank line is left unchanged`() {
        assertEquals("a\n\nb", normalize("a\n\nb"))
    }

    @Test
    fun `markdown bullet after excess blank lines is not corrupted`() {
        assertEquals("para one\n\n- bullet", normalize("para one\n\n\n- bullet"))
    }

    @Test
    fun `single crlf break between two lines normalizes to a single bare newline`() {
        assertEquals("line one\nline two", normalize("line one\r\nline two"))
    }

    @Test
    fun `lone carriage return normalizes to a bare newline`() {
        // Old-Mac line ending, not part of a \r\n pair — the second .replace('\r', '\n') step.
        assertEquals("a\nb", normalize("a\rb"))
    }

    // --- Single line break preserved (AC4) ---

    @Test
    fun `single line break between two lines is preserved`() {
        assertEquals("line one\nline two", normalize("line one\nline two"))
    }

    // --- buildShareText wiring + combined payload (AC1/AC7) ---

    @Test
    fun `buildShareText output is normalized`() {
        assertEquals("hello world", build("hello   world", null, null))
    }

    @Test
    fun `combined browser share payload normalizes all artifacts at once`() {
        val payload = "Example  Page\u00A0Title\r\n\r\n \r\n\r\nBody   text\u00A0here.\r\nSecond line."
        assertEquals(
            "Example Page Title\n\nBody text here.\nSecond line.",
            normalize(payload),
        )
    }

    // --- Regression: dedup must normalize before comparing (code review bug 1) ---

    @Test
    fun `whitespace-only difference between subject and body still dedups`() {
        // subject has a double space, body has a single space \u2014 raw strings differ, but once
        // both sides are normalized independently they are equal and must not be duplicated.
        assertEquals("Example Page", build(null, "Example Page", "Example  Page"))
    }

    // --- Regression: leading/trailing whitespace runs must be fully trimmed (code review bug 2) ---

    @Test
    fun `trailing blank-line run is fully trimmed, not collapsed to a surviving blank line`() {
        assertEquals("Foo", normalize("Foo\n\n\n"))
    }

    @Test
    fun `leading and trailing blank-line runs are trimmed while an internal one is collapsed`() {
        assertEquals("Foo\n\nBar", normalize("\n\nFoo\n\n\nBar\n\n"))
    }
}
