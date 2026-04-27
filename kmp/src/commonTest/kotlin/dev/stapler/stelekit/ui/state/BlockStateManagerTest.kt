package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

/** Counts readFile calls to detect when GraphLoader.loadFullPage reaches the disk-read step. */
class CountingFakeFileSystem : FileSystem {
    var readFileCallCount = 0
        private set

    override fun getDefaultGraphPath(): String = "/tmp"
    override fun expandTilde(path: String) = path
    override fun readFile(path: String): String? { readFileCallCount++; return null }
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

    private fun createPage(filePath: String? = null) = Page(
        uuid = pageUuid,
        name = "Test Page",
        filePath = filePath,
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
    fun unobservePage_stops_observation_and_keeps_cache() = runTest {
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

        // unobservePage cancels the observation job but keeps blocks cached for fast re-navigation
        manager.unobservePage(pageUuid)
        assertEquals(1, manager.blocks.value[pageUuid]?.size)
    }

    // ---- Cache persistence and disk-load suppression ----

    /**
     * Regression test for the production bug where individual pages had to reload from disk
     * on every navigation. After [unobservePage], blocks remain in [BlockStateManager._blocks]
     * as an in-memory cache. Re-navigating with [isContentLoaded]=false must skip [GraphLoader.loadFullPage]
     * when blocks are already cached, so the user sees content instantly without a disk round-trip.
     */
    @Test
    fun re_navigation_skips_disk_load_when_blocks_are_cached() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val fileSystem = CountingFakeFileSystem()
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        // Page with a known filePath so loadFullPage would reach readFile if it were called
        pageRepo.savePage(createPage(filePath = "/fake/test.md"))
        blockRepo.saveBlock(createBlock("block-1", content = "hello"))

        // First navigation: default isContentLoaded=true, no disk load attempted
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }
        assertEquals(1, manager.blocks.value[pageUuid]?.size)

        // Unobserve: job cancelled but blocks stay in _blocks as cache
        manager.unobservePage(pageUuid)
        assertEquals(1, manager.blocks.value[pageUuid]?.size, "blocks must remain cached after unobservePage")

        val readCallsBefore = fileSystem.readFileCallCount

        // Re-navigation with isContentLoaded=false (as happens when PageView navigates to a non-journal)
        manager.observePage(pageUuid, isContentLoaded = false)

        // alreadyCached=true → loadFullPage skipped → readFile never called
        assertEquals(readCallsBefore, fileSystem.readFileCallCount,
            "readFile must not be called when blocks are already cached in _blocks")
        assertNotNull(manager.blocks.value[pageUuid], "cached blocks must still be accessible after re-navigation")

