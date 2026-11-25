package dev.stapler.stelekit.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scripted event lists for Story 3.2. Each case names the `emission-trace.md` section it was built from;
 * the trace records `getBlockByUuid` emissions in feed order and Job completion markers.
 */
class TableSyncPolicyTest {
    private fun table(vararg rows: String): String =
        (listOf("| A | B |", "| --- | --- |") + rows.map { "| $it | x |" }).joinToString("\n")

    private val s0 = table("0")
    private val s1 = table("1")
    private val s2 = table("2")
    private val f = table("F")

    private fun policy(): TableSyncPolicy = TableSyncPolicy().also { it.registerLoaded(s0) }

    private fun TableSyncPolicy.adoptOf(content: String) = SyncDecision.Adopt(content)

    // trace (a): two quick commits, echoes S1 then S2 while both Jobs run.
    @Test
    fun outOfOrderEcho_should_BeStaleThenEcho_When_SecondCommitIssuedBeforeFirstEcho() {
        val p = policy()
        p.registerCommit(s1)
        p.registerCommit(s2)
        assertEquals(SyncDecision.Stale, p.classify(s1))
        assertEquals(SyncDecision.Echo, p.classify(s2))
    }

    // trace (a2): sequential commits; each echo arrives while the display already holds the newer commit or not.
    @Test
    fun echo_should_BeEcho_When_ObservedContentIsTheDisplayedCommit() {
        val p = policy()
        p.registerCommit(s1)
        assertEquals(SyncDecision.Echo, p.classify(s1))
        p.registerCommit(s2)
        assertEquals(SyncDecision.Echo, p.classify(s2))
        assertEquals(SyncDecision.Echo, p.classify(s2), "a repeated emission is an Echo")
    }

    // trace (a): conflated feed, only S2 ever observed.
    @Test
    fun conflation_should_AckOlderInFlightAndMakeLaterUndoAnAdopt_When_OnlyNewestObserved() {
        val p = policy()
        p.registerCommit(s1)
        p.registerCommit(s2)
        assertEquals(SyncDecision.Echo, p.classify(s2))
        p.expectAdopt(s1)
        assertEquals(p.adoptOf(s1), p.classify(s1))
    }

    // trace (c): commit S1, undo, redo; each emission is the expected result.
    @Test
    fun undoRedo_should_AdoptEachState_When_ResultsAreRegisteredAndObserved() {
        val p = policy()
        p.registerCommit(s1)
        assertEquals(SyncDecision.Echo, p.classify(s1))
        p.expectAdopt(s0)
        assertEquals(p.adoptOf(s0), p.classify(s0))
        p.expectAdopt(s1)
        assertEquals(p.adoptOf(s1), p.classify(s1))
    }

