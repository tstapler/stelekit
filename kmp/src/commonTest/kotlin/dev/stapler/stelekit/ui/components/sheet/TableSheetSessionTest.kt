// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.CellText
import dev.stapler.stelekit.table.TableEdit
import dev.stapler.stelekit.table.TableEditOutcome
import dev.stapler.stelekit.table.TableParseResult
import dev.stapler.stelekit.table.TableParser
import dev.stapler.stelekit.table.TableSerializer
import dev.stapler.stelekit.table.formula.TableEngine
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.PageUuid
import kotlin.time.Instant
import dev.stapler.stelekit.table.NoticeType
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `TableSheetSession` host policy against a scripted manager and a scripted body: what happens to a draft when a
 * commit fails, is dropped or the sheet is already gone, and the order of close callbacks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableSheetSessionTest {
    private val uuid = BlockUuid("b1")
    private val a1 = CellAddress(1, 1)
    private val start = "| A | B |\n| --- | --- |\n| 1 | 2 |\n"

    private class Port(private val scope: CoroutineScope, start: String) : TableManagerPort {
        var content: String? = start
        var stored: String? = start
        var persist = true
        var gate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<String>()
        val records = MutableStateFlow(0L)
        private val stack = ArrayDeque<String>()

        /** Runs inside the manager's undo, before it completes: where the feed's emission of the undone content lands. */
        var afterUndo: (String) -> Unit = {}
        override val undoRecordCount: StateFlow<Long> = records
        override val editingBlockUuid = MutableStateFlow<BlockUuid?>(null)

        override fun write(uuid: BlockUuid, text: String, version: Long): Job = scope.launch {
            gate?.await()
            val old = content
            writes += text
            if (old == text) return@launch
            if (old != null) stack.addLast(old)
            content = text
            if (persist) stored = text
            records.value += 1
        }

        override fun undo(): Job = scope.launch {
            val old = stack.removeLastOrNull() ?: return@launch
            content = old
            stored = old
            afterUndo(old)
        }
        override fun redo(): Job = scope.launch { }
        override fun contentOf(uuid: BlockUuid): String? = content
        override suspend fun storedContent(uuid: BlockUuid): String? = stored
    }

    private class Body(var dirty: DraftText? = null) : SheetBodyHooks {
        var onCommit: () -> Unit = {}
        val rebuilt = mutableListOf<String>()
        val restored = mutableListOf<DraftText>()
        override val hasDirtyDraft get() = dirty != null
        override val selectionAnchor: CellAddress? = null
        override fun dirtyDraft() = dirty
        override fun commitPendingDraft() = onCommit()
        override fun discardDraft() { dirty = null }
        override fun rebuild(content: String, keep: CellAddress?) { rebuilt += content }
        override fun restoreDraft(address: CellAddress, text: String) { restored += DraftText(address, text) }
    }

    private class Rig(
        val port: Port,
        val session: TableSheetSession,
        val body: Body,
        val closes: MutableList<SheetCloseReason>,
        val lost: MutableList<String>,
        val flushed: MutableList<Unit>,
    )

    private fun TestScope.rig(initial: String = start, readOnly: Boolean = false): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val port = Port(CoroutineScope(dispatcher), initial)
        val closes = mutableListOf<SheetCloseReason>()
        val lost = mutableListOf<String>()
        val flushed = mutableListOf<Unit>()
        val session = TableSheetSession(
            blockUuid = uuid,
            initialContent = initial,
            port = port,
            context = dispatcher,
            readOnly = readOnly,
            flushScope = CoroutineScope(dispatcher),
            flush = { flushed += Unit },
            onCloseRequest = { closes += it },
            onChangeLoss = { lost += it },
        )
        val body = Body()
        session.bind(body)
        return Rig(port, session, body, closes, lost, flushed)
    }

    private fun edit(content: String, address: CellAddress, text: String): String {
        val doc = (TableParser.parse(content) as TableParseResult.Parsed).doc
        val applied = TableEngine.editor().apply(doc, TableEdit.SetCell(address, CellText(text))) as TableEditOutcome.Applied
        return TableSerializer.serialize(applied.doc)
    }

    @Test
    fun aFailedCommit_should_GoToTheRecoveredStore_When_TheSheetClosedBeforeItFailed() = runTest {
        val r = rig()
        r.port.persist = false // the write lands in the manager but never in the DB
        val draft = DraftText(a1, "typed")
        val s1 = edit(start, a1, "typed")
        r.body.dirty = draft
        r.body.onCommit = { r.session.commit(s1, "edit A1", draft) }
        r.session.close(SheetCloseReason.User)
        advanceUntilIdle()
        assertEquals(listOf(RecoveredDraft(uuid, draft)), r.session.recovered.all)
        assertEquals(emptyList(), r.body.restored, "the body is gone: nothing may be restored into it")
    }

    @Test
    fun aFailedCommitWithoutADraft_should_TellTheWindow_When_TheSheetClosedBeforeItFailed() = runTest {
        val r = rig()
        r.port.persist = false
        r.session.commit(edit(start, a1, "x"), "structure", null)
        r.session.close(SheetCloseReason.User)
        advanceUntilIdle()
        assertEquals(1, r.lost.size)
        assertEquals(emptyList(), r.session.recovered.all)
    }

    @Test
    fun editAsMarkdown_should_WaitForTheQueuedCommits_BeforeAskingToOpenTheEditor() = runTest {
        val r = rig()
        r.port.gate = CompletableDeferred()
        val s1 = edit(start, a1, "typed")
        r.session.commit(s1, "edit A1", null)
        r.session.editAsMarkdown()
        advanceUntilIdle()
        assertEquals(emptyList(), r.closes, "the commit is still writing; the raw editor would open on the old text")
        r.port.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(SheetCloseReason.EditAsMarkdown), r.closes)
        assertEquals(s1, r.port.content)
    }

    @Test
    fun otherCloses_should_StillAskTheHostAtOnce() = runTest {
        val r = rig()
        r.port.gate = CompletableDeferred()
        r.session.commit(edit(start, a1, "typed"), "edit A1", null)
        r.session.close(SheetCloseReason.User)
        assertEquals(listOf(SheetCloseReason.User), r.closes)
    }

    @Test
    fun aQueuedCommit_should_NotOverwriteAnOutsideWrite_When_ItArrivesBehindAnInFlightCommit() = runTest {
        val r = rig()
        r.port.gate = CompletableDeferred()
        val s1 = edit(start, a1, "one")
        val draft = DraftText(CellAddress(2, 2), "two")
        r.session.commit(s1, "edit A1", null)
        r.session.commit(edit(s1, draft.address, "two"), "edit B2", draft)
        advanceUntilIdle()
        val foreign = edit(start, CellAddress(2, 1), "outside")
        r.session.onContent(foreign) // deferred: S1 is still in flight
        r.port.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(s1), r.port.writes, "the second commit was built on the table the outside write replaced")
        assertEquals(foreign, r.body.rebuilt.last())
        assertEquals(listOf(draft), r.body.restored)
    }

    @Test
    fun aQueuedCommit_should_NotOverwriteAHeldConflict_When_AnOutsideWriteMeetsADirtyDraft() = runTest {
        val r = rig()
        r.port.gate = CompletableDeferred()
        val s1 = edit(start, a1, "one")
        r.session.commit(s1, "edit A1", null)
        r.session.commit(edit(s1, CellAddress(2, 2), "two"), "edit B2", null)
        advanceUntilIdle()
        r.body.dirty = DraftText(a1, "unsaved")
        r.session.onContent(edit(start, CellAddress(2, 1), "outside"))
        r.port.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(r.session.conflictHeld)
        assertEquals(listOf(s1), r.port.writes, "the held foreign write must not be replaced while the banner is up")
    }

    @Test
    fun openingTheSheet_should_StayOpen_When_AnotherBlockIsInTheEditor() = runTest {
        val r = rig()
        r.session.onEditingBlock(BlockUuid("other"))
        assertFalse(r.session.closed)
        r.session.onEditingBlock(uuid)
        assertEquals(SheetCloseReason.EditorOpened, r.session.closeReason)
    }

    @Test
    fun applyRecovered_should_BuildOnTheQueuedCommits_When_ACommitIsStillRunning() = runTest {
        val r = rig()
        r.port.gate = CompletableDeferred()
        val s1 = edit(start, a1, "one")
        r.session.commit(s1, "edit A1", null)
        val item = RecoveredDraft(uuid, DraftText(CellAddress(2, 2), "back"))
        assertTrue(r.session.applyRecovered(item))
        r.port.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(s1, edit(s1, CellAddress(2, 2), "back")), r.port.writes)
        assertEquals(emptyList(), r.session.recovered.all)
    }

    @Test
    fun aReplacedNotice_should_NotKeepItsAction() {
        val notices = TableNotices()
        notices.withAction(NoticeType.Why, "first", "Fix") {}
        notices.withAction(NoticeType.Why, "second", "Fix") {}
        assertEquals("second", notices.visible?.text)
        assertEquals(1, notices.actionCount, "the replaced notice's lambda (and what it captured) is released")
    }

    @Test
    fun openSheet_should_LoadOnce_When_TappedTwiceBeforeTheLoadFinishes() = runTest {
        val load = CompletableDeferred<Block?>()
        var loads = 0
        val controller = TableSheetController(
            liveContent = { "live" },
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        ) { loads++; load.await() }
        controller.openSheet(uuid)
        controller.openSheet(uuid)
        advanceUntilIdle()
        load.complete(Block(uuid = uuid, pageUuid = PageUuid("p"), content = "stored", position = "a0", createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0)))
        advanceUntilIdle()
        assertEquals(1, loads)
        assertEquals("live", controller.open?.content, "the manager's copy can be ahead of the stored row")
    }

    @Test
    fun recoveredStore_should_KeepTheNewestDraftsAndCutHugeText() {
        val store = RecoveredDraftStore()
        repeat(RecoveredDraftStore.MAX_ITEMS + 5) { store.add(RecoveredDraft(uuid, DraftText(CellAddress(it + 1, 1), "t$it"))) }
        assertEquals(RecoveredDraftStore.MAX_ITEMS, store.all.size)
        assertEquals("t24", store.all.last().draft.text)
        assertEquals("t5", store.all.first().draft.text, "the oldest five were dropped")
        store.add(RecoveredDraft(uuid, DraftText(CellAddress(1, 2), "x".repeat(RecoveredDraftStore.MAX_TEXT_CHARS + 50))))
        assertEquals(RecoveredDraftStore.MAX_TEXT_CHARS, store.all.last().draft.text.length)
    }

    @Test
    fun escGate_should_SwallowOneDismiss_AfterAnEscapeKeyDown() {
        var now = 0L
        val gate = EscDismissGate { now }
        assertFalse(gate.swallowDismiss(), "Android Back is not an Esc key")
        gate.observe(isEscape = true, isDown = true)
        assertTrue(gate.swallowDismiss())
        assertFalse(gate.swallowDismiss(), "only the one dismiss that Esc caused")
        gate.observe(isEscape = true, isDown = true)
        gate.observe(isEscape = true, isDown = false)
        assertFalse(gate.swallowDismiss())
        gate.observe(isEscape = false, isDown = true)
        assertFalse(gate.swallowDismiss())
    }

    @Test
    fun theGrid_should_EndAtTheNewestCommit_When_AnUndoEchoRebuiltItBetweenTheCommitAndItsWrite() = runTest {
        val r = rig()
        val s1 = edit(start, a1, "one")
        r.session.commit(s1, "edit A1", null)
        advanceUntilIdle()
        r.port.afterUndo = { r.session.onContent(it) } // the undo's emission is seen before the re-commit registers
        r.session.undo()
        r.session.commit(s1, "edit A1", null)
        advanceUntilIdle()
        assertEquals(start, r.body.rebuilt.first(), "the undo's content was adopted")
        assertEquals(s1, r.body.rebuilt.last(), "and the re-commit put the grid back on the text it wrote")
        assertEquals(s1, r.port.content)
        assertEquals(s1, r.session.content)
    }

    @Test
    fun lateUndoEchoes_should_NotBeTreatedAsAnOutsideWrite_And_TheNextCommitIsWritten() = runTest {
        val r = rig()
        val s1 = edit(start, a1, "one")
        val s2 = edit(s1, a1, "two")
        r.session.commit(s1, "edit A1", null)
        r.session.commit(s2, "edit A1", null)
        r.session.undo()
        r.session.undo()
        advanceUntilIdle()
        assertEquals(2, r.session.history.value.redoDepth)
        val seen = mutableListOf<dev.stapler.stelekit.table.SyncDecision>()
        r.session.decisionObserver = { d, _ -> seen += d }
        r.session.onContent(s1) // the feed's late emission of the first undo target
        r.session.onContent(start) // and of the second
        assertEquals(listOf<dev.stapler.stelekit.table.SyncDecision>(dev.stapler.stelekit.table.SyncDecision.Adopt(s1), dev.stapler.stelekit.table.SyncDecision.Adopt(start)), seen)
        assertEquals(2, r.session.history.value.redoDepth, "the committer's own undo targets are not an outside write")
        val s3 = edit(start, a1, "three")
        val draft = DraftText(a1, "three")
        r.body.dirty = draft
        r.session.commit(s3, "edit A1", draft)
        advanceUntilIdle()
        assertEquals(emptyList(), r.body.restored, "the commit was not dropped")
        assertEquals(s3, r.port.content)
        assertEquals(0, r.session.history.value.redoDepth, "a new commit clears redo, so it ran")
    }

    @Test
    fun aCommitQueuedBeforeLateUndoEchoes_should_NotBeDropped() = runTest {
        val r = rig()
        val s1 = edit(start, a1, "one")
        r.session.commit(s1, "edit A1", null)
        r.session.undo()
        advanceUntilIdle()
        val s3 = edit(start, a1, "three")
        r.port.gate = CompletableDeferred()
        r.session.commit(s3, "edit A1", DraftText(a1, "three"))
        advanceUntilIdle()
        r.session.onContent(start) // the undo's late emission, while the commit waits
        r.port.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList(), r.body.restored)
        assertEquals(s3, r.port.content)
    }

    @Test
    fun escGate_should_Expire_When_TheKeyUpWasLost() {
        var now = 0L
        val gate = EscDismissGate { now }
        gate.observe(isEscape = true, isDown = true)
        now = EscDismissGate.ARMED_FOR_MS + 1
        assertFalse(gate.swallowDismiss(), "a Back long after the Esc must close the sheet")
        gate.observe(isEscape = true, isDown = true)
        now += EscDismissGate.ARMED_FOR_MS
        assertTrue(gate.swallowDismiss())
    }
}
