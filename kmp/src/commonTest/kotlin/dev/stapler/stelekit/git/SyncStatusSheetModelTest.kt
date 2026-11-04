// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.SyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncStatusSheetModelTest {
    private val minute = 60_000L
    private val now = 10_000_000L

    private fun model(
        staleness: SyncStaleness,
        syncState: SyncState = SyncState.Idle,
        reviewPending: Boolean = false,
        mismatch: Boolean = false,
    ) = syncStatusSheetModel(staleness, syncState, now, intervalMinutes = 15, firstSyncReviewPending = reviewPending, contentMismatch = mismatch)

    @Test
    fun `headline and severity are exactly the sidebar chip's`() {
        val s = SyncStaleness(lastMergedAtMs = now - 5 * minute, behindCount = 3)
        val chip = stalenessChipState(s, now, 15)
        val m = model(s)
        assertEquals(chip.text, m.headline)
        assertEquals(chip.severity, m.severity)
    }

    @Test
    fun `rows say never rather than blank for a graph that has not merged or fetched`() {
        val m = model(SyncStaleness())
        assertEquals("Never", m.rows.single { it.label == "Last merged" }.value)
        assertEquals("Never", m.rows.single { it.label == "Last checked" }.value)
        assertEquals("Checking…", m.rows.single { it.label == "Behind remote" }.value)
    }

    @Test
    fun `rows age the timestamps against now`() {
        val m = model(SyncStaleness(lastMergedAtMs = now - 90 * minute, lastFetchedAtMs = now - 2 * minute, behindCount = 0))
        assertEquals("1 h ago", m.rows.single { it.label == "Last merged" }.value)
        assertEquals("2 min ago", m.rows.single { it.label == "Last checked" }.value)
        assertEquals("Up to date", m.rows.single { it.label == "Behind remote" }.value)
    }

    @Test
    fun `fetch-only graphs explain why they cannot merge on their own`() {
        val saf = model(SyncStaleness(behindCount = 2, fetchOnlyReason = FetchOnlyReason.Saf)).explanation
        val closed = model(SyncStaleness(behindCount = 2, fetchOnlyReason = FetchOnlyReason.AppClosed)).explanation
        assertTrue(saf!!.contains("folder", ignoreCase = true))
        assertTrue(closed!!.contains("open", ignoreCase = true))
        assertNull(model(SyncStaleness(behindCount = 2)).explanation)
    }

    @Test
    fun `banner and badge follow BannerPrecedence over the same inputs`() {
        for (bits in 0 until 8) {
            val mismatch = bits and 1 != 0
            val review = bits and 2 != 0
            val behind = if (bits and 4 != 0) 4 else 0
            val m = model(SyncStaleness(behindCount = behind), reviewPending = review, mismatch = mismatch)
            val expected = BannerPrecedence.choose(mismatch, review, behind > 0, review)
            assertEquals(expected, m.bannerChoice, "subset $bits")
        }
    }

    @Test
    fun `recent checks list newest first with kind and age`() {
        val history = listOf(
            BackgroundOutcome(now - 30 * minute, BackgroundOutcomeKind.Fetched, 1),
            BackgroundOutcome(now - 15 * minute, BackgroundOutcomeKind.Error, 1, "AuthFailed"),
            BackgroundOutcome(now - 1 * minute, BackgroundOutcomeKind.Merged, 0),
        )
        val recent = model(SyncStaleness(history = history)).recent
        assertEquals(listOf("Merged · 1 min ago", "Failed (AuthFailed) · 15 min ago", "Fetched, 1 behind · 30 min ago"), recent)
    }

    @Test
    fun `sync now is withheld while a sync is running and review is offered only when owed`() {
        val running = model(SyncStaleness(), syncState = SyncState.Fetching)
        assertFalse(running.canSyncNow)
        assertTrue(model(SyncStaleness()).canSyncNow)
        assertFalse(model(SyncStaleness()).canReviewFirstSync)
        assertTrue(model(SyncStaleness(), reviewPending = true).canReviewFirstSync)
    }
}
