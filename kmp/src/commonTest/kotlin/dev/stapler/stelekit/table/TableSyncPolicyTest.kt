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

    // trace (c): an Acked state older than the newest own commit with no expectation registered is Stale (P5-5):
    // only `expectAdopt` makes an undo/redo target an Adopt.
    @Test
    fun undoRedo_should_BeStale_When_NoExpectationWasRegistered() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        p.registerCommit(s2); p.classify(s2)
        assertEquals(SyncDecision.Stale, p.classify(s1))
        assertEquals(SyncDecision.Echo, p.classify(s2))
    }

    @Test
    fun repeatedForeignEmission_should_ConflictKeepDraft_When_DraftIsPending() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        assertEquals(SyncDecision.ConflictKeepDraft, p.classify(f, draftPending = true))
        assertEquals(SyncDecision.ConflictKeepDraft, p.classify(f, draftPending = true))
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
    fun undoThenImmediateRecommit_should_AdoptThenReAdopt_When_RecommitIssuedBeforeUndoEmission() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        p.expectAdopt(s0)
        p.registerCommit(s1)
        assertEquals(p.adoptOf(s0), p.classify(s0))
        assertEquals(p.adoptOf(s1), p.classify(s1), "the display was moved to S0, so the re-commit must be re-shown")
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

    // trace (a2): Job completes; no echo delivered (manager dropped the commit). A retired entry no longer holds
    // foreign content back.
    @Test
    fun markDone_should_RetireDroppedCommit_When_NoEchoEverArrives() {
        val p = policy()
        val q1 = p.registerCommit(s1)
        val q2 = p.registerCommit(s2)
        p.markDone(q1)
        p.markDone(q2)
        assertTrue(p.classify(f) is SyncDecision.Rebuild, "no InFlight entry is left, so F is not deferred")
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

    // trace (d3): external content equal to a superseded Acked state is ignored (Stale), accepted: it is
    // indistinguishable from a late echo. A foreign revert to an older state is lost until the next write (P5-5).
    @Test
    fun externalEditEqualToSupersededAckedState_should_BeStale_When_NothingInFlight() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        p.registerCommit(s2); p.classify(s2)
        assertEquals(SyncDecision.Stale, p.classify(s1))
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
                val want = if (withExpect) SyncDecision.Adopt(states[i]) else SyncDecision.Stale
                assertEquals(want, p.classify(states[i]), "undo to $i expect=$withExpect")
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

    // P5-5 gap 2: a foreign emission while an own commit is InFlight is held, then dropped when the feed delivers the
    // commit (the DB ends at S1), so the older foreign content never stays displayed.
    @Test
    fun foreignEmission_should_BeDeferredThenSuperseded_When_OwnCommitEchoFollows() {
        val p = policy()
        val q1 = p.registerCommit(s1)
        assertEquals(SyncDecision.Defer, p.classify(f))
        assertEquals(SyncDecision.Echo, p.classify(s1))
        assertEquals(null, p.markDone(q1), "the superseded foreign content is not released")
    }

    // P5-5 gap 2: the feed conflated the commit's echo away and the DB ends at F; markDone releases F as a Rebuild.
    @Test
    fun markDone_should_ReleaseDeferredForeignAsRebuild_When_CommitJobCompletesWithoutEcho() {
        val p = policy()
        val q1 = p.registerCommit(s1)
        assertEquals(SyncDecision.Defer, p.classify(f))
        assertEquals(SyncDecision.Rebuild(CellAddress(2, 1)), p.markDone(q1, selection = CellAddress(2, 1)))
        assertEquals(null, p.markDone(q1), "released once")
    }

    @Test
    fun markDone_should_ReleaseDeferredForeignAsConflict_When_DraftPending() {
        val p = policy()
        val q1 = p.registerCommit(s1)
        p.classify(f)
        assertEquals(SyncDecision.ConflictKeepDraft, p.markDone(q1, draftPending = true))
    }

    @Test
    fun foreignEmission_should_KeepOnlyNewest_When_TwoForeignEmissionsPrecedeMarkDone() {
        val p = policy()
        val q1 = p.registerCommit(s1)
        p.classify(f)
        assertEquals(SyncDecision.Defer, p.classify(table("G")))
        assertEquals(SyncDecision.Rebuild(null), p.markDone(q1))
        assertEquals(SyncDecision.Echo, p.classify(table("G")))
    }

    @Test
    fun foreignEmission_should_StayDeferred_When_AnotherCommitIsStillInFlight() {
        val p = policy()
        val q1 = p.registerCommit(s1)
        p.registerCommit(s2)
        p.classify(f)
        assertEquals(null, p.markDone(q1))
    }

    // P5-5 gap 2 ambiguity: undo then re-commit of the undone content before the undo emission arrives, in every
    // delivery shape, ends with display == DB == the re-commit.
    @Test
    fun undoThenRecommit_should_EndAtRecommit_When_UndoEmissionDeliveredLate() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        p.expectAdopt(s0)
        val q = p.registerCommit(s1)
        val displayed = ArrayList<String>()
        for (c in listOf(s0, s1)) {
            when (val d = p.classify(c)) {
                is SyncDecision.Adopt -> displayed.add(d.content)
                else -> Unit
            }
        }
        p.markDone(q)
        assertEquals(s1, displayed.last())
        // conflated: only the re-commit is delivered
        val p2 = policy()
        p2.registerCommit(s1); p2.classify(s1)
        p2.expectAdopt(s0)
        p2.registerCommit(s1)
        assertEquals(SyncDecision.Echo, p2.classify(s1))
    }

    @Test
    fun undoThenDifferentCommit_should_EndAtCommit_When_UndoEmissionDeliveredLate() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        p.expectAdopt(s0)
        p.registerCommit(s2)
        assertEquals(p.adoptOf(s0), p.classify(s0))
        assertEquals(p.adoptOf(s2), p.classify(s2))
    }

    // P5-5 gap 1 (trace (b2)): the commits' Jobs completed before the older echo is delivered; the older echo is
    // Stale (superseded by the newer own commit), never an Adopt that moves the display backwards.
    @Test
    fun olderEcho_should_BeStale_When_MarkDonePrecedesIt() {
        val p = policy()
        val q1 = p.registerCommit(s1)
        val q2 = p.registerCommit(s2)
        p.markDone(q1)
        p.markDone(q2)
        assertEquals(SyncDecision.Stale, p.classify(s1))
        assertEquals(SyncDecision.Echo, p.classify(s2))
    }

    // P5-5 gap 1: an older acked emission trailing an undo's Adopt is Stale too; only the expectation adopts.
    @Test
    fun olderAckedEmission_should_BeStale_When_ItTrailsUndoAdopt() {
        val p = policy()
        val q1 = p.registerCommit(s1)
        val q2 = p.registerCommit(s2)
        p.markDone(q1)
        p.markDone(q2)
        p.expectAdopt(s1)
        assertEquals(p.adoptOf(s1), p.classify(s1))
        assertEquals(SyncDecision.Stale, p.classify(s2))
    }

    // P5-5 gap 1: redo needs its own expectation; an unexpected superseded state is Stale.
    @Test
    fun redo_should_AdoptOnlyWithExpectation_When_TargetIsNewerThanDisplayedAdopt() {
        val p = policy()
        p.registerCommit(s1); p.classify(s1)
        p.registerCommit(s2); p.classify(s2)
        p.expectAdopt(s1)
        assertEquals(p.adoptOf(s1), p.classify(s1))
        p.expectAdopt(s2)
        assertEquals(p.adoptOf(s2), p.classify(s2))
    }
}
