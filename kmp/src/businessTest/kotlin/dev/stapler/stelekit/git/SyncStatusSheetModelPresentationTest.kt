// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.SyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 1.7a (ux.md S1): the pure Running / Paused / Failed derivation, copy, actions and announcements. */
class SyncStatusSheetModelPresentationTest {
    private val now = 1_000_000L
    private val unknown = LockVerdict.OwnerUnknown(UnknownReason.TornRecord)
    private val lockError = DomainError.GitError.StaleLockFile("/r/.git/index.lock", unknown)

    private fun model(
        syncState: SyncState = SyncState.Idle,
        inFlight: SyncRunInfo? = null,
        verdict: LockVerdict? = null,
        paused: PausedRetryInfo? = null,
        retryNow: (() -> Unit)? = null,
    ) = syncStatusSheetModel(
        SyncStaleness(behindCount = 0), syncState, now, 15, firstSyncReviewPending = false,
        inFlight = inFlight, lastVerdict = verdict, pausedRetry = paused, retryNow = retryNow,
    )

    private fun run(followUp: Boolean = false) = SyncRunInfo(SyncOrigin.UiPress, now, "g", followUp)

    @Test
    fun idle_should_ShowSyncNow() {
        val m = model()
        assertEquals(SyncSheetState.Idle, m.state)
        assertTrue(m.canSyncNow)
        assertNull(m.retryNow)
    }

    @Test
    fun running_should_BeDerivedFromInFlight_EvenWhenSyncStateIsIdle() {
        val m = model(inFlight = run())
        assertEquals(SyncSheetState.Running(phase = null, followUpLine = null), m.state)
        assertFalse(m.canSyncNow)
        assertEquals("A sync is already running. Sync now is available once it finishes.", m.syncNowDisabledReason)
    }

    @Test
    fun running_should_NamePhaseFromSyncState() {
        val m = model(syncState = SyncState.Fetching, inFlight = run())
        assertEquals("Fetching", assertIs<SyncSheetState.Running>(m.state).phase)
    }

    @Test
    fun running_should_ShowFollowUpLineOnlyWhenQueued() {
        assertEquals("One more sync will run after this", assertIs<SyncSheetState.Running>(model(inFlight = run(true)).state).followUpLine)
        assertNull(assertIs<SyncSheetState.Running>(model(inFlight = run(false)).state).followUpLine)
    }

    @Test
    fun paused_should_ShowCauseNeutralCopyAndEnabledRetryNow() {
        var pressed = 0
        val m = model(SyncState.Error(lockError), paused = PausedRetryInfo(now + 61_000, 1), retryNow = { pressed++ })

        val s = assertIs<SyncSheetState.Paused>(m.state)
        assertEquals("Sync paused - retrying automatically", s.rowText)
        assertEquals(
            "We couldn't confirm that nothing else is syncing, so we paused to protect your notes. " +
                "We'll retry automatically (next try in ~2 min). Your notes are saved on this device.",
            s.body,
        )
        assertEquals("Lock owner unknown, try 1 of 2", s.details)
        assertTrue(s.canRemoveLock, "an unknown owner is recoverable through the confirmed removal")
        val retry = assertNotNull(m.retryNow)
        retry()
        assertEquals(1, pressed)
        assertNull(m.problem, "the paused state carries its own copy; no second error surface")
        assertTrue(m.canSyncNow)
    }

    @Test
    fun paused_copy_should_NeverAssertACauseOrUseFileJargon() {
        val s = assertIs<SyncSheetState.Paused>(model(SyncState.Error(lockError), paused = PausedRetryInfo(now + 30_000, 1)).state)
        listOf(s.rowText, s.body).forEach { text ->
            listOf("another git operation", "running", "index.lock", "stale").forEach { assertFalse(text.contains(it, ignoreCase = true), "$it in: $text") }
        }
    }

