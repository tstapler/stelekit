// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AudiobookNoteFormatter] in the androidApp test source set.
 * These run on the JVM (no Android API calls in AudiobookNoteFormatter).
 * Covers T-FR3.1–T-FR3.7, T-FR5.2.
 */
class AudiobookNoteFormatterAndroidTest {

    @Test
    fun `formatPositionMs 3661000ms produces 01-01-01`() {
        assertEquals("01:01:01", AudiobookNoteFormatter.formatPositionMs(3661000L))
    }

    @Test
    fun `formatPositionMs 60000ms produces 00-01-00`() {
        assertEquals("00:01:00", AudiobookNoteFormatter.formatPositionMs(60000L))
    }

    @Test
    fun `formatPositionMs 0ms produces 00-00-00`() {
        assertEquals("00:00:00", AudiobookNoteFormatter.formatPositionMs(0L))
    }

    @Test
    fun `formatPositionMs 3599999ms produces 00-59-59`() {
        assertEquals("00:59:59", AudiobookNoteFormatter.formatPositionMs(3599999L))
    }

    @Test
    fun `formatVoiceNote with all fields produces correct markdown T-FR3-1`() {
        val result = AudiobookNoteFormatter.formatVoiceNote(
            text = "hello",
            bookTitle = "Dune",
            positionMs = 3661000L,
            date = "2026-06-07",
        )
        assertTrue(result.contains("🎙️"))
        assertTrue(result.contains("\"hello\""))
        assertTrue(result.contains("#audiobook-note"))
        assertTrue(result.contains("[:book: [[Dune]]]"))
        assertTrue(result.contains("[:timer: 01:01:01]"))
        assertTrue(result.contains("[:calendar: 2026-06-07]"))
    }

    @Test
    fun `formatVoiceNote omits timer field when positionMs is null T-FR3-2`() {
        val result = AudiobookNoteFormatter.formatVoiceNote(
            text = "hello", bookTitle = "Dune", positionMs = null, date = "2026-06-07"
        )
        assertFalse(result.contains("[:timer:"))
        assertTrue(result.contains("[:book: [[Dune]]]"))
    }

    @Test
    fun `formatVoiceNote omits book field when title is null T-FR3-3`() {
        val result = AudiobookNoteFormatter.formatVoiceNote(
            text = "hello", bookTitle = null, positionMs = 60000L, date = "2026-06-07"
        )
        assertFalse(result.contains("[:book:"))
        assertTrue(result.contains("[:timer: 00:01:00]"))
    }

    @Test
    fun `formatBookmark produces correct markdown T-FR3-4`() {
        val result = AudiobookNoteFormatter.formatBookmark(
            bookTitle = "Dune", chapter = "Ch 5", positionMs = 120000L
        )
        assertTrue(result.contains("🔖"))
        assertTrue(result.contains("[[Dune]]"))
        assertTrue(result.contains("Ch 5"))
        assertTrue(result.contains("00:02:00"))
        assertTrue(result.contains("#audiobook-note"))
    }

    @Test
    fun `formatQuickTag produces correct markdown T-FR3-5`() {
        val result = AudiobookNoteFormatter.formatQuickTag(
            tag = "Key insight", bookTitle = "Dune", positionMs = 0L
        )
        assertTrue(result.contains("🏷️"))
        assertTrue(result.contains("#Key insight"))
        assertTrue(result.contains("[[Dune]]"))
        assertTrue(result.contains("00:00:00"))
        assertTrue(result.contains("#audiobook-note"))
    }

    @Test
    fun `formatAudioSnippetBookmark does not contain audio snippet wording T-FR5-2`() {
        val result = AudiobookNoteFormatter.formatAudioSnippetBookmark(
            bookTitle = "Dune", positionMs = 600000L
        )
        assertFalse("Must not contain 'audio snippet'", result.lowercase().contains("audio snippet"))
        assertTrue(result.contains("🔖"))
        assertTrue(result.contains("[[Dune]]"))
        assertTrue(result.contains("00:10:00"))
        assertTrue(result.contains("#audiobook-note"))
    }
}
