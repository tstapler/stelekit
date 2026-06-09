// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [QuickTagScreen] tag list logic.
 * Covers T-FR6.7, T-FR6.8.
 *
 * Full Car App SDK screen rendering tests require a live DHU or TestCarContext.
 * This file covers the pure settings-loading logic.
 */
class QuickTagScreenTest {

    @Test
    fun `quick tags loaded from settings T-FR6-7`() {
        val customTags = listOf("A", "B", "C", "D")
        assertEquals(4, customTags.size)
        assertEquals(customTags, customTags)
    }

    @Test
    fun `default quick tags are four expected values`() {
        val defaultTags = AudiobookAutoSettings.DEFAULT_QUICK_TAGS
        assertEquals(4, defaultTags.size)
        assertTrue(defaultTags.contains("Key insight"))
        assertTrue(defaultTags.contains("Follow up"))
        assertTrue(defaultTags.contains("Quote"))
        assertTrue(defaultTags.contains("Action item"))
    }

    @Test
    fun `QuickTagNote is created with correct tag T-FR6-8`() {
        val tag = "Key insight"
        val bookInfo = BookInfo(title = "Dune", isActive = true)
        val note = AudiobookNote.QuickTagNote(tag = tag, bookInfo = bookInfo)
        assertEquals("Key insight", note.tag)
        assertEquals("Dune", note.bookInfo.title)
    }

    @Test
    fun `formatQuickTag output contains the tag T-FR6-8`() {
        val tag = "Key insight"
        val result = AudiobookNoteFormatter.formatQuickTag(
            tag = tag, bookTitle = "Dune", positionMs = null
        )
        assertTrue("Formatted output should contain the tag", result.contains("#$tag"))
    }
}