        manager.unobservePage(pageUuid)
    }

    /**
     * Complementary test: when [_blocks] has no entry for a page and [isContentLoaded]=false,
     * [observePage] must call [GraphLoader.loadFullPage] so the page is fetched from disk.
     */
    @Test
    fun first_navigation_triggers_disk_load_when_content_not_loaded() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val fileSystem = CountingFakeFileSystem()
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        // Page with filePath but no blocks in repo or in _blocks (first-time navigation)
        pageRepo.savePage(createPage(filePath = "/fake/test.md"))

        assertEquals(0, fileSystem.readFileCallCount)

        // Navigate for the first time with isContentLoaded=false
        manager.observePage(pageUuid, isContentLoaded = false)

        // loadFullPage must have been called: it finds the filePath, sees no loaded blocks,
        // and calls readFile (which returns null from CountingFakeFileSystem but the call is recorded)
        assertEquals(1, fileSystem.readFileCallCount,
            "readFile must be called on first navigation with isContentLoaded=false and no cached blocks")

        manager.unobservePage(pageUuid)
    }

    /**
     * Verifies that [unobservePage] cancels the DB observation job while keeping the blocks
     * in the in-memory cache — the contract that makes [re_navigation_skips_disk_load_when_blocks_are_cached] work.
     */
    @Test
    fun unobservePage_cancels_observation_but_keeps_blocks_as_cache() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1"))
        blockRepo.saveBlock(createBlock("b2", position = 1))

        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }
        assertEquals(2, manager.blocks.value[pageUuid]?.size)

        manager.unobservePage(pageUuid)

        // Blocks must remain in cache during keepalive window
        assertEquals(2, manager.blocks.value[pageUuid]?.size,
            "blocks must not be cleared from _blocks after unobservePage")

        // Advance virtual time past the 5-second keepalive so the observation is actually cancelled
        testScheduler.advanceTimeBy(5_001L)

        // After keepalive expires, new DB changes must NOT update the stale cache
        blockRepo.saveBlock(createBlock("b3", position = 2))
        assertEquals(2, manager.blocks.value[pageUuid]?.size,
            "DB changes after unobservePage must not update the stale cache entry")
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

    // ── Pending disk write tracking ───────────────────────────────────────────

    /**
     * A minimal tracking filesystem for disk-write tests.
     * Records paths that were written to disk so tests can assert the disk write
     * did or did not fire.
     */
    private class TrackingFileSystem : FileSystem {
        val writtenPaths = mutableListOf<String>()
        override fun getDefaultGraphPath(): String = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean {
            writtenPaths.add(path)
            return true
        }
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String): Boolean = true
        override fun directoryExists(path: String): Boolean = true
        override fun createDirectory(path: String): Boolean = true
        override fun deleteFile(path: String): Boolean = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long = 1000L
    }

    private fun createPageWithFilePath(filePath: String) = Page(
        uuid = pageUuid,
        name = "Test Page",
        filePath = filePath,
        createdAt = now,
        updatedAt = now
    )

    @Test
    fun hasPendingDiskWrite_returns_false_when_no_save_queued() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val writer = GraphWriter(TrackingFileSystem())
        writer.startAutoSave(scope)
        val manager = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = scope,
            graphWriter = writer,
            pageRepository = pageRepo,
            graphPathProvider = { "/graph" }
        )

        assertFalse(manager.hasPendingDiskWrite(pageUuid), "No pending write initially")
    }

    @Test
    fun hasPendingDiskWrite_returns_true_after_block_edit_before_debounce_fires() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val writer = GraphWriter(TrackingFileSystem())
        writer.startAutoSave(scope)
        val manager = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = scope,
            graphWriter = writer,
            pageRepository = pageRepo,
            graphPathProvider = { "/graph" }
        )

        pageRepo.savePage(createPageWithFilePath("/graph/pages/test-page.md"))
        blockRepo.saveBlock(createBlock("b1", content = "original"))
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        // Edit triggers queueDiskSave which schedules a debounced write
        manager.updateBlockContent("b1", "edited", 1)
        // Advance just enough for the outer scope.launch to register the job, not past the delay
        advanceTimeBy(10)

        assertTrue(manager.hasPendingDiskWrite(pageUuid),
            "Pending disk write must be visible before debounce delay fires")
    }

    @Test
    fun hasPendingDiskWrite_returns_false_after_debounce_fires() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val writer = GraphWriter(TrackingFileSystem())
        writer.startAutoSave(scope)
        val manager = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = scope,
            graphWriter = writer,
            pageRepository = pageRepo,
            graphPathProvider = { "/graph" }
        )

        pageRepo.savePage(createPageWithFilePath("/graph/pages/test-page.md"))
        blockRepo.saveBlock(createBlock("b1", content = "original"))
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.updateBlockContent("b1", "edited", 1)
        advanceTimeBy(10)
        assertTrue(manager.hasPendingDiskWrite(pageUuid), "Should be pending before delay")

        // Advance past the debounce delay (DebounceManager uses 300ms for BSM)
        advanceTimeBy(400)
        advanceUntilIdle()

        assertFalse(manager.hasPendingDiskWrite(pageUuid),
            "Must not be pending after debounce fires")
    }

    @Test
    fun cancelPendingDiskSave_prevents_disk_write_from_firing() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val trackingFs = TrackingFileSystem()
        val writer = GraphWriter(trackingFs)
        writer.startAutoSave(scope)
        val manager = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = scope,
            graphWriter = writer,
            pageRepository = pageRepo,
            graphPathProvider = { "/graph" }
        )

        pageRepo.savePage(createPageWithFilePath("/graph/pages/test-page.md"))
        blockRepo.saveBlock(createBlock("b1", content = "original"))
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.updateBlockContent("b1", "edited", 1)
        advanceTimeBy(10)
        assertTrue(manager.hasPendingDiskWrite(pageUuid), "Should be pending before cancel")

        // Cancel the pending write (simulates showing the conflict dialog)
        manager.cancelPendingDiskSave(pageUuid)
        assertFalse(manager.hasPendingDiskWrite(pageUuid), "Must not be pending after cancel")

        // Advance well past the original debounce delay — write must not fire
        advanceTimeBy(1000)
        advanceUntilIdle()

        assertTrue(trackingFs.writtenPaths.isEmpty(),
            "Cancelled disk write must not fire even after the debounce delay has passed")
    }

    @Test
    fun cancelPendingDiskSave_does_not_affect_other_page_writes() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val trackingFs = TrackingFileSystem()
        val writer = GraphWriter(trackingFs)
        writer.startAutoSave(scope)

        val otherPageUuid = "other-page-uuid"
        val manager = BlockStateManager(
            blockRepository = blockRepo,
            graphLoader = graphLoader,
            scope = scope,
            graphWriter = writer,
            pageRepository = pageRepo,
            graphPathProvider = { "/graph" }
        )

        // Set up two pages
        pageRepo.savePage(createPageWithFilePath("/graph/pages/page-a.md"))
        pageRepo.savePage(Page(
            uuid = otherPageUuid,
            name = "Other Page",
            filePath = "/graph/pages/page-b.md",
            createdAt = now,
            updatedAt = now
        ))
        val blockB = Block(
            uuid = "block-b",
            pageUuid = otherPageUuid,
            content = "other page content",
            position = 0,
            createdAt = now,
            updatedAt = now
        )
        blockRepo.saveBlock(createBlock("block-a", content = "page a content"))
        blockRepo.saveBlock(blockB)
        manager.observePage(pageUuid)
        manager.observePage(otherPageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }
        manager.blocks.first { it.containsKey(otherPageUuid) }

        // Queue writes for both pages
        manager.updateBlockContent("block-a", "edited a", 1)
        manager.updateBlockContent("block-b", "edited b", 1)
        advanceTimeBy(10)

        assertTrue(manager.hasPendingDiskWrite(pageUuid))
        assertTrue(manager.hasPendingDiskWrite(otherPageUuid))

        // Cancel only the first page's write
        manager.cancelPendingDiskSave(pageUuid)

        assertFalse(manager.hasPendingDiskWrite(pageUuid), "Cancelled page must not be pending")
        assertTrue(manager.hasPendingDiskWrite(otherPageUuid),
            "Unrelated page's write must still be pending")
    }
}
