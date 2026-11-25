// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.table.SyncDecision
import dev.stapler.stelekit.table.TableSyncPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `TableCommitter` against a scripted manager: serial writes, the sheet's own undo scope, the ledger calls around
 * undo, and every degraded path. The real manager and the real feed are covered by `TableSheetHostTest` and
 * `TableSheetRealFeedTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableCommitterTest {
    private val uuid = BlockUuid("b1")

    /** A manager whose write order, undo stack and record counter behave like the real one's. */
    private class FakePort(private val scope: CoroutineScope, start: String) : TableManagerPort {
        var content: String? = start
        var stored: String? = start
        val records = MutableStateFlow(0L)
        override val undoRecordCount: StateFlow<Long> = records
        override val editingBlockUuid = MutableStateFlow<BlockUuid?>(null)
        val writes = mutableListOf<String>()
        val versions = mutableListOf<Long>()
        var undoCalls = 0
        var redoCalls = 0
        var gate: CompletableDeferred<Unit>? = null
        var persist = true
        var openEditorOnUndo = false
        val stack = ArrayDeque<Pair<String, String>>()
        private val redoStack = ArrayDeque<Pair<String, String>>()

        /** An entry recorded by some other surface. */
        fun foreignRecord() {
            stack.addLast("x" to "y")
            records.value += 1
        }

        override fun write(uuid: BlockUuid, text: String, version: Long): Job = scope.launch {
            gate?.await()
            val old = content ?: return@launch
            versions += version
            writes += text
            if (old == text) return@launch
            content = text
            if (persist) stored = text
            stack.addLast(old to text)
            redoStack.clear()
            records.value += 1
        }

        override fun undo(): Job = scope.launch {
            undoCalls++
            val (old, new) = stack.removeLastOrNull() ?: return@launch
            if (old == "x") return@launch
            content = old
            stored = old
            redoStack.addLast(old to new)
            if (openEditorOnUndo) editingBlockUuid.value = BlockUuid("b1")
        }

        override fun redo(): Job = scope.launch {
            redoCalls++
            val (old, new) = redoStack.removeLastOrNull() ?: return@launch
            content = new
            stored = new
            stack.addLast(old to new)
        }

        override fun contentOf(uuid: BlockUuid) = content
        override suspend fun storedContent(uuid: BlockUuid) = stored
    }

    private class Rig(val port: FakePort, val sync: TableSyncPolicy, val committer: TableCommitter, val events: MutableList<CommitterEvent>)

    private fun TestScope.rig(start: String = "S0", entriesBeforeOpen: Int = 0): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = FakePort(CoroutineScope(dispatcher), start)
        repeat(entriesBeforeOpen) { port.foreignRecord() }
        val sync = TableSyncPolicy().also { it.registerLoaded(start) }
        val events = mutableListOf<CommitterEvent>()
        val committer = TableCommitter(uuid, port, sync, start, dispatcher, onEvent = { if (it !is CommitterEvent.Issued) events += it })
        return Rig(port, sync, committer, events)
    }

    @Test
    fun commits_should_WriteInOrderOneAtATime_When_TheFirstWriteIsDelayed() = runTest {
        val r = rig()
        r.port.gate = CompletableDeferred()
        r.committer.commit("S1", "edit A1")
        r.committer.commit("S2", "edit A2")
        advanceUntilIdle()
        assertEquals(emptyList(), r.port.writes, "both writes wait behind the gate")
        r.port.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("S1", "S2"), r.port.writes)
        assertEquals("S2", r.port.content)
        assertEquals(2, r.committer.history.value.undoDepth)
    }

    @Test
    fun commit_should_BeSkipped_When_TheStringEqualsCurrentContent() = runTest {
        val r = rig()
        r.committer.commit("S0", "edit A1")
        r.committer.commit("S1", "edit A1")
        r.committer.commit("S1", "edit A1")
        advanceUntilIdle()
        assertEquals(listOf("S1"), r.port.writes)
        assertEquals(1, r.committer.history.value.undoDepth)
    }

    @Test
    fun versions_should_StrictlyIncrease_AcrossCommits() = runTest {
        val r = rig()
        listOf("S1", "S2", "S3").forEach { r.committer.commit(it, "edit") }
        advanceUntilIdle()
        assertEquals(r.port.versions.sorted().distinct(), r.port.versions)
    }

    @Test
    fun undo_should_NotCallTheManager_When_OnlyAnUnrelatedEntryIsOnTheStack() = runTest {
        val r = rig(entriesBeforeOpen = 1) // E, recorded before the sheet opened
        r.committer.undo()
        advanceUntilIdle()
        assertEquals(0, r.port.undoCalls)
        assertEquals(listOf<CommitterEvent>(CommitterEvent.NothingToUndo), r.events)
        assertEquals(1, r.port.stack.size, "E is still on the stack")
    }

    @Test
    fun undo_should_CallTheManagerTwice_When_TwoCommitsAndThreeUndos() = runTest {
        val r = rig()
        r.committer.commit("S1", "edit A1")
        r.committer.commit("S2", "edit A2")
        repeat(3) { r.committer.undo() }
        advanceUntilIdle()
        assertEquals(2, r.port.undoCalls)
        assertEquals("S0", r.port.content)
        assertEquals(CommitterEvent.NothingToUndo, r.events.last())
        assertEquals(2, r.committer.history.value.redoDepth)
    }

    @Test
    fun undo_should_JoinTheInFlightCommit_BeforeReadingDepth() = runTest {
        val r = rig()
        r.port.gate = CompletableDeferred()
        r.committer.commit("S1", "edit A1")
        r.committer.undo()
        advanceUntilIdle()
        assertEquals(0, r.port.undoCalls, "undo waits for the commit")
        r.port.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("S0", r.port.content)
        assertEquals(1, r.port.undoCalls)
        assertTrue(r.events.none { it is CommitterEvent.MustClose })
    }

    @Test
    fun depth_should_BeClampedTo100_When_101CommitsAreMade() = runTest {
        val r = rig()
        repeat(101) { r.committer.commit("S${it + 1}", "edit") }
        advanceUntilIdle()
        assertEquals(100, r.committer.history.value.undoDepth)
        repeat(101) { r.committer.undo() }
        advanceUntilIdle()
        assertEquals(100, r.port.undoCalls)
        assertEquals(CommitterEvent.NothingToUndo, r.events.last())
    }

    @Test
    fun undo_should_BeExpectedByTheLedger_So_TheEchoIsAdopted() = runTest {
        val r = rig()
        r.committer.commit("S1", "edit A1")
        advanceUntilIdle()
        r.committer.undo()
        advanceUntilIdle()
        assertEquals(SyncDecision.Adopt("S0"), r.sync.classify("S0"))
    }

    @Test
    fun foreignEntry_should_CloseTheSheetAndNotBeUndone_When_TheCounterRisesWhileIdle() = runTest {
        val r = rig()
        r.committer.commit("S1", "edit A1")
        advanceUntilIdle()
        r.port.foreignRecord()
        r.committer.onUndoRecordCount(r.port.records.value)
        r.committer.undo()
        advanceUntilIdle()
        assertEquals(CommitterEvent.MustClose(SheetCloseReason.ForeignEntry), r.events.single())
        assertEquals(0, r.port.undoCalls)
    }

    @Test
    fun foreignEntry_should_BeDetectedAtTheNextOperation_When_NoObserverReportedIt() = runTest {
        val r = rig()
        r.port.foreignRecord()
        r.committer.commit("S1", "edit A1")
        advanceUntilIdle()
        assertEquals(CommitterEvent.MustClose(SheetCloseReason.ForeignEntry), r.events.single())
        assertEquals(emptyList(), r.port.writes)
    }

    @Test
    fun commitFailure_should_RaiseCommitFailedAndRetryReissueWithANewSeq_When_TheWriteDidNotPersist() = runTest {
        val r = rig()
        r.port.persist = false
        r.committer.commit("S1", "edit A1", DraftText(dev.stapler.stelekit.table.CellAddress(2, 1), "x"))
        advanceUntilIdle()
        val failed = assertIs<CommitterEvent.CommitFailed>(r.events.single())
        assertEquals("S1", failed.text)
        assertEquals(1, r.committer.history.value.undoDepth, "the manager recorded an entry for the in-memory change, so the sheet counts it")
        r.port.persist = true
        r.committer.retry()
        advanceUntilIdle()
        assertEquals("S1", r.port.stored)
        assertEquals("S1", r.port.content)
        assertEquals(3, r.committer.history.value.undoDepth, "failed entry plus the retry's restore and apply steps")
        assertEquals(1, r.events.size, "no second failure")
    }

    @Test
    fun undo_should_CloseTheSheet_When_TheRawEditorOpensAsAResult() = runTest {
        val r = rig()
        r.committer.commit("S1", "edit A1")
        advanceUntilIdle()
        r.port.openEditorOnUndo = true
        r.committer.undo()
        advanceUntilIdle()
        assertEquals(CommitterEvent.MustClose(SheetCloseReason.Degraded), r.events.last())
    }

    @Test
    fun foreignContent_should_ResetUndoSteps() = runTest {
        val r = rig()
        r.committer.commit("S1", "edit A1")
        advanceUntilIdle()
        r.committer.foreignContent("F")
        assertEquals(0, r.committer.history.value.undoDepth)
    }

    @Test
    fun markDone_should_ReleaseHeldForeignContent_When_ACommitCompletes() = runTest {
        val r = rig()
        r.port.gate = CompletableDeferred()
        r.committer.commit("S1", "edit A1")
        advanceUntilIdle()
        assertEquals(SyncDecision.Defer, r.sync.classify("F")) // foreign emission while S1 is in flight
        r.port.gate!!.complete(Unit)
        advanceUntilIdle()
        val released = r.events.filterIsInstance<CommitterEvent.Released>().single()
        assertEquals("F", released.content)
        assertIs<SyncDecision.Rebuild>(released.decision)
    }
}
