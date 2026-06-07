// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Tests that [NoOpJournalService] contract is safe.
 * Covers T-FR4.8.
 */
class NoOpJournalServiceTest {

    @Test
    fun `NoOpJournalService appendToToday completes without exception`() = runBlocking {
        val service = NoOpJournalService()
        // Should not throw
        service.appendToToday("anything")
    }

    @Test
    fun `NoOpJournalService createTranscriptPage returns a page without exception`() = runBlocking {
        val service = NoOpJournalService()
        val page = service.createTranscriptPage("title", "content")
        assertNotNull(page)
    }

    @Test
    fun `NoOpJournalService appendToPage completes without exception`() = runBlocking {
        val service = NoOpJournalService()
        val today = service.ensureTodayJournal()
        service.appendToPage(today.uuid, "content")
    }
}
