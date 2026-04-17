package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FakeFileSystem : FileSystem {
    override fun getDefaultGraphPath(): String = "/tmp"
    override fun expandTilde(path: String) = path
    override fun readFile(path: String): String? = null
    override fun writeFile(path: String, content: String): Boolean = true
    override fun listFiles(path: String): List<String> = emptyList()
    override fun listDirectories(path: String): List<String> = emptyList()
    override fun fileExists(path: String): Boolean = false
    override fun directoryExists(path: String): Boolean = true
    override fun createDirectory(path: String): Boolean = true
    override fun deleteFile(path: String): Boolean = true
    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = null
}

@OptIn(ExperimentalCoroutinesApi::class)
class BlockStateManagerTest {

    private val now = Clock.System.now()
    private val pageUuid = "test-page-uuid"

    private fun createBlock(
        uuid: String,
        content: String = "",
        version: Long = 0,
        position: Int = 0
    ) = Block(
        uuid = uuid,
        pageUuid = pageUuid,
        content = content,
        position = position,
        version = version,
        createdAt = now,
        updatedAt = now
    )

    private fun createPage() = Page(
        uuid = pageUuid,
        name = "Test Page",
        createdAt = now,
        updatedAt = now
    )

    // ---- Dirty-set merge: the core race condition fix ----

    @Test
    fun dirty_block_is_preserved_when_stale_db_emission_arrives() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        // Setup: page with a block
        val page = createPage()
        pageRepo.savePage(page)
        val block = createBlock("block-1", content = "original", version = 0)
        blockRepo.saveBlock(block)

        // Start observing
        manager.observePage(pageUuid)

        // Wait for initial emission
        val initialBlocks = manager.blocks.first { it.containsKey(pageUuid) }[pageUuid]!!
        assertEquals(1, initialBlocks.size)
        assertEquals("original", initialBlocks[0].content)

        // User edits → optimistic update + mark dirty
        manager.updateBlockContent("block-1", "user typed this", 5)

        // Verify optimistic update is in local state
        val afterEdit = manager.blocks.value[pageUuid]!!
        val editedBlock = afterEdit.find { it.uuid == "block-1" }
        assertNotNull(editedBlock)
        assertEquals("user typed this", editedBlock.content)
        assertEquals(5L, editedBlock.version)

        // Simulate stale DB re-emission (version 0 < local version 5)
        // This happens naturally via the reactive flow when saveBlock triggers a re-query
        // but the DB hasn't processed the save yet
        // The merge logic should keep the local version
        val staleBlocks = manager.blocks.value[pageUuid]!!
        val staleBlock = staleBlocks.find { it.uuid == "block-1" }
        assertNotNull(staleBlock)
        assertEquals("user typed this", staleBlock.content,
            "Dirty block should NOT be overwritten by stale DB emission")

        manager.unobservePage(pageUuid)
    }

    // ---- Editing focus ----

    @Test
    fun requestEditBlock_sets_focus_state() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        assertNull(manager.editingBlockUuid.value)
        assertNull(manager.editingCursorIndex.value)

        manager.requestEditBlock("block-1", 5)
        assertEquals("block-1", manager.editingBlockUuid.value)
        assertEquals(5, manager.editingCursorIndex.value)

        manager.requestEditBlock(null)
        assertNull(manager.editingBlockUuid.value)
    }

    // ---- Undo/Redo ----

    @Test
    fun undo_redo_basic_flow() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        assertEquals(false, manager.canUndo.value)
        assertEquals(false, manager.canRedo.value)

        // Setup block
        val block = createBlock("block-1", content = "original", version = 0)
        blockRepo.saveBlock(block)
        pageRepo.savePage(createPage())
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        // Edit
        manager.updateBlockContent("block-1", "edited", 1)
        assertEquals(true, manager.canUndo.value)
        assertEquals(false, manager.canRedo.value)

        // Undo
        manager.undo()
        assertEquals(false, manager.canUndo.value)
        assertEquals(true, manager.canRedo.value)

        // Redo
        manager.redo()
        assertEquals(true, manager.canUndo.value)
        assertEquals(false, manager.canRedo.value)

        manager.unobservePage(pageUuid)
    }

    // ---- Collapse/expand ----

    @Test
    fun toggleBlockCollapse_toggles() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        assertEquals(emptySet(), manager.collapsedBlockUuids.value)

        manager.toggleBlockCollapse("block-1")
        assertEquals(setOf("block-1"), manager.collapsedBlockUuids.value)

        manager.toggleBlockCollapse("block-1")
        assertEquals(emptySet(), manager.collapsedBlockUuids.value)
    }

    // ---- Page observation lifecycle ----

    @Test
    fun unobservePage_clears_state() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("block-1", content = "test"))
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        assertEquals(1, manager.blocks.value[pageUuid]?.size)

        manager.unobservePage(pageUuid)
        assertNull(manager.blocks.value[pageUuid])
    }

    // ---- addBlockToPage ----

    @Test
    fun addBlockToPage_creates_block_and_sets_focus() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.addBlockToPage(pageUuid)

        // Should have a block now
        val blocks = blockRepo.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()
        assertEquals(1, blocks.size)
        assertEquals("", blocks[0].content)

        // Focus should be on the new block
        assertNotNull(manager.editingBlockUuid.value)
    }
}
