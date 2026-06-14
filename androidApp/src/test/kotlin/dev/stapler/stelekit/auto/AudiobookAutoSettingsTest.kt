// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [AudiobookAutoSettings] SharedPreferences round-trips.
 * Covers T-FR6.1–T-FR6.6.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AudiobookAutoSettingsTest {

    private lateinit var context: Context
    private lateinit var settings: AudiobookAutoSettings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("audiobook_auto_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = AudiobookAutoSettings(context)
    }

    @Test
    fun `default quick tags are four expected values T-FR6-1`() {
        val tags = settings.getQuickTags()
        assertEquals(4, tags.size)
        assertEquals(listOf("Key insight", "Follow up", "Quote", "Action item"), tags)
    }

    @Test
    fun `quick tags round-trip read-write T-FR6-2`() {
        settings.setQuickTags(listOf("A", "B"))
        assertEquals(listOf("A", "B"), settings.getQuickTags())
    }

    @Test
    fun `default snippet duration is 30 T-FR6-3`() {
        assertEquals(30, settings.getSnippetDurationSeconds())
    }

    @Test
    fun `snippet duration round-trip T-FR6-4`() {
        settings.setSnippetDurationSeconds(45)
        assertEquals(45, settings.getSnippetDurationSeconds())
    }

    @Test
    fun `default destination is BOTH T-FR6-5`() {
        assertEquals(NoteDestination.BOTH, settings.getNoteDestination())
    }

    @Test
    fun `destination round-trip for JOURNAL_ONLY T-FR6-6`() {
        settings.setNoteDestination(NoteDestination.JOURNAL_ONLY)
        assertEquals(NoteDestination.JOURNAL_ONLY, settings.getNoteDestination())
    }

    @Test
    fun `destination round-trip for BOOK_PAGE_ONLY T-FR6-6`() {
        settings.setNoteDestination(NoteDestination.BOOK_PAGE_ONLY)
        assertEquals(NoteDestination.BOOK_PAGE_ONLY, settings.getNoteDestination())
    }

    @Test
    fun `destination round-trip for BOTH T-FR6-6`() {
        settings.setNoteDestination(NoteDestination.BOTH)
        assertEquals(NoteDestination.BOTH, settings.getNoteDestination())
    }
}
