// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScheduledSyncPolicyTest {

    private val open = ScheduledSyncInputs(
        online = true, vaultUnlocked = true, editing = false, busy = false, backoffActive = false,
        conflictPending = false, repositorySafe = true, appOwnedPath = true, firstSyncConfirmed = true,
    )

    private fun skip(reason: ScheduledSkipReason) = ScheduledSyncDecision.Skip(reason)

    @Test
    fun `all clear runs a full sync`() {
        assertEquals(ScheduledSyncDecision.Run, ScheduledSyncPolicy.decide(open))
    }

    @Test
    fun `each blocking input maps to its skip reason`() {
        assertEquals(skip(ScheduledSkipReason.Busy), ScheduledSyncPolicy.decide(open.copy(busy = true)))
        assertEquals(skip(ScheduledSkipReason.EditingInProgress), ScheduledSyncPolicy.decide(open.copy(editing = true)))
        assertEquals(skip(ScheduledSkipReason.VaultLocked), ScheduledSyncPolicy.decide(open.copy(vaultUnlocked = false)))
        assertEquals(skip(ScheduledSkipReason.Offline), ScheduledSyncPolicy.decide(open.copy(online = false)))
        assertEquals(skip(ScheduledSkipReason.ConflictPending), ScheduledSyncPolicy.decide(open.copy(conflictPending = true)))
        assertEquals(skip(ScheduledSkipReason.Backoff), ScheduledSyncPolicy.decide(open.copy(backoffActive = true)))
        assertEquals(skip(ScheduledSkipReason.RepoState), ScheduledSyncPolicy.decide(open.copy(repositorySafe = false)))
        assertEquals(skip(ScheduledSkipReason.FirstSyncUnconfirmed), ScheduledSyncPolicy.decide(open.copy(firstSyncConfirmed = false)))
    }

    @Test
    fun `a graph that is not app-owned is fetch-only`() {
        assertEquals(ScheduledSyncDecision.FetchOnly(FetchOnlyReason.Saf), ScheduledSyncPolicy.decide(open.copy(appOwnedPath = false)))
    }

    @Test
    fun `the closed-app runner is fetch-only`() {
        assertEquals(ScheduledSyncDecision.FetchOnly(FetchOnlyReason.AppClosed), ScheduledSyncPolicy.decide(open.copy(appOpen = false)))
    }

    @Test
    fun `only repo state and first sync confirmation still fetch`() {
        val fetching = ScheduledSkipReason.entries.filter { it.fetchesAnyway }.toSet()
        assertEquals(setOf(ScheduledSkipReason.RepoState, ScheduledSkipReason.FirstSyncUnconfirmed), fetching)
    }

    @Test
    fun `never runs while any input blocks, across every combination`() {
        var combos = 0
        for (mask in 0 until (1 shl BITS)) {
            fun bit(i: Int) = mask and (1 shl i) != 0
            val inputs = ScheduledSyncInputs(
                online = bit(0), vaultUnlocked = bit(1), editing = bit(2), busy = bit(3), backoffActive = bit(4),
                conflictPending = bit(5), repositorySafe = bit(6), appOwnedPath = bit(7), firstSyncConfirmed = bit(8), appOpen = bit(9),
            )
            val blocking = !inputs.online || !inputs.vaultUnlocked || inputs.editing || inputs.busy || inputs.backoffActive ||
                inputs.conflictPending || !inputs.repositorySafe || !inputs.appOwnedPath || !inputs.firstSyncConfirmed || !inputs.appOpen
            val decision = ScheduledSyncPolicy.decide(inputs)
            if (blocking) assertNotEquals(ScheduledSyncDecision.Run, decision, "ran with blocking inputs $inputs")
            else assertEquals(ScheduledSyncDecision.Run, decision)
            combos++
        }
        assertEquals(1 shl BITS, combos)
    }

    @Test
    fun `no-git skips never come back from an input set that is only blocked by repo state or consent`() {
        val decision = ScheduledSyncPolicy.decide(open.copy(repositorySafe = false, firstSyncConfirmed = false))
        assertTrue(decision is ScheduledSyncDecision.Skip && decision.reason.fetchesAnyway)
    }

    @Test
    fun `backoff doubles from one minute and is capped at thirty`() {
        assertEquals(0L, ScheduledSyncPolicy.backoffMs(0))
        assertEquals(60_000L, ScheduledSyncPolicy.backoffMs(1))
        assertEquals(120_000L, ScheduledSyncPolicy.backoffMs(2))
        assertEquals(240_000L, ScheduledSyncPolicy.backoffMs(3))
        assertEquals(30 * 60_000L, ScheduledSyncPolicy.backoffMs(50))
    }

    private companion object {
        const val BITS = 10
    }
}
