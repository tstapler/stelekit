// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.SyncState
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSafetyConfirmTest {

    private fun blocked(deleted: Int, sample: List<String>) = DomainError.GitError.MassChangeBlocked(deleted, 20, sample)

    @Test
    fun `detailsTarget_should_RouteEachProblemStateToItsSheet`() {
        assertEquals(SyncDetailsTarget.RepairNeeded, SyncState.Error(DomainError.GitError.RepairNeeded("MERGING")).detailsTarget())
        assertEquals(SyncDetailsTarget.MassChange, SyncState.Error(blocked(30, emptyList())).detailsTarget())
        assertEquals(SyncDetailsTarget.Conflict, SyncState.ConflictPending(emptyList()).detailsTarget())
        assertEquals(SyncDetailsTarget.None, SyncState.Error(DomainError.GitError.Offline).detailsTarget())
        assertEquals(SyncDetailsTarget.None, SyncState.Idle.detailsTarget())
    }

    @Test
    fun `typedConfirm_should_TrimAndIgnoreCase_ButRequireInternalWhitespace`() {
        assertTrue(TypedConfirm.matches("  SYNC \n", "sync"))
        assertTrue(TypedConfirm.matches("Sync", "sync"))
        assertFalse(TypedConfirm.matches("syn", "sync"))
        assertFalse(TypedConfirm.matches("", "sync"))
        assertTrue(TypedConfirm.matches("erase 3 DB-only journals", "erase 3 db-only JOURNALS"))
        assertFalse(TypedConfirm.matches("erase  3", "erase 3"))
    }

    @Test
    fun `challenge_should_AskForJournalCount_When_ItDiffersFromPrintedNumbers`() {
        val paths = List(5) { "journals/2026_10_0$it.md" } + List(45) { "pages/p$it.md" }
        val c = MassChangeChallenge.of(blocked(214, paths))

        assertEquals("Sync paused: 214 files would be deleted", c.headline)
        assertTrue(c.answerable)
        assertTrue(c.accepts("5"))
        assertTrue(c.accepts(" 5 "))
        assertFalse(c.accepts("5 files"))
        assertFalse(c.accepts("50"))
        assertFalse(c.accepts("214"))
        assertFalse(c.accepts(""))
        assertFalse(c.accepts("-5"))
    }

    @Test
    fun `challenge_should_FallToNextCandidate_When_JournalCountEqualsAPrintedNumber`() {
        // 50 listed, 50 are journals: journals (50) collides with "50" in the question, so non-journals (0) is used.
        val paths = List(50) { "journals/2026_10_$it.md" }
        val c = MassChangeChallenge.of(blocked(214, paths))

        assertTrue(c.answerable)
        assertTrue(c.accepts("0"))
        assertFalse(c.accepts("50"))
    }

    @Test
    fun `challenge_should_BeWithheld_When_EveryCandidateCollides`() {
        // 2 listed, both pages in one folder; headline says 2, so journals (0)... use headline numbers to collide all three.
        // deleted=0 prints "0"; listed=1 prints "1"; one non-journal in one folder => candidates journals=0, nonJournals=1, folders=1.
        val c = MassChangeChallenge.of(blocked(0, listOf("pages/a.md")))

        assertFalse(c.answerable)
        assertFalse(c.accepts("0"))
        assertFalse(c.accepts("1"))
    }

    @Test
    fun `challenge_should_NeverExpectANumberPrintedInThePrompt_Property`() = runTest {
        checkAll(Arb.int(0..1000), Arb.list(Arb.int(0..3).map { if (it == 0) "journals/j.md" else "pages/f$it/p.md" }, 0..60)) { deleted, paths ->
            val c = MassChangeChallenge.of(blocked(deleted, paths))
            val printed = Regex("[0-9]+").findAll(c.headline + " " + c.question).map { it.value }.toSet()
            for (guess in 0..200) {
                if (c.accepts(guess.toString())) assertFalse(guess.toString() in printed, "accepted printed number $guess")
            }
        }
    }
}
