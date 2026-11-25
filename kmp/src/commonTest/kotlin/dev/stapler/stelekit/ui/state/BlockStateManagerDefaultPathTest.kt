package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.Block
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
import kotlin.time.Clock

/**
 * Characterization pack for the default (restoreEditFocus = true) text-block path of
 * [BlockStateManager.updateBlockContent]; run before and after the Table undo change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockStateManagerDefaultPathTest {
    private val now = Clock.System.now()
    private val pageUuid = "default-path-page"
    private val uuid = BlockUuid("block-1")

    private class Rig(val blocks: InMemoryBlockRepository, val manager: BlockStateManager)

    private suspend fun TestScope.rig(content: String, version: Long): Rig {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, GraphLoader(FakeFileSystem(), pageRepo, blockRepo), scope)
        pageRepo.savePage(Page(uuid = PageUuid(pageUuid), name = "P", createdAt = now, updatedAt = now))
        blockRepo.saveBlock(
            Block(uuid = uuid, pageUuid = PageUuid(pageUuid), content = content, position = "a0",
                version = version, createdAt = now, updatedAt = now)
        )
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }
        return Rig(blockRepo, manager)
    }

    private fun BlockStateManager.content() = blocks.value[pageUuid]!!.single { it.uuid == uuid }.content

    @Test
    fun defaultPath_should_BehaveAsBefore_When_RestoreEditFocusTrue() = runTest {
        val m = rig("original", 0).manager

        // undo of a text-block edit restores content and re-enters edit mode at the end of the text
        m.updateBlockContent(uuid, "edited", 1)
        assertEquals("edited", m.content())
        m.undo()
        assertEquals("original", m.content())
        assertEquals(uuid, m.editingBlockUuid.value)
        assertEquals("original".length, m.editingCursorIndex.value)

        // redo re-applies and re-enters edit mode at the end of the new text
        m.redo()
        assertEquals("edited", m.content())
        assertEquals(uuid, m.editingBlockUuid.value)
        assertEquals("edited".length, m.editingCursorIndex.value)
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun defaultPath_should_ApplyEditAfterUndo_And_ClearRedo() = runTest {
        val m = rig("original", 0).manager
        m.updateBlockContent(uuid, "first", 1)
        m.undo()
        assertEquals("original", m.content())
        assertEquals(true, m.canRedo.value)

        m.updateBlockContent(uuid, "second", 2)
        assertEquals("second", m.content())
        assertEquals(true, m.canUndo.value)
        assertEquals(false, m.canRedo.value)
        m.undo()
        assertEquals("original", m.content())
        m.unobservePage(PageUuid(pageUuid))
    }

    @Test
    fun defaultPath_should_RejectLowerVersionEdit_When_NewerVersionApplied() = runTest {
        val m = rig("original", 0).manager
        m.updateBlockContent(uuid, "newer", 5)
        m.updateBlockContent(uuid, "stale", 3)
        assertEquals("newer", m.content())
        assertEquals(5L, m.blocks.value[pageUuid]!!.single { it.uuid == uuid }.version)
        m.unobservePage(PageUuid(pageUuid))
    }
}
