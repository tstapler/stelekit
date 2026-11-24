package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private suspend fun TestScope.manager(): BlockStateManager {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val m = BlockStateManager(blockRepo, GraphLoader(FakeFileSystem(), pageRepo, blockRepo), scope)
        pageRepo.savePage(Page(uuid = PageUuid(pageUuid), name = "P", createdAt = now, updatedAt = now))
        blockRepo.saveBlock(
            Block(uuid = uuid, pageUuid = PageUuid(pageUuid), content = c0, position = "a0",
                blockType = BlockType.Table, version = 1, createdAt = now, updatedAt = now)
        )
        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }
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
        val m = manager()
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
}