    @Test
    fun paused_should_OfferLockRemovalForNoRecord() {
        val noRecord = DomainError.GitError.StaleLockFile("/r/.git/index.lock", LockVerdict.NoRecord)
        val s = assertIs<SyncSheetState.Paused>(model(SyncState.Error(noRecord), paused = PausedRetryInfo(now + 30_000, 2)).state)
        assertTrue(s.canRemoveLock)
        assertEquals("Leftover lock file, try 2 of 2", s.details)
    }

    @Test
    fun paused_should_PreferTheServiceVerdictOverTheOneInTheError() {
        val bare = DomainError.GitError.StaleLockFile("/r/.git/index.lock")
        val s = assertIs<SyncSheetState.Paused>(model(SyncState.Error(bare), verdict = LockVerdict.NoRecord, paused = PausedRetryInfo(now + 1, 1)).state)
        assertTrue(s.canRemoveLock)
    }

    @Test
    fun nextTry_should_ReadNowOrUnderAMinuteOrMinutes() {
        fun body(at: Long) = assertIs<SyncSheetState.Paused>(model(SyncState.Error(lockError), paused = PausedRetryInfo(at, 1)).state).body
        assertTrue(body(now - 5).contains("next try now"))
        assertTrue(body(now + 20_000).contains("next try in under a minute"))
        assertTrue(body(now + 120_000).contains("next try in ~2 min"))
    }

    @Test
    fun failed_should_ShowFinalCopyWithSyncNowAndLockRemoval_After_TwoRetries() {
        val m = model(SyncState.Error(lockError), paused = null)

        val s = assertIs<SyncSheetState.Failed>(m.state)
        assertEquals("Couldn't sync. Your notes are saved on this device.", s.text)
        assertTrue(s.canRemoveLock)
        assertNull(m.retryNow, "Retry now exists only inside Paused")
        assertTrue(m.canSyncNow)
        assertNotNull(m.problem)
    }

    @Test
    fun running_should_WinOverPaused_WhileARetryIsExecuting() {
        val m = model(SyncState.Error(lockError), inFlight = run(), paused = PausedRetryInfo(now + 1, 1))
        assertIs<SyncSheetState.Running>(m.state)
        assertNull(m.retryNow)
    }

    @Test
    fun ownerAlive_should_ReadAsRunningNeverAnError() {
        val m = model(verdict = LockVerdict.OwnerAlive)
        assertIs<SyncSheetState.Running>(m.state)
    }

    @Test
    fun unrelatedErrors_should_KeepTheExistingProblemSurface() {
        val m = model(SyncState.Error(DomainError.GitError.Offline))
        assertEquals(SyncSheetState.Attention, m.state)
        assertNotNull(m.problem)
        assertNull(m.retryNow)
    }

    @Test
    fun announcement_should_FireOncePerEntryIntoPausedOrFailed() {
        val paused = assertIs<SyncSheetState.Paused>(model(SyncState.Error(lockError), paused = PausedRetryInfo(now + 1, 1)).state)
        val failed = assertIs<SyncSheetState.Failed>(model(SyncState.Error(lockError)).state)
        val running = SyncSheetState.Running(null, null)

        assertEquals("Sync paused. Retrying automatically.", syncAnnouncement(SyncSheetState.Idle, paused))
        assertNull(syncAnnouncement(paused, paused), "a retry tick is not a new entry")
        assertNull(syncAnnouncement(paused, paused.copy(details = "Lock owner unknown, try 2 of 2")))
        assertEquals("Couldn't sync. Your notes are saved on this device.", syncAnnouncement(paused, failed))
        assertNull(syncAnnouncement(failed, failed))
        assertNull(syncAnnouncement(SyncSheetState.Idle, running))
        assertNull(syncAnnouncement(null, paused), "no announcement on first composition")
        assertEquals("Sync paused. Retrying automatically.", syncAnnouncement(running, paused), "re-entering after a retry run announces again")
    }
}
