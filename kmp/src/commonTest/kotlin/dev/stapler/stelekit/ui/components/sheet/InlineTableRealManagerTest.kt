// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.EntryProfile
import dev.stapler.stelekit.ui.components.TableUndoHost
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.ui.state.FakeFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Story 4.3 against a real `BlockStateManager` and in-memory repositories: undo stays in the table, the version
 * contract, and undo followed at once by an inline commit. The echo is fed by hand, in the order the feed delivers it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InlineTableRealManagerTest {
    private val now = Clock.System.now()
    private val pageUuid = "inline-real-page"
    private val uuid = BlockUuid("inline-table-1")
    private val c0 = "| Item | Qty |\n|---|---|\n| Apple | 3 |"
    private val c1 = "| Item | Qty |\n|---|---|\n| Apple | 45 |"

    private class Harness(val manager: BlockStateManager, val repo: InMemoryBlockRepository, val state: InlineTableState) {
        var localVersion = 1L
    }

    private suspend fun TestScope.harness(): Harness {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val m = BlockStateManager(blockRepo, GraphLoader(FakeFileSystem(), pageRepo, blockRepo), scope)
        pageRepo.savePage(Page(uuid = PageUuid(pageUuid), name = "P", createdAt = now, updatedAt = now))
        blockRepo.saveBlock(
            Block(uuid = uuid, pageUuid = PageUuid(pageUuid), content = c0, position = "a0", blockType = BlockType.Table, version = 1, createdAt = now, updatedAt = now),
        )
        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }
        testScheduler.advanceUntilIdle()
        lateinit var h: Harness
        val state = InlineTableState(c0) { text ->
            // BlockItem's seam: LocalTableCommitAction(uuid, text, localVersion) -> restoreEditFocus = false.
            h.manager.updateBlockContent(uuid, text, h.localVersion, restoreEditFocus = false)
        }
        h = Harness(m, blockRepo, state)
        state.undoHost = TableUndoHost(
            undo = { m.undo() },
            redo = { m.redo() },
            canUndo = m.canUndo,
            canRedo = m.canRedo,
            recordCount = m.undoRecordCount,
        )
        return h
    }

    private fun Harness.content() = manager.blocks.value[pageUuid]!!.single { it.uuid == uuid }.content
    private suspend fun Harness.stored() = repo.getBlockByUuid(uuid).first().getOrNull()!!

    private fun InlineTableState.qty() = doc!!.cell(CellAddress(2, 2))!!.value

    private fun InlineTableState.commit(address: CellAddress, text: String) {
        startEdit(address, EntryProfile.Touch)
        onFieldChange(TextFieldValue(text, TextRange(text.length)))
        commitAndLeaveEdit()
    }

    @Test
    fun undo_should_ShowPreviousValueInTheGridAndNeverOpenTheRawEditor_AndRedoRestores() = runTest {
        val h = harness()
        h.state.commit(CellAddress(2, 2), "45")
        h.state.onContent(h.content()) // echo
        assertEquals("45", h.state.qty())
        h.manager.undo()
        h.state.onContent(h.content())
        assertEquals("3", h.state.qty(), "the grid follows an undo issued outside the table")
        assertNull(h.manager.editingBlockUuid.value)
        assertEquals(CellAddress(2, 2), h.state.selection?.anchor)
        h.manager.redo()
        h.state.onContent(h.content())
        assertEquals("45", h.state.qty())
        assertNull(h.manager.editingBlockUuid.value)
        h.manager.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun undoBeforeTheCommitEchoIsObserved_should_StillShowPreviousValue_WhenUndoIssuedOutsideTheTable() = runTest {
        val h = harness()
        h.state.commit(CellAddress(2, 2), "45")
        h.manager.undo() // the conflated feed never shows the host the intermediate c1
        h.state.onContent(h.content())
        assertEquals("3", h.state.qty(), "otherwise the grid keeps 45 while the DB holds 3 (P5-5, Stale)")
        h.manager.redo()
        h.state.onContent(h.content())
        assertEquals("45", h.state.qty())
        h.manager.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun undoBeforeTheCommitEchoIsObserved_should_StillShowPreviousValue_WhenUndoIsTheStripButton() = runTest {
        val h = harness()
        h.state.commit(CellAddress(2, 2), "45")
        h.state.onUndoRecordCount(h.manager.undoRecordCount.value)
        h.state.perform(StripAction.Undo)
        h.state.onContent(h.content())
        assertEquals("3", h.state.qty())
        h.manager.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun stripUndo_should_ShowPreviousValue_AndRedoRestores() = runTest {
        val h = harness()
        h.state.commit(CellAddress(2, 2), "45")
        h.state.onContent(h.content())
        h.state.onUndoRecordCount(h.manager.undoRecordCount.value)
        h.state.perform(StripAction.Undo)
        h.state.onContent(h.content())
        assertEquals("3", h.state.qty())
        assertNull(h.manager.editingBlockUuid.value)
        h.state.perform(StripAction.Redo)
        h.state.onContent(h.content())
        assertEquals("45", h.state.qty())
        h.manager.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun undoFollowedByInlineCommit_should_LeaveDbEqualToGrid_When_TheUndoEmissionArrivesLate() = runTest {
        val h = harness()
        h.state.commit(CellAddress(2, 2), "45")
        h.state.onContent(h.content())
        h.state.onUndoRecordCount(h.manager.undoRecordCount.value)
        h.state.perform(StripAction.Undo) // registers the expected target; the emission has not reached the grid yet
        h.state.commit(CellAddress(2, 1), "Pear")
        val committed = h.content()
        h.state.onContent(c0) // the undo's emission, now late
        h.state.onContent(committed)
        assertEquals(committed, h.stored().content, "DB holds the last write")
        assertEquals("Pear", h.state.doc!!.cell(CellAddress(2, 1))!!.value)
        assertEquals(committed, h.content())
        h.manager.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun storedVersions_should_StrictlyIncrease_AcrossInlineSheetStyleAndInlineCommits() = runTest {
        val h = harness()
        val seen = mutableListOf<Long>()
        h.state.commit(CellAddress(2, 2), "4")
        h.state.onContent(h.content())
        seen += h.stored().version
        // A committer that passes a stale lower bound (the sheet's own counter): the manager still mints a higher one.
        h.manager.updateBlockContent(uuid, "| Item | Qty |\n|---|---|\n| Apple | 7 |", 1, restoreEditFocus = false)
        h.state.onContent(h.content())
        seen += h.stored().version
        h.state.commit(CellAddress(2, 2), "8")
        h.state.onContent(h.content())
        seen += h.stored().version
        assertEquals(seen.sorted().distinct(), seen, "strictly increasing: $seen")
        assertTrue(seen.first() > 1)
        assertEquals("8", h.state.qty())
        h.manager.unobservePage(PageUuid(pageUuid))
    }
}
