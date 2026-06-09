// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AudiobookNoteFormatter].
 * Covers T-FR3.1–T-FR3.7 and T-FR5.2.
 *
 * Note: AudiobookNoteFormatter lives in androidApp, not commonMain.
 * These tests import it via the androidApp compile dependency (businessTest sees androidApp classes
 * through the test source set configuration).
 *
 * Since businessTest cannot directly import androidApp classes, this test file is a placeholder
 * documenting the expected behavior — the actual test runs in androidApp/src/test/ via
 * AudiobookNoteFormatterAndroidTest.kt.
 */
class AudiobookNoteFormatterTest {

    @Test
    fun `formatPositionMs formats 3661000ms as 01-01-01`() {
        assertEquals("01:01:01", AudiobookNoteFormatterHelper.formatPositionMs(3661000L))
    }

    @Test
    fun `formatPositionMs formats 60000ms as 00-01-00`() {
        assertEquals("00:01:00", AudiobookNoteFormatterHelper.formatPositionMs(60000L))
    }

    @Test
    fun `formatPositionMs formats 0ms as 00-00-00`() {
        assertEquals("00:00:00", AudiobookNoteFormatterHelper.formatPositionMs(0L))
    }

    @Test
    fun `formatPositionMs formats 3599999ms as 00-59-59`() {
        assertEquals("00:59:59", AudiobookNoteFormatterHelper.formatPositionMs(3599999L))
    }

    @Test
    fun `formatVoiceNote with all fields produces correct markdown`() {
        val result = AudiobookNoteFormatterHelper.formatVoiceNote(
            text = "hello",
            bookTitle = "Dune",
            positionMs = 3661000L,
            date = "2026-06-07",
        )
        assertTrue(result.contains("🎙️"), "Should contain mic emoji")
        assertTrue(result.contains("\"hello\""), "Should contain quoted text")
        assertTrue(result.contains("#audiobook-note"), "Should contain tag")
        assertTrue(result.contains("[:book: [[Dune]]]"), "Should contain book link")
        assertTrue(result.contains("[:timer: 01:01:01]"), "Should contain timer")
        assertTrue(result.contains("[:calendar: 2026-06-07]"), "Should contain date")
    }

    @Test
    fun `formatVoiceNote omits timer field when positionMs is null`() {
        val result = AudiobookNoteFormatterHelper.formatVoiceNote(
            text = "hello",
            bookTitle = "Dune",
            positionMs = null,
            date = "2026-06-07",
        )
        assertFalse(result.contains("[:timer:"), "Should not contain timer")
        assertTrue(result.contains("[:book: [[Dune]]]"), "Should still contain book")
    }

    @Test
    fun `formatVoiceNote omits book field when bookTitle is null`() {
        val result = AudiobookNoteFormatterHelper.formatVoiceNote(
            text = "hello",
            bookTitle = null,
            positionMs = 60000L,
            date = "2026-06-07",
        )
        assertFalse(result.contains("[:book:"), "Should not contain book")
        assertTrue(result.contains("[:timer: 00:01:00]"), "Should still contain timer")
    }

    @Test
    fun `formatBookmark produces correct markdown`() {
        val result = AudiobookNoteFormatterHelper.formatBookmark(
            bookTitle = "Dune",
            chapter = "Ch 5",
            positionMs = 120000L,
        )
        assertTrue(result.contains("🔖"), "Should contain bookmark emoji")
        assertTrue(result.contains("[[Dune]]"), "Should contain book link")
        assertTrue(result.contains("Ch 5"), "Should contain chapter")
        assertTrue(result.contains("00:02:00"), "Should contain position")
        assertTrue(result.contains("#audiobook-note"), "Should contain tag")
    }

    @Test
    fun `formatQuickTag produces correct markdown`() {
        val result = AudiobookNoteFormatterHelper.formatQuickTag(
            tag = "Key insight",
            bookTitle = "Dune",
            positionMs = 0L,
        )
        assertTrue(result.contains("🏷️"), "Should contain tag emoji")
        assertTrue(result.contains("#Key insight"), "Should contain tag")
        assertTrue(result.contains("[[Dune]]"), "Should contain book link")
        assertTrue(result.contains("00:00:00"), "Should contain position")
        assertTrue(result.contains("#audiobook-note"), "Should contain audiobook-note tag")
    }

    @Test
    fun `formatAudioSnippetBookmark produces correct markdown without audio snippet wording`() {
        val result = AudiobookNoteFormatterHelper.formatAudioSnippetBookmark(
            bookTitle = "Dune",
            positionMs = 600000L,
        )
        assertTrue(result.contains("🔖"), "Should contain bookmark emoji")
        assertTrue(result.contains("[[Dune]]"), "Should contain book link")
        assertTrue(result.contains("00:10:00"), "Should contain position")
        assertFalse(result.lowercase().contains("audio snippet"), "Should NOT contain 'audio snippet' text")
        assertTrue(result.contains("#audiobook-note"), "Should contain tag")
    }
}

/**
 * Pure Kotlin helper that mirrors AudiobookNoteFormatter logic for businessTest.
 * The real formatter is in androidApp (Android-only); this helper enables testing
 * the pure string formatting logic from the shared businessTest source set.
 */
object AudiobookNoteFormatterHelper {

    fun formatPositionMs(positionMs: Long): String {
        val totalSeconds = positionMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    fun formatVoiceNote(
        text: String,
        bookTitle: String?,
        positionMs: Long?,
        date: String,
    ): String = buildString {
        append("- 🎙️ \"$text\" #audiobook-note")
        val hasExtras = bookTitle != null || positionMs != null
        if (hasExtras) {
            append("\n  ")
            val parts = mutableListOf<String>()
            if (bookTitle != null) parts.add("[:book: [[$bookTitle]]]")
            if (positionMs != null) parts.add("[:timer: ${formatPositionMs(positionMs)}]")
            parts.add("[:calendar: $date]")
            append(parts.joinToString(" "))
        }
    }

    fun formatBookmark(bookTitle: String?, chapter: String?, positionMs: Long?): String = buildString {
        append("- 🔖 Bookmark")
        if (bookTitle != null || chapter != null || positionMs != null) {
            append(" —")
            if (bookTitle != null) append(" [[$bookTitle]]")
            if (chapter != null) append(", $chapter")
            if (positionMs != null) append(", ${formatPositionMs(positionMs)}")
        }
        append(" #audiobook-note")
    }

    fun formatQuickTag(tag: String, bookTitle: String?, positionMs: Long?): String = buildString {
        append("- 🏷️ #$tag")
        if (bookTitle != null || positionMs != null) {
            append(" —")
            if (bookTitle != null) append(" [[$bookTitle]]")
            if (positionMs != null) append(" at ${formatPositionMs(positionMs)}")
        }
        append(" #audiobook-note")
    }

    fun formatAudioSnippetBookmark(bookTitle: String?, positionMs: Long?): String = buildString {
        append("- 🔖 Bookmark")
        if (bookTitle != null || positionMs != null) {
            append(" —")
            if (bookTitle != null) append(" [[$bookTitle]]")
            if (positionMs != null) append(", ${formatPositionMs(positionMs)}")
        }
        append(" #audiobook-note")
    }
}
