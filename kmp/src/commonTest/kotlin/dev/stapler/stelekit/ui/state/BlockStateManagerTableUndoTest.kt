package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import dev.stapler.stelekit.db.BlockUpdateEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class BlockStateManagerTableUndoTest {
    private val now = Clock.System.now()
    private val pageUuid = "table-undo-page"
    private val uuid = BlockUuid("table-1")
    private val c0 = "| a | b |\n|---|---|\n| 1 | 2 |"
    private val c1 = "| a | b |\n|---|---|\n| 1 | 3 |"

    // Swallows content writes so the repository flow cannot re-deliver content and mask the
    // in-memory version guard under test.
    private class SwallowContentWrites(private val d: BlockRepository) : BlockRepository by d {
        @OptIn(DirectRepositoryWrite::class)
        override suspend fun updateBlockContentOnly(blockUuid: BlockUuid, content: String): Either<DomainError, Unit> = Unit.right()
    }

    private suspend fun TestScope.manager(
        swallowWrites: Boolean = false,
        pushed: MutableSharedFlow<BlockUpdateEvent>? = null,
        blockType: BlockType = BlockType.Table,
        content: String = c0,
    ): BlockStateManager {
        val backing = InMemoryBlockRepository()
        val blockRepo: BlockRepository = if (swallowWrites) SwallowContentWrites(backing) else backing
        val pageRepo = InMemoryPageRepository()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val m = BlockStateManager(blockRepo, GraphLoader(FakeFileSystem(), pageRepo, blockRepo), scope,
            invalidationSource = pushed?.let { MutableSharedFlow<Set<PageUuid>>(extraBufferCapacity = 10) },
            pushSource = pushed)
        pageRepo.savePage(Page(uuid = PageUuid(pageUuid), name = "P", createdAt = now, updatedAt = now))
        backing.saveBlock(
            Block(uuid = uuid, pageUuid = PageUuid(pageUuid), content = content, position = "a0",
                blockType = blockType, version = 1, createdAt = now, updatedAt = now)
        )
        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }
        testScheduler.advanceUntilIdle() // the push collector must be subscribed before a test emits
        return m
    }

    private fun BlockStateManager.content() = blocks.value[pageUuid]!!.single { it.uuid == uuid }.content

    @Test
    fun undo_should_RestoreContentAndKeepEditingNull_When_TableCommitWithRestoreEditFocusFalse() = runTest {
        val m = manager()
        m.updateBlockContent(uuid, c1, 2, restoreEditFocus = false)
        assertEquals(c1, m.content())
        m.undo()
        assertEquals(c0, m.content())
        assertNull(m.editingBlockUuid.value)
        m.redo()
        assertEquals(c1, m.content())
        assertNull(m.editingBlockUuid.value)
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun commit_should_BeApplied_When_StaleLowerBoundVersionAfterUndo() = runTest {
        val m = manager(swallowWrites = true)
        val c2 = "| a | b |\n|---|---|\n| 9 | 9 |"
        m.updateBlockContent(uuid, c1, 2, restoreEditFocus = false)
        m.undo()
        m.updateBlockContent(uuid, c2, 1, restoreEditFocus = false)
        assertEquals(c2, m.content())
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun commit_should_BeNoUndoEntry_When_SerializedEqualsCurrentContent() = runTest {
        val m = manager()
        val before = m.undoRecordCount.value
        m.updateBlockContent(uuid, c0, 5, restoreEditFocus = false)
        assertEquals(before, m.undoRecordCount.value)
        assertEquals(false, m.canUndo.value)
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun undoRecordCount_should_RiseByExactlyOnePerChangingCommitAndZeroForUnchanged() = runTest {
        val m = manager()
        val start = m.undoRecordCount.value
        m.updateBlockContent(uuid, c1, 2, restoreEditFocus = false)
        assertEquals(start + 1, m.undoRecordCount.value)
        m.updateBlockContent(uuid, c1, 3, restoreEditFocus = false)
        assertEquals(start + 1, m.undoRecordCount.value)
        m.updateBlockContent(uuid, c0, 4, restoreEditFocus = false)
        assertEquals(start + 2, m.undoRecordCount.value)
        assertEquals(true, m.canUndo.value)
        m.undo()
        m.undo()
        assertEquals(false, m.canUndo.value)
        assertEquals(true, m.canRedo.value)
        assertEquals(start + 2, m.undoRecordCount.value)
        m.unobservePage(PageUuid(pageUuid))
    }

    private fun MutableSharedFlow<BlockUpdateEvent>.patch(content: String) {
        tryEmit(BlockUpdateEvent.BlockContentPatched(PageUuid(pageUuid), uuid, content))
    }

    private val foreign = "| a | b |\n|---|---|\n| 7 | 8 |"

    @Test
    fun foreignTablePatch_should_BeAdopted_When_NoWriteIsPendingAndBlockIsNotBeingEdited() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 10)
        val m = manager(pushed = pushed)
        pushed.patch(foreign)
        testScheduler.advanceUntilIdle()
        assertEquals(foreign, m.content())
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun foreignTablePatch_should_BeIgnored_When_BlockIsBeingEditedInRawEditor() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 10)
        val m = manager(pushed = pushed)
        m.requestEditBlock(uuid, 0)
        pushed.patch(foreign)
        testScheduler.advanceUntilIdle()
        assertEquals(c0, m.content(), "the raw editor owns the content while it is open")
        m.stopEditingBlock(uuid)
        m.unobservePage(PageUuid(pageUuid))
    }

    private val c2 = "| a | b |\n|---|---|\n| 9 | 9 |"

    @Test
    fun externalRevertToAnOlderOwnWrite_should_BeAdopted_When_AllConfirmationsWereConsumed() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 10)
        val m = manager(swallowWrites = true, pushed = pushed)
        m.updateBlockContent(uuid, c1, 2, restoreEditFocus = false)
        m.updateBlockContent(uuid, c2, 3, restoreEditFocus = false)
        pushed.patch(c1)
        pushed.patch(c2)
        testScheduler.advanceUntilIdle()
        assertEquals(c2, m.content())
        pushed.patch(c1) // sync restored an older state we once wrote: nothing is outstanding, so it is foreign
        testScheduler.advanceUntilIdle()
        assertEquals(c1, m.content())
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun lateConfirmationOfAnOwnWrite_should_NotOverwriteNewerLocalContent_When_PendingCountAlreadyDrained() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 10)
        val m = manager(swallowWrites = true, pushed = pushed)
        m.updateBlockContent(uuid, c1, 2, restoreEditFocus = false)
        m.updateBlockContent(uuid, c2, 3, restoreEditFocus = false)
        pushed.patch(foreign) // consumes one pending slot although it is not one of our writes
        pushed.patch(foreign)
        testScheduler.advanceUntilIdle()
        pushed.patch(c1) // c1's confirmation is still outstanding: late, not foreign
        testScheduler.advanceUntilIdle()
        assertEquals(c2, m.content())
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun foreignEditSkippedWhileEditing_should_BeAdopted_When_EditingEndsWithoutLocalChange() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 10)
        val m = manager(pushed = pushed)
        m.requestEditBlock(uuid, 0)
        pushed.patch(foreign)
        testScheduler.advanceUntilIdle()
        assertEquals(c0, m.content())
        m.stopEditingBlock(uuid)
        assertEquals(foreign, m.content(), "otherwise a later commit equal to the stale copy would be dropped")
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun foreignEditSkippedWhileEditing_should_BeDropped_When_UserWroteDuringEditing() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 10)
        val m = manager(swallowWrites = true, pushed = pushed)
        m.requestEditBlock(uuid, 0)
        pushed.patch(foreign)
        m.updateBlockContent(uuid, c2, 2, restoreEditFocus = false)
        testScheduler.advanceUntilIdle()
        m.stopEditingBlock(uuid)
        assertEquals(c2, m.content(), "the user's later text is the last writer")
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun foreignPatch_should_BeIgnored_When_BlockIsNotATable() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 10)
        val m = manager(pushed = pushed, blockType = BlockType.Bullet, content = "plain text")
        pushed.patch("other text")
        testScheduler.advanceUntilIdle()
        assertEquals("plain text", m.content(), "non-table blocks keep ignoring patches they did not issue")
        m.unobservePage(PageUuid(pageUuid))
    }

    private fun tbl(n: Int) = "| a | b |\n|---|---|\n| $n | $n |"

    @Test
    fun heldForeignEdit_should_NotBeAdopted_When_UserTypedAndRestoredTheOriginalText() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 10)
        val m = manager(swallowWrites = true, pushed = pushed)
        val typed = "| a | b |\n|---|---|\n| 1 | 2x |"
        m.requestEditBlock(uuid, 0)
        pushed.patch(foreign) // DB now holds the foreign edit; the editor keeps showing c0
        testScheduler.advanceUntilIdle()
        m.updateBlockContent(uuid, typed, 2, restoreEditFocus = false) // DB = typed
        m.updateBlockContent(uuid, c0, 3, restoreEditFocus = false) // backspace: DB = c0, memory = c0
        pushed.patch(typed)
        pushed.patch(c0)
        testScheduler.advanceUntilIdle()
        m.stopEditingBlock(uuid)
        assertEquals(c0, m.content(), "the DB holds c0, so adopting the older foreign edit would desync memory from it")
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun heldForeignEdit_should_BeDropped_When_ALaterForeignPatchEqualsLocalContent() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 10)
        val m = manager(pushed = pushed)
        m.requestEditBlock(uuid, 0)
        pushed.patch(foreign)
        pushed.patch(c0) // the other writer restored c0: the DB equals what the editor shows
        testScheduler.advanceUntilIdle()
        m.stopEditingBlock(uuid)
        assertEquals(c0, m.content())
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun lateOwnConfirmation_should_StayOwn_When_MoreThanSixteenContentsWereWritten() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 64)
        val m = manager(swallowWrites = true, pushed = pushed)
        for (i in 1..17) m.updateBlockContent(uuid, tbl(i), i.toLong() + 1, restoreEditFocus = false)
        repeat(17) { pushed.patch(foreign) } // drains the pending count without confirming any of our writes
        testScheduler.advanceUntilIdle()
        pushed.patch(tbl(16))
        testScheduler.advanceUntilIdle()
        assertEquals(tbl(17), m.content(), "c16's confirmation is still outstanding: late, not foreign")
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun outstandingEntry_should_NotSwallowALaterForeignWrite_When_ItsConfirmationNeverArrived() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 64)
        val m = manager(swallowWrites = true, pushed = pushed)
        m.updateBlockContent(uuid, c1, 2, restoreEditFocus = false)
        pushed.patch(foreign) // drains pending; c1's confirmation was lost (tryEmit drops when the buffer is full)
        testScheduler.advanceUntilIdle()
        val other = tbl(5)
        pushed.patch(other) // a genuinely foreign write lands afterwards
        testScheduler.advanceUntilIdle()
        assertEquals(other, m.content())
        pushed.patch(c1) // and a later write equal to the lost one's content is foreign too
        testScheduler.advanceUntilIdle()
        assertEquals(c1, m.content())
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun proseWithPipe_should_NotBeTracked_When_BlockIsNotATable() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 10)
        val m = manager(swallowWrites = true, pushed = pushed, blockType = BlockType.Bullet, content = "x")
        m.updateBlockContent(uuid, "a | b", 2, restoreEditFocus = false)
        pushed.patch("a | b")
        testScheduler.advanceUntilIdle()
        assertEquals("a | b", m.content())
        m.unobservePage(PageUuid(pageUuid))
    }
}
