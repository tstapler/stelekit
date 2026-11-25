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

    @Test
    fun duplicateOrLatePatch_should_NotOverwriteNewerLocalContent_When_NoWritePending() = runTest {
        val pushed = MutableSharedFlow<BlockUpdateEvent>(extraBufferCapacity = 10)
        val m = manager(swallowWrites = true, pushed = pushed)
        val c2 = "| a | b |\n|---|---|\n| 9 | 9 |"
        m.updateBlockContent(uuid, c1, 2, restoreEditFocus = false)
        m.updateBlockContent(uuid, c2, 3, restoreEditFocus = false)
        pushed.patch(c1)
        pushed.patch(c2)
        testScheduler.advanceUntilIdle()
        assertEquals(c2, m.content())
        pushed.patch(c1) // duplicate of an already-confirmed older write: no write is pending any more
        testScheduler.advanceUntilIdle()
        assertEquals(c2, m.content(), "a late duplicate of our own older write must not move the block backwards")
        pushed.patch(foreign) // a genuinely foreign write is still adopted afterwards
        testScheduler.advanceUntilIdle()
        assertEquals(foreign, m.content())
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
}