    // trace (c): undo and redo of Acked states with no expectation registered (all entries Acked, no InFlight).
    @Test
    fun undoRedo_should_AdoptAckedStates_When_NothingIsInFlight() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        p.registerCommit(s2); p.classify(s2)
        assertEquals(p.adoptOf(s1), p.classify(s1))
        assertEquals(p.adoptOf(s2), p.classify(s2))
    }

    @Test
    fun undo_should_ConflictKeepDraft_When_DraftIsPending() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        assertEquals(SyncDecision.ConflictKeepDraft, p.classify(s0, draftPending = true))
    }

    // trace (b) and (b2): the undo emission arrives before the re-commit is issued.
    @Test
    fun undoThenImmediateRecommit_should_AdoptThenEcho_When_UndoEmissionPrecedesRecommit() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        p.expectAdopt(s0)
        assertEquals(p.adoptOf(s0), p.classify(s0))
        p.registerCommit(s1)
        assertEquals(SyncDecision.Echo, p.classify(s1))
    }

    // Story 3.2 R3 case, literal order (not seen in trace (b)/(b2), where S0 always precedes the re-commit):
    // the re-commit is issued before the S0 emission is delivered.
    @Test
    fun undoThenImmediateRecommit_should_AdoptThenEcho_When_RecommitIssuedBeforeUndoEmission() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        p.expectAdopt(s0)
        p.registerCommit(s1)
        assertEquals(p.adoptOf(s0), p.classify(s0))
        assertEquals(SyncDecision.Echo, p.classify(s1))
    }

    // trace (c2): S1 and S2 committed, undo with no settle; S2 echoed, then the post-undo S1 emission.
    @Test
    fun tabTabCtrlZ_should_AdoptS1_When_ExpectedUndoResultObserved() {
        val p = policy()
        p.registerCommit(s1)
        p.registerCommit(s2)
        assertEquals(SyncDecision.Echo, p.classify(s2))
        p.expectAdopt(s1)
        assertEquals(p.adoptOf(s1), p.classify(s1))
    }

    // trace (c2): same, but the expectation arrives after the emission was judged; host re-classifies.
    @Test
    fun tabTabCtrlZ_should_CorrectStaleToAdopt_When_ExpectationRegisteredAfterEmission() {
        val p = policy()
        p.registerCommit(s1)
        p.registerCommit(s2)
        p.classify(s2)
        p.registerCommit(table("3"))
        assertEquals(SyncDecision.Stale, p.classify(s1))
        p.expectAdopt(s1)
        assertEquals(p.adoptOf(s1), p.classify(s1))
    }

    // trace (a): S1 and S2 InFlight, only S2 observed, then undo and S1.
    @Test
    fun conflationThenUndoToSkippedState_should_Adopt_When_NewestWasEchoed() {
        val p = policy()
        p.registerCommit(s1)
        p.registerCommit(s2)
        p.classify(s2)
        assertEquals(p.adoptOf(s1), p.classifyAfterExpect(s1))
    }

    private fun TableSyncPolicy.classifyAfterExpect(c: String): SyncDecision {
        expectAdopt(c)
        return classify(c)
    }

    // trace (a2): Job completes; no echo delivered (manager dropped the commit).
    @Test
    fun markDone_should_RetireDroppedCommit_When_NoEchoEverArrives() {
        val p = policy()
        val seq = p.registerCommit(s1)
        p.registerCommit(s2)
        p.classify(s2)
        p.markDone(seq)
        assertEquals(p.adoptOf(s1), p.classify(s1))
    }

    @Test
    fun markDone_should_NotLetLaterLegitimateEmissionBeStale_When_OnlyEntryWasInFlight() {
        val p = policy()
        val seq = p.registerCommit(s1)
        p.markDone(seq)
        assertEquals(SyncDecision.Echo, p.classify(s1))
        val other = p.classify(f)
        assertTrue(other is SyncDecision.Rebuild, "an external edit after markDone is a Rebuild, got $other")
    }

    // trace (a) step 4: S1 echo arrives after the display moved on to the adopted S1 and S2 is still InFlight.
    @Test
    fun lateEchoAfterAdopt_should_BeStale_When_NewerStateWasAdopted() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        p.registerCommit(s2)
        p.expectAdopt(s1)
        assertEquals(p.adoptOf(s1), p.classify(s1))
        assertEquals(SyncDecision.Stale, p.classify(s2))
    }

    // trace (d): foreign write F after commit S1; no draft keeps the selection.
    @Test
    fun externalChange_should_RebuildKeepingSelection_When_NoDraft() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        val tall = table("1", "2", "3", "4")
        assertEquals(SyncDecision.Rebuild(CellAddress(4, 2)), p.classify(tall, selection = CellAddress(4, 2)))
    }

    // trace (d3): the same, with fewer rows: selection clamps to the last row.
    @Test
    fun externalChange_should_ClampSelection_When_NewTableIsShorter() {
        val p = policy()
        val short = table("1", "2")
        assertEquals(SyncDecision.Rebuild(CellAddress(3, 2)), p.classify(short, selection = CellAddress(4, 2)))
    }

    // trace (d): foreign write with a pending draft.
    @Test
    fun externalChange_should_KeepDraft_When_DraftPending() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        assertEquals(SyncDecision.ConflictKeepDraft, p.classify(s2, draftPending = true, selection = CellAddress(3, 2)))
    }

    // trace (d3): external content equal to an Acked state is a silent revert (Adopt), accepted.
    @Test
    fun externalEditEqualToAckedState_should_Adopt_When_NothingInFlight() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        p.registerCommit(s2); p.classify(s2)
        assertEquals(p.adoptOf(s1), p.classify(s1))
    }

    // trace (d): external edit equal to an older InFlight entry is Stale (last writer wins on the next commit).
    @Test
    fun externalEditEqualToOlderInFlight_should_BeStale() {
        val p = policy()
        p.registerCommit(s1)
        p.registerCommit(s2)
        assertEquals(SyncDecision.Stale, p.classify(s1))
    }

    // trace (c) and (c2): 100 sequential commits then 100 undos, with and without registered expectations.
    @Test
    fun hundredDeepUndo_should_AdoptEveryState_When_LedgerHoldsThem() {
        for (withExpect in listOf(false, true)) {
            val p = TableSyncPolicy()
            val states = (0..100).map { table("v$it") }
            p.registerLoaded(states[0])
            for (i in 1..100) {
                p.registerCommit(states[i])
                assertEquals(SyncDecision.Echo, p.classify(states[i]))
            }
            for (i in 99 downTo 0) {
                if (withExpect) p.expectAdopt(states[i])
                assertEquals(SyncDecision.Adopt(states[i]), p.classify(states[i]), "undo to $i expect=$withExpect")
            }
        }
    }

    // trace (a2): 129 distinct states; the oldest is evicted and reads as external.
    @Test
    fun eviction_should_TreatOldestAsExternal_When_129StatesWereRegistered() {
        val p = TableSyncPolicy()
        val states = (0..128).map { table("v$it") }
        p.registerLoaded(states[0])
        for (i in 1..128) {
            p.registerCommit(states[i])
            p.classify(states[i])
        }
        assertEquals(OwnCommitLedger.DEFAULT_CAPACITY, p.ledger.size)
        val d = p.classify(states[0])
        assertTrue(d is SyncDecision.Rebuild, "oldest evicted state reads as external, got $d")
        val p2 = TableSyncPolicy()
        p2.registerLoaded(states[0])
        for (i in 1..128) { p2.registerCommit(states[i]); p2.classify(states[i]) }
        assertEquals(SyncDecision.ConflictKeepDraft, p2.classify(states[0], draftPending = true))
    }

    // trace (b): one entry per hash; a re-commit of an existing hash re-seqs it and makes it InFlight again.
    @Test
    fun recommitOfExistingHash_should_KeepOneEntry_When_SameContentCommittedTwice() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        val before = p.ledger.size
        p.registerCommit(s1)
        assertEquals(before, p.ledger.size)
    }

    // trace (d3): after a foreign write F the manager now follows the feed (P5-3), so the re-commit of S1 persists
    // and its echo is an Echo, not a Rebuild.
    @Test
    fun recommitAfterForeignWrite_should_EchoItsOwnContent_When_ForeignWasRebuilt() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        assertTrue(p.classify(f) is SyncDecision.Rebuild)
        p.registerCommit(s1)
        assertEquals(SyncDecision.Echo, p.classify(s1))
    }

    // Story 3.2 R2-B1: the policy reads content only. The host drops the DB version at its boundary, so two runs
    // that differ only in the (ignored) version must decide identically.
    @Test
    fun decisions_should_BeIdentical_When_DbVersionsAreSkewed() {
        data class Emission(val content: String, val dbVersion: Long)

        fun run(versions: List<Long>): List<SyncDecision> {
            val p = policy()
            p.registerCommit(s1)
            p.registerCommit(s2)
            p.expectAdopt(s0)
            val feed = listOf(s1, s2, s1, s0, s2, f).zip(versions) { c, v -> Emission(c, v) }
            return feed.map { p.classify(it.content) }
        }
        val ordered = run(listOf(1, 2, 3, 4, 5, 6))
        val skewed = run(listOf(40, 7, 7, 99, 3, 3))
        assertEquals(ordered, skewed)
        assertEquals(SyncDecision.Stale, ordered[0])
    }

    // trace (d3) limit: a foreign Rebuild issued while a newer own commit is InFlight leaves the older foreign
    // content displayed (the commit's echo reads Stale). Pinned as observed behavior, reported to the planner.
    @Test
    fun knownLimit_should_LeaveForeignDisplayed_When_ForeignRebuildOvertakesInFlightCommit() {
        val p = policy()
        p.registerCommit(s1)
        assertTrue(p.classify(f) is SyncDecision.Rebuild)
        assertEquals(SyncDecision.Stale, p.classify(s1))
    }

    // trace (b2) shows a commit emission arriving after its Job joined. If an undo is issued before an older
    // commit's emission is delivered, that old Acked emission reads Adopt after the undo's Adopt (rule 4) and
    // regresses the display. Pinned as observed; reported to the planner, not redesigned here.
    @Test
    fun knownLimit_should_RegressDisplay_When_OlderAckedEmissionTrailsUndoAdopt() {
        val p = policy()
        val q1 = p.registerCommit(s1)
        val q2 = p.registerCommit(s2)
        p.markDone(q1)
        p.markDone(q2)
        p.expectAdopt(s1)
        assertEquals(p.adoptOf(s1), p.classify(s1))
        assertEquals(p.adoptOf(s2), p.classify(s2))
    }

    // trace (b2): a commit's emission can trail its Job. markDone then acks the entry, so the late echo of an older
    // state reads Adopt (rule 4, no InFlight left) and regresses the display until the newer echo arrives.
    @Test
    fun knownLimit_should_RegressDisplay_When_MarkDonePrecedesOlderEcho() {
        val p = policy()
        val q1 = p.registerCommit(s1)
        val q2 = p.registerCommit(s2)
        p.markDone(q1)
        p.markDone(q2)
        assertEquals(p.adoptOf(s1), p.classify(s1))
        assertEquals(p.adoptOf(s2), p.classify(s2))
    }
}
