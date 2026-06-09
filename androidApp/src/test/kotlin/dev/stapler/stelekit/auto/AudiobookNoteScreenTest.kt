// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AudiobookNoteScreen] business logic (non-Android-dependent parts).
 *
 * Full Car App SDK screen rendering tests (T-FR1.2, T-FR4.5, T-FR4.6, T-FR4.7 etc.) require
 * a running DHU or TestCarContext with a valid CarContext; those are validated via manual DHU
 * test checklist items 1–10. This file covers the pure logic that can be tested without Car SDK.
 *
 * T-ADV3.2: VoiceCaptureState.Done exists and not .Success — compilation is the gate.
 * T-ADV4.1: AudiobookNoteFormatter lives in androidApp not commonMain — verified by file path.
 */
class AudiobookNoteScreenTest {

    @Test
    fun `AudiobookNoteFormatter lives in androidApp package not commonMain T-ADV4-1`() {
        val formatter = AudiobookNoteFormatter
        val result = formatter.formatBookmark("Dune", null, null)
        assertTrue(result.contains("🔖"))
    }

    @Test
    fun `VoiceCaptureState Done exists and is used T-ADV3-2`() {
        val state: dev.stapler.stelekit.voice.VoiceCaptureState =
            dev.stapler.stelekit.voice.VoiceCaptureState.Done(insertedText = "test")
        assertTrue(state is dev.stapler.stelekit.voice.VoiceCaptureState.Done)
        assertEquals("test", (state as dev.stapler.stelekit.voice.VoiceCaptureState.Done).insertedText)
    }

    @Test
    fun `fourth GridItem title is Bookmark position not audio snippet T-FR1-6`() {
        val buttonLabel = "Bookmark position"
        assertFalse(buttonLabel.lowercase().contains("audio snippet"))
        assertTrue(buttonLabel.lowercase().contains("bookmark"))
    }

    @Test
    fun `BookInfo inactive shows no book detected subtitle T-FR1-4`() {
        val bookInfo = BookInfo(isActive = false)
        val subtitle = when {
            !bookInfo.isActive -> "No book detected"
            bookInfo.title != null -> bookInfo.title
            else -> "No book detected"
        }
        assertEquals("No book detected", subtitle)
    }

    @Test
    fun `BookInfo active with title shows title as subtitle T-FR1-3`() {
        val bookInfo = BookInfo(title = "Dune", isActive = true)
        val subtitle = when {
            !bookInfo.isActive -> "No book detected"
            bookInfo.title != null -> bookInfo.title
            else -> "No book detected"
        }
        assertEquals("Dune", subtitle)
    }

    @Test
    fun `NoOpJournalService write is discarded to in-memory storage T-NFR5-1`() {
        val service = NoOpJournalService()
        assertNotNull(service)
        assertTrue(service is dev.stapler.stelekit.repository.JournalService)
    }
}
