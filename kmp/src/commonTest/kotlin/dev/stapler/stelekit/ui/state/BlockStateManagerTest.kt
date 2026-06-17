package dev.stapler.stelekit.ui.state

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

/**
 * Wraps [InMemoryBlockRepository] and counts every call to [getBlocksForPage].
 * Used to verify that [BlockStateManager.addBlockToPage] uses in-memory state
 * instead of issuing a new DB read when blocks are already cached.
 */
@OptIn(DirectRepositoryWrite::class)
private class CountingBlockRepository(
    val delegate: InMemoryBlockRepository = InMemoryBlockRepository()
) : BlockRepository by delegate {
    var getBlocksForPageCallCount: Int = 0
        private set

    override fun getBlocksForPage(pageUuid: PageUuid): Flow<Either<DomainError, List<Block>>> {
        getBlocksForPageCallCount++
        return delegate.getBlocksForPage(pageUuid)
    }
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
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = content,
        position = position,
        version = version,
        createdAt = now,
        updatedAt = now
    )

    private fun createPage(uuid: String = pageUuid, filePath: String? = null) = Page(
        uuid = PageUuid(uuid),
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
        manager.observePage(PageUuid(pageUuid))

        // Wait for initial emission
        val initialBlocks = manager.blocks.first { it.containsKey(pageUuid) }[pageUuid]!!
        assertEquals(1, initialBlocks.size)
        assertEquals("original", initialBlocks[0].content)

        // User edits → optimistic update + mark dirty
        manager.updateBlockContent(BlockUuid("block-1"), "user typed this", 5)

        // Verify optimistic update is in local state
        val afterEdit = manager.blocks.value[pageUuid]!!
        val editedBlock = afterEdit.find { it.uuid.value == "block-1" }
        assertNotNull(editedBlock)
        assertEquals("user typed this", editedBlock.content)
        assertEquals(5L, editedBlock.version)

        // Simulate stale DB re-emission (version 0 < local version 5).
        // Re-save the original (pre-edit) block to the repository — this triggers the
        // reactive flow with stale data, exercising the dirty-block merge guard.
        val originalBlock = block.copy(content = "original", version = 0)
        blockRepo.saveBlock(originalBlock)
        advanceUntilIdle()

        val staleBlocks = manager.blocks.value[pageUuid]!!
        val staleBlock = staleBlocks.find { it.uuid.value == "block-1" }
        assertNotNull(staleBlock)
        assertEquals("user typed this", staleBlock.content,
            "Dirty block should NOT be overwritten by stale DB emission")

        manager.unobservePage(PageUuid(pageUuid))
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

        manager.requestEditBlock(BlockUuid("block-1"), 5)
        assertEquals(BlockUuid("block-1"), manager.editingBlockUuid.value)
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
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // Edit
        manager.updateBlockContent(BlockUuid("block-1"), "edited", 1)
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

        manager.unobservePage(PageUuid(pageUuid))
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

        manager.toggleBlockCollapse(BlockUuid("block-1"))
        assertEquals(setOf("block-1"), manager.collapsedBlockUuids.value)

        manager.toggleBlockCollapse(BlockUuid("block-1"))
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
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        assertEquals(1, manager.blocks.value[pageUuid]?.size)

        // unobservePage cancels the observation job but keeps blocks cached for fast re-navigation
        manager.unobservePage(PageUuid(pageUuid))
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
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }
        assertEquals(1, manager.blocks.value[pageUuid]?.size)

        // Unobserve: job cancelled but blocks stay in _blocks as cache
        manager.unobservePage(PageUuid(pageUuid))
        assertEquals(1, manager.blocks.value[pageUuid]?.size, "blocks must remain cached after unobservePage")

        val readCallsBefore = fileSystem.readFileCallCount

        // Re-navigation with isContentLoaded=false (as happens when PageView navigates to a non-journal)
        manager.observePage(PageUuid(pageUuid), isContentLoaded = false)

        // alreadyCached=true → loadFullPage skipped → readFile never called
        assertEquals(readCallsBefore, fileSystem.readFileCallCount,
            "readFile must not be called when blocks are already cached in _blocks")
        assertNotNull(manager.blocks.value[pageUuid], "cached blocks must still be accessible after re-navigation")

        manager.unobservePage(PageUuid(pageUuid))
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
        manager.observePage(PageUuid(pageUuid), isContentLoaded = false)

        // loadFullPage must have been called: it finds the filePath, sees no loaded blocks,
        // and calls readFile (which returns null from CountingFakeFileSystem but the call is recorded)
        assertEquals(1, fileSystem.readFileCallCount,
            "readFile must be called on first navigation with isContentLoaded=false and no cached blocks")

        manager.unobservePage(PageUuid(pageUuid))
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

        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }
        assertEquals(2, manager.blocks.value[pageUuid]?.size)

        manager.unobservePage(PageUuid(pageUuid))

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
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.addBlockToPage(PageUuid(pageUuid))

        // Should have a block now
        val blocks = blockRepo.getBlocksForPage(PageUuid(pageUuid)).first().getOrNull() ?: emptyList()
        assertEquals(1, blocks.size)
        assertEquals("", blocks[0].content)

        // Focus should be on the new block
        assertNotNull(manager.editingBlockUuid.value)
    }

    /**
     * Regression test for BUG-008 root cause A.
     *
     * Before the fix, [BlockStateManager.addBlockToPage] called
     * `blockRepository.getBlocksForPage(pageUuid).first()` — a DB round-trip — to find
     * the last block's position even when blocks were already cached in [BlockStateManager._blocks].
     * After the fix it reads [BlockStateManager.blocksForPage] (in-memory), so
     * [BlockRepository.getBlocksForPage] must NOT be called again after the initial subscription
     * set up by [BlockStateManager.observePage].
     *
     * Fails against pre-fix code (counter increments twice) and passes after the fix (once).
     */
    @Test
    fun addBlockToPage_uses_in_memory_state_not_db_read() = runTest {
        val counting = CountingBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, counting)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(counting, graphLoader, scope)

        pageRepo.savePage(createPage())
        counting.delegate.saveBlock(createBlock("b1"))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        val countAfterObserve = counting.getBlocksForPageCallCount

        manager.addBlockToPage(PageUuid(pageUuid))
        advanceUntilIdle()

        assertEquals(
            countAfterObserve, counting.getBlocksForPageCallCount,
            "addBlockToPage must not call getBlocksForPage() — use in-memory blocksForPage() instead"
        )
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
        uuid = PageUuid(pageUuid),
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
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // Edit triggers queueDiskSave which schedules a debounced write
        manager.updateBlockContent(BlockUuid("b1"), "edited", 1)
        // Advance just enough for the outer scope.launch to register the job, not past the delay
        advanceTimeBy(10)

        assertTrue(manager.hasPendingDiskWrite(pageUuid),
            "Pending disk write must be visible before debounce delay fires")
    }

    @Test
    fun hasPendingDiskWrite_returns_true_in_graphWriter_window_after_bsm_debounce_fires() = runTest {
        // After the BSM 300ms debounce fires it calls graphWriter.queueSave, which starts its
        // own 500ms debounce. hasPendingDiskWrite must stay true for that entire second window
        // so an external change arriving then still shows the conflict dialog.
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
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.updateBlockContent(BlockUuid("b1"), "edited", 1)
        advanceTimeBy(10)
        assertTrue(manager.hasPendingDiskWrite(pageUuid), "Should be pending before BSM debounce")

        // Advance past BSM debounce (300ms) but stay inside GraphWriter window (500ms more)
        advanceTimeBy(400)

        assertTrue(manager.hasPendingDiskWrite(pageUuid),
            "Must still be pending in GraphWriter window (BSM fired, GraphWriter hasn't yet)")

        // Advance past GraphWriter debounce too
        advanceTimeBy(600)
        advanceUntilIdle()

        assertFalse(manager.hasPendingDiskWrite(pageUuid),
            "Must not be pending after both debounces have fired")
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
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.updateBlockContent(BlockUuid("b1"), "edited", 1)
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
            uuid = PageUuid(otherPageUuid),
            name = "Other Page",
            filePath = "/graph/pages/page-b.md",
            createdAt = now,
            updatedAt = now
        ))
        val blockB = Block(
            uuid = BlockUuid("block-b"),
            pageUuid = PageUuid(otherPageUuid),
            content = "other page content",
            position = 0,
            createdAt = now,
            updatedAt = now
        )
        blockRepo.saveBlock(createBlock("block-a", content = "page a content"))
        blockRepo.saveBlock(blockB)
        manager.observePage(PageUuid(pageUuid))
        manager.observePage(PageUuid(otherPageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }
        manager.blocks.first { it.containsKey(otherPageUuid) }

        // Queue writes for both pages
        manager.updateBlockContent(BlockUuid("block-a"), "edited a", 1)
        manager.updateBlockContent(BlockUuid("block-b"), "edited b", 1)
        advanceTimeBy(10)

        assertTrue(manager.hasPendingDiskWrite(pageUuid))
        assertTrue(manager.hasPendingDiskWrite(otherPageUuid))

        // Cancel only the first page's write
        manager.cancelPendingDiskSave(pageUuid)

        assertFalse(manager.hasPendingDiskWrite(pageUuid), "Cancelled page must not be pending")
        assertTrue(manager.hasPendingDiskWrite(otherPageUuid),
            "Unrelated page's write must still be pending")
    }

    @Test
    fun cancelPendingDiskSave_in_graphWriter_window_prevents_stale_write() = runTest {
        // Regression test for the "GraphWriter window" gap:
        // BSM debounce fires at T+300ms and calls graphWriter.queueSave() with the user's blocks.
        // An external change arriving in the subsequent 500ms graphWriter window would have been
        // auto-reloaded into the DB, but then graphWriter would fire at T+800ms and overwrite
        // with the stale pre-reload content. cancelPendingDiskSave must also cancel the
        // graphWriter job to prevent this.
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
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.updateBlockContent(BlockUuid("b1"), "edited", 1)

        // Advance past BSM debounce — graphWriter now has the pending write
        advanceTimeBy(400)

        assertTrue(manager.hasPendingDiskWrite(pageUuid),
            "GraphWriter window: must still be pending after BSM debounce fires")

        // Simulate conflict dialog showing: cancel
        manager.cancelPendingDiskSave(pageUuid)
        assertFalse(manager.hasPendingDiskWrite(pageUuid),
            "Must not be pending after cancel in GraphWriter window")

        // Advance past the GraphWriter debounce — write must not fire
        advanceTimeBy(600)
        advanceUntilIdle()

        assertTrue(trackingFs.writtenPaths.isEmpty(),
            "GraphWriter must not write after cancelPendingDiskSave called in GraphWriter window")
    }

    // ---- insertLinkAtCursor — overrideCursorIndex ----

    @Test
    fun insertLinkAtCursor_usesOverrideCursorIndex_whenEditingCursorIsNull() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        val block = createBlock("b1", content = "Hello world", version = 0)
        blockRepo.saveBlock(block)
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // Simulate: dialog opened, focus lost, cursor nulled
        manager.requestEditBlock(null)
        assertNull(manager.editingCursorIndex.value)

        // Insert at position 5 using override
        manager.insertLinkAtCursor(BlockUuid("b1"), "MyPage", overrideCursorIndex = 5)
        advanceUntilIdle()

        val updated = blockRepo.getBlockByUuid(BlockUuid("b1")).first().getOrNull()
        assertNotNull(updated)
        assertEquals("Hello[[MyPage]] world", updated.content,
            "Link should be inserted at position 5 (override), not at content.length")
    }

    @Test
    fun insertLinkAtCursor_fallsBackToContentLength_whenBothNull() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        val block = createBlock("b1", content = "Hi", version = 0)
        blockRepo.saveBlock(block)
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.requestEditBlock(null)  // cursor = null, no override
        manager.insertLinkAtCursor(BlockUuid("b1"), "Page")
        advanceUntilIdle()

        val updated = blockRepo.getBlockByUuid(BlockUuid("b1")).first().getOrNull()
        assertNotNull(updated)
        assertEquals("Hi[[Page]]", updated.content, "With no cursor info, link appended at end")
    }

    // ---- replaceSelectionWithLink ----

    @Test
    fun replaceSelectionWithLink_replacesSelectedText() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        val block = createBlock("b1", content = "Hello world today", version = 0)
        blockRepo.saveBlock(block)
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // Select "world" (positions 6..11)
        manager.replaceSelectionWithLink(BlockUuid("b1"), 6, 11, "World")
        advanceUntilIdle()

        val updated = blockRepo.getBlockByUuid(BlockUuid("b1")).first().getOrNull()
        assertNotNull(updated)
        assertEquals("Hello [[World]] today", updated.content,
            "Selected 'world' should be replaced with [[World]]")
    }

    @Test
    fun replaceSelectionWithLink_fallsBackToInsertWhenNoRealSelection() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        val block = createBlock("b1", content = "Hello world", version = 0)
        blockRepo.saveBlock(block)
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // selectionStart == selectionEnd → degenerate selection, falls back to insert
        manager.replaceSelectionWithLink(BlockUuid("b1"), 5, 5, "Page")
        advanceUntilIdle()

        val updated = blockRepo.getBlockByUuid(BlockUuid("b1")).first().getOrNull()
        assertNotNull(updated)
        assertEquals("Hello[[Page]] world", updated.content,
            "Degenerate selection should insert at cursor position, not replace")
    }

    @Test
    fun replaceSelectionWithLink_safelyHandlesOutOfBoundsSelection() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        val block = createBlock("b1", content = "Hi", version = 0)
        blockRepo.saveBlock(block)
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // Out-of-bounds end clamped to content length
        manager.replaceSelectionWithLink(BlockUuid("b1"), 0, 999, "Page")
        advanceUntilIdle()

        val updated = blockRepo.getBlockByUuid(BlockUuid("b1")).first().getOrNull()
        assertNotNull(updated)
        assertEquals("[[Page]]", updated.content, "Out-of-bounds selection clamped to content length")
    }

    // ---- activePageUuids (Phase 3 race condition guard) ----

    @Test
    fun observePage_adds_uuid_to_activePageUuids() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        assertTrue(manager.activePageUuids.value.isEmpty())

        manager.observePage(PageUuid(pageUuid))
        assertTrue(manager.activePageUuids.value.contains(pageUuid),
            "activePageUuids should contain the page UUID once observePage is called")
    }

    @Test
    fun unobservePage_removes_uuid_from_activePageUuids() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        manager.observePage(PageUuid(pageUuid))
        assertTrue(manager.activePageUuids.value.contains(pageUuid))

        manager.unobservePage(PageUuid(pageUuid))
        assertFalse(manager.activePageUuids.value.contains(pageUuid),
            "activePageUuids should not contain the page UUID after unobservePage")
    }

    @Test
    fun activePageUuids_tracks_multiple_pages_independently() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        val pageA = createPage(uuid = "page-a")
        val pageB = createPage(uuid = "page-b")
        pageRepo.savePage(pageA)
        pageRepo.savePage(pageB)

        manager.observePage(PageUuid("page-a"))
        manager.observePage(PageUuid("page-b"))
        assertEquals(setOf("page-a", "page-b"), manager.activePageUuids.value)

        manager.unobservePage(PageUuid("page-a"))
        assertEquals(setOf("page-b"), manager.activePageUuids.value,
            "Only page-b should remain after unobserving page-a")
    }

    // ---- editingSelectionRange ----

    @Test
    fun updateEditingSelection_exposedViaStateFlow() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        assertNull(manager.editingSelectionRange.value)

        manager.updateEditingSelection(IntRange(2, 8))
        assertEquals(IntRange(2, 8), manager.editingSelectionRange.value)

        // Cleared when editing block is nulled
        manager.requestEditBlock(null)
        assertNull(manager.editingSelectionRange.value)
    }

    // ---- Selection mode operations ----

    @Test
    fun enterSelectionMode_sets_anchor_and_selected_and_isInSelectionMode() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        assertFalse(manager.isInSelectionMode.value)
        assertTrue(manager.selectedBlockUuids.value.isEmpty())

        manager.enterSelectionMode(BlockUuid("b1"))

        assertEquals(setOf("b1"), manager.selectedBlockUuids.value)
        assertTrue(manager.isInSelectionMode.value)
    }

    @Test
    fun toggleBlockSelection_adds_then_removes_uuid() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        manager.toggleBlockSelection(BlockUuid("b1"))
        assertTrue(manager.selectedBlockUuids.value.contains("b1"))
        assertTrue(manager.isInSelectionMode.value)

        manager.toggleBlockSelection(BlockUuid("b1"))
        assertFalse(manager.selectedBlockUuids.value.contains("b1"))
        assertFalse(manager.isInSelectionMode.value)
    }

    @Test
    fun toggleBlockSelection_can_add_multiple_uuids() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        manager.toggleBlockSelection(BlockUuid("b1"))
        manager.toggleBlockSelection(BlockUuid("b2"))

        assertEquals(setOf("b1", "b2"), manager.selectedBlockUuids.value)
        assertTrue(manager.isInSelectionMode.value)
    }

    @Test
    fun clearSelection_resets_all_selection_state() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        manager.enterSelectionMode(BlockUuid("b1"))
        assertTrue(manager.isInSelectionMode.value)

        manager.clearSelection()

        assertTrue(manager.selectedBlockUuids.value.isEmpty())
        assertFalse(manager.isInSelectionMode.value)
    }

    @Test
    fun selectAll_selects_all_visible_blocks_for_page() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", position = 0))
        blockRepo.saveBlock(createBlock("b2", position = 1))
        blockRepo.saveBlock(createBlock("b3", position = 2))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.selectAll(PageUuid(pageUuid))

        assertEquals(3, manager.selectedBlockUuids.value.size)
        assertTrue(manager.selectedBlockUuids.value.containsAll(setOf("b1", "b2", "b3")))
        assertTrue(manager.isInSelectionMode.value)
    }

    @Test
    fun extendSelectionByOne_down_adds_next_visible_block() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", position = 0))
        blockRepo.saveBlock(createBlock("b2", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.enterSelectionMode(BlockUuid("b1"))
        manager.extendSelectionByOne(up = false)

        assertTrue(manager.selectedBlockUuids.value.contains("b1"))
        assertTrue(manager.selectedBlockUuids.value.contains("b2"))
    }

    @Test
    fun extendSelectionByOne_up_adds_previous_visible_block() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", position = 0))
        blockRepo.saveBlock(createBlock("b2", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.enterSelectionMode(BlockUuid("b2"))
        manager.extendSelectionByOne(up = true)

        assertTrue(manager.selectedBlockUuids.value.contains("b1"))
        assertTrue(manager.selectedBlockUuids.value.contains("b2"))
    }

    @Test
    fun extendSelectionTo_selects_range_from_anchor_to_target() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", position = 0))
        blockRepo.saveBlock(createBlock("b2", position = 1))
        blockRepo.saveBlock(createBlock("b3", position = 2))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.enterSelectionMode(BlockUuid("b1"))
        manager.extendSelectionTo(BlockUuid("b3"))

        assertEquals(setOf("b1", "b2", "b3"), manager.selectedBlockUuids.value)
        assertTrue(manager.isInSelectionMode.value)
    }

    @Test
    fun deleteSelectedBlocks_removes_selected_block_and_clears_selection() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "keep", position = 0))
        blockRepo.saveBlock(createBlock("b2", content = "delete me", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.enterSelectionMode(BlockUuid("b2"))
        manager.deleteSelectedBlocks().join()
        advanceUntilIdle()

        val remaining = manager.blocks.value[pageUuid] ?: emptyList()
        assertFalse(remaining.any { it.uuid.value == "b2" }, "Deleted block must not remain in state")
        assertTrue(remaining.any { it.uuid.value == "b1" }, "Non-selected block must remain")
        assertTrue(manager.selectedBlockUuids.value.isEmpty(), "Selection must be cleared after delete")
        assertFalse(manager.isInSelectionMode.value)
    }

    @Test
    fun deleteSelectedBlocks_is_noop_when_nothing_selected() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        manager.deleteSelectedBlocks().join()

        assertFalse(manager.canUndo.value, "No undo entry must be recorded for empty selection delete")
    }

    // ---- Structural block operations ----

    @Test
    fun splitBlock_creates_two_blocks_at_cursor_position() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "HelloWorld", position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.splitBlock(BlockUuid("b1"), 5).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        assertEquals(2, blocks.size, "splitBlock must produce two blocks")
        assertTrue(blocks.any { it.content == "Hello" }, "First block must have content before cursor")
        assertTrue(blocks.any { it.content.trim() == "World" }, "Second block must have content after cursor")
    }

    @Test
    fun splitBlock_focuses_the_new_block() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "AB", position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.splitBlock(BlockUuid("b1"), 1).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val newBlock = blocks.find { it.uuid.value != "b1" }
        assertNotNull(newBlock, "A new block must be created by splitBlock")
        assertEquals(newBlock.uuid, manager.editingBlockUuid.value,
            "Focus must move to the new block after split")
    }

    @Test
    fun splitBlock_records_undo_entry_and_undo_restores_single_block() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "HelloWorld", position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.splitBlock(BlockUuid("b1"), 5).join()
        advanceUntilIdle()
        assertTrue(manager.canUndo.value, "splitBlock must record an undo entry")

        manager.undo().join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        assertEquals(1, blocks.size, "Undo must restore single block")
        assertEquals("HelloWorld", blocks[0].content, "Undo must restore original content")
    }

    @Test
    fun splitBlock_at_cursor_zero_produces_empty_first_block_and_full_content_in_second() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "Hello", position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.splitBlock(BlockUuid("b1"), 0).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        assertEquals(2, blocks.size, "splitBlock at 0 must still produce two blocks")
        val sorted = blocks.sortedBy { it.position }
        assertEquals("", sorted[0].content, "Block before cursor-at-0 must be empty")
        assertEquals("Hello", sorted[1].content, "Block after cursor-at-0 must have full original content")
    }

    @Test
    fun indentBlock_makes_block_child_of_previous_sibling() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "parent", position = 0))
        blockRepo.saveBlock(createBlock("b2", content = "child", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.indentBlock(BlockUuid("b2")).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val b2 = blocks.find { it.uuid.value == "b2" }
        assertNotNull(b2)
        assertEquals("b1", b2.parentUuid, "indentBlock must make b2 a child of b1")
    }

    @Test
    fun indentBlock_records_undo_entry() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", position = 0))
        blockRepo.saveBlock(createBlock("b2", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.indentBlock(BlockUuid("b2")).join()
        advanceUntilIdle()

        assertTrue(manager.canUndo.value, "indentBlock must record an undo entry")
    }

    @Test
    fun outdentBlock_moves_child_to_grandparent_level() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        // b1 at root, b2 is child of b1
        blockRepo.saveBlock(createBlock("b1", content = "root", position = 0))
        val childBlock = Block(
            uuid = BlockUuid("b2"), pageUuid = PageUuid(pageUuid), parentUuid = "b1",
            content = "child", level = 1, position = 0,
            createdAt = now, updatedAt = now
        )
        blockRepo.saveBlock(childBlock)
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.outdentBlock(BlockUuid("b2")).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val b2 = blocks.find { it.uuid.value == "b2" }
        assertNotNull(b2)
        assertNull(b2.parentUuid, "outdentBlock must remove parentUuid when outdenting from root child")
    }

    @Test
    fun moveBlockUp_swaps_positions_with_previous_sibling() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "first", position = 0))
        blockRepo.saveBlock(createBlock("b2", content = "second", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.moveBlockUp(BlockUuid("b2")).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val b1 = blocks.find { it.uuid.value == "b1" }!!
        val b2 = blocks.find { it.uuid.value == "b2" }!!
        assertTrue(b2.position < b1.position, "b2 must move above b1 after moveBlockUp")
    }

    @Test
    fun moveBlockDown_swaps_positions_with_next_sibling() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "first", position = 0))
        blockRepo.saveBlock(createBlock("b2", content = "second", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.moveBlockDown(BlockUuid("b1")).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val b1 = blocks.find { it.uuid.value == "b1" }!!
        val b2 = blocks.find { it.uuid.value == "b2" }!!
        assertTrue(b1.position > b2.position, "b1 must move below b2 after moveBlockDown")
    }

    // ---- Content update with undo ----

    @Test
    fun updateBlockContent_undo_restores_old_content() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "original", version = 0, position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.updateBlockContent(BlockUuid("b1"), "modified", 1).join()
        advanceUntilIdle()

        val afterEdit = manager.blocks.value[pageUuid]?.find { it.uuid.value == "b1" }
        assertEquals("modified", afterEdit?.content, "Content must update after edit")
        assertTrue(manager.canUndo.value)

        manager.undo().join()
        advanceUntilIdle()

        val afterUndo = manager.blocks.value[pageUuid]?.find { it.uuid.value == "b1" }
        assertEquals("original", afterUndo?.content, "Undo must restore original content")
    }

    @Test
    fun updateBlockContent_redo_reapplies_edit_after_undo() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "original", version = 0, position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.updateBlockContent(BlockUuid("b1"), "modified", 1).join()
        manager.undo().join()
        advanceUntilIdle()
        assertTrue(manager.canRedo.value)

        manager.redo().join()
        advanceUntilIdle()

        val afterRedo = manager.blocks.value[pageUuid]?.find { it.uuid.value == "b1" }
        assertEquals("modified", afterRedo?.content, "Redo must reapply the edit")
    }

    // ---- Focus navigation ----

    @Test
    fun focusNextBlock_moves_focus_to_next_visible_block() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "first", position = 0))
        blockRepo.saveBlock(createBlock("b2", content = "second", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.focusNextBlock(BlockUuid("b1")).join()
        advanceUntilIdle()

        assertEquals(BlockUuid("b2"), manager.editingBlockUuid.value,
            "focusNextBlock must move focus to the next block")
        assertEquals(0, manager.editingCursorIndex.value,
            "focusNextBlock must place cursor at position 0 in the next block")
    }

    @Test
    fun focusPreviousBlock_moves_focus_to_previous_visible_block() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "first", position = 0))
        blockRepo.saveBlock(createBlock("b2", content = "second", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.focusPreviousBlock(BlockUuid("b2")).join()
        advanceUntilIdle()

        assertEquals(BlockUuid("b1"), manager.editingBlockUuid.value,
            "focusPreviousBlock must move focus to the previous block")
        assertEquals("first".length, manager.editingCursorIndex.value,
            "focusPreviousBlock must place cursor at the end of the previous block")
    }

    @Test
    fun focusNextBlock_is_noop_at_last_block() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "only", position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.focusNextBlock(BlockUuid("b1")).join()
        advanceUntilIdle()

        assertNull(manager.editingBlockUuid.value,
            "focusNextBlock must not change focus when already at last block")
    }

    @Test
    fun focusPreviousBlock_is_noop_at_first_block() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "only", position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.focusPreviousBlock(BlockUuid("b1")).join()
        advanceUntilIdle()

        assertNull(manager.editingBlockUuid.value,
            "focusPreviousBlock must not change focus when already at first block")
    }

    // ---- Block properties ----

    @Test
    fun updateBlockProperties_persists_new_properties_in_local_state() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.updateBlockProperties(BlockUuid("b1"), mapOf("status" to "done")).join()
        advanceUntilIdle()

        val b1 = manager.blocks.value[pageUuid]?.find { it.uuid.value == "b1" }
        assertNotNull(b1)
        assertEquals("done", b1.properties["status"],
            "updateBlockProperties must update local state with new properties")
    }

    // ---- Merge block ----

    @Test
    fun mergeBlock_combines_content_with_previous_sibling_and_removes_block() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "Hello", position = 0))
        blockRepo.saveBlock(createBlock("b2", content = " World", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.mergeBlock(BlockUuid("b2")).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val b1 = blocks.find { it.uuid.value == "b1" }
        assertNotNull(b1, "b1 must remain after mergeBlock")
        assertEquals("Hello World", b1.content, "b1 must contain merged content")
        assertFalse(blocks.any { it.uuid.value == "b2" }, "Merged block b2 must be removed")
    }

    @Test
    fun mergeBlock_moves_focus_to_previous_block() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        blockRepo.saveBlock(createBlock("b1", content = "Hello", position = 0))
        blockRepo.saveBlock(createBlock("b2", content = "World", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.mergeBlock(BlockUuid("b2")).join()
        advanceUntilIdle()

        assertEquals(BlockUuid("b1"), manager.editingBlockUuid.value,
            "mergeBlock must move focus to the previous block")
        assertEquals("Hello".length, manager.editingCursorIndex.value,
            "mergeBlock must place cursor at the original end of the previous block")
    }

    // ---- TC-04: Optimistic update — splitBlock moves _blocks BEFORE DB write ----

    /**
     * TC-04: Verifies that splitBlock optimistically updates _blocks and sets focus BEFORE
     * the DB write completes (500ms delay simulated). The user sees the split immediately.
     */
    @Test
    fun splitBlock_optimistically_updates_blocks_and_focus_before_db_write() = runTest {
        val delayedRepo = DelayedBlockRepository(InMemoryBlockRepository(), splitDelayMs = 500L)
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, delayedRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(delayedRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        delayedRepo.delegate.saveBlock(createBlock("b1", content = "HelloWorld", position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // Launch splitBlock but don't join — check state before DB returns
        val job = manager.splitBlock(BlockUuid("b1"), 5)
        // Advance just enough to let the optimistic _blocks update and requestEditBlock run
        advanceTimeBy(1L)

        // Assert _blocks already has 2 entries BEFORE the 500ms DB delay expires
        val blocks = manager.blocks.value[pageUuid]
        assertEquals(2, blocks?.size, "_blocks must have 2 entries immediately after splitBlock launch (optimistic)")
        assertEquals("Hello", blocks?.find { it.uuid.value == "b1" }?.content,
            "First block must have content before cursor (optimistic)")
        val newBlock = blocks?.find { it.uuid.value != "b1" }
        assertNotNull(newBlock, "New block must be present in _blocks optimistically")
        assertEquals("World", newBlock.content, "New block must have content after cursor (optimistic)")

        // Focus must be on the new block immediately (before DB returns)
        assertEquals(newBlock.uuid, manager.editingBlockUuid.value,
            "Focus must move to new block UUID before DB write completes")

        // Now let the DB write complete
        job.join()
        advanceUntilIdle()
    }

    // ---- TC-05: Optimistic update — addNewBlock moves _blocks BEFORE DB write ----

    /**
     * TC-05: Verifies that addNewBlock optimistically inserts an empty block and sets focus
     * BEFORE the DB write completes.
     */
    @Test
    fun addNewBlock_optimistically_inserts_empty_block_and_focus_before_db_write() = runTest {
        val delayedRepo = DelayedBlockRepository(InMemoryBlockRepository(), splitDelayMs = 500L)
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, delayedRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(delayedRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        delayedRepo.delegate.saveBlock(createBlock("b1", content = "Hello", position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        val job = manager.addNewBlock(BlockUuid("b1"))
        advanceTimeBy(1L)

        val blocks = manager.blocks.value[pageUuid]
        assertEquals(2, blocks?.size, "_blocks must have 2 entries immediately after addNewBlock launch (optimistic)")
        val newBlock = blocks?.find { it.uuid.value != "b1" }
        assertNotNull(newBlock, "New empty block must be present optimistically")
        assertEquals("", newBlock.content, "New block must have empty content (addNewBlock appends empty)")

        assertEquals(newBlock.uuid, manager.editingBlockUuid.value,
            "Focus must move to new block UUID before DB write completes")

        job.join()
        advanceUntilIdle()
    }

    // ---- TC-06: Rollback — splitBlock rolls back _blocks on DB failure ----

    /**
     * TC-06: Verifies that when splitBlock's repository call returns Left (DB failure),
     * the optimistic _blocks update is reversed and focus returns to the original block.
     */
    @Test
    fun splitBlock_rolls_back_blocks_and_focus_on_db_failure() = runTest {
        val failingRepo = FailingBlockRepository(InMemoryBlockRepository())
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, failingRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(failingRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        failingRepo.delegate.saveBlock(createBlock("b1", content = "HelloWorld", position = 0))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.splitBlock(BlockUuid("b1"), 5).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid]
        assertEquals(1, blocks?.size, "_blocks must be rolled back to 1 entry on DB failure")
        assertEquals("HelloWorld", blocks?.get(0)?.content, "Original content must be restored on rollback")

        // Focus must return to original block at the original cursor position
        assertEquals(BlockUuid("b1"), manager.editingBlockUuid.value,
            "Focus must return to original block on DB failure")
        assertEquals(5, manager.editingCursorIndex.value,
            "Cursor must return to original position on DB failure")
    }

    // ---- TC-07: Rollback — mergeBlock rolls back focus on DB failure ----

    /**
     * TC-07: Verifies that when mergeBlock's repository call returns Left (DB failure),
     * focus is precisely restored to the pre-merge block and cursor position.
     *
     * In real usage the user is editing b2 at position 0 (beginning of the block)
     * when they press Backspace, so the pre-merge focus is (b2, 0). After a DB
     * failure the rollback must restore that exact state rather than always
     * resetting to position 0 of an arbitrary block.
     */
    @Test
    fun mergeBlock_rolls_back_focus_on_db_failure() = runTest {
        val failingRepo = FailingBlockRepository(InMemoryBlockRepository())
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, failingRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(failingRepo, graphLoader, scope)

        pageRepo.savePage(createPage())
        failingRepo.delegate.saveBlock(createBlock("b1", content = "Hello", position = 0))
        failingRepo.delegate.saveBlock(createBlock("b2", content = "World", position = 1))
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // Simulate user editing b2 at position 0 (cursor at the start of the block,
        // which is where Backspace triggers mergeBlock in real usage).
        manager.requestEditBlock(BlockUuid("b2"), 0)

        manager.mergeBlock(BlockUuid("b2")).join()
        advanceUntilIdle()

        // _blocks must still have 2 entries (merge did not succeed, but note that the merge
        // result is observed via reactive flow from the delegate which didn't actually mutate)
        val blocks = manager.blocks.value[pageUuid]
        assertEquals(2, blocks?.size,
            "_blocks must remain unchanged (2 entries) after failed merge")
        assertEquals("Hello", blocks?.find { it.uuid.value == "b1" }?.content,
            "b1 content must be unchanged after failed merge")
        assertEquals("World", blocks?.find { it.uuid.value == "b2" }?.content,
            "b2 content must be unchanged after failed merge")

        // Focus must be restored to the exact pre-merge state (b2, position 0)
        assertEquals(BlockUuid("b2"), manager.editingBlockUuid.value,
            "Focus must return to original block (b2) on merge DB failure")
        assertEquals(0, manager.editingCursorIndex.value,
            "Cursor must return to pre-merge position 0 on merge DB failure")
    }

    // ---- Race-condition tests (TC-N4a – TC-N4d) ----

    /**
     * TC-N4a: splitBlock after a pending content write must use the latest content.
     *
     * Without the fix, splitBlock bypasses the actor and reads stale content from the DB.
     * With the fix, the actor serializes: content write drains first, then split executes.
     */
    @Test
    fun splitBlock_after_pending_content_write_uses_latest_content() = runTest {
        val innerRepo = InMemoryBlockRepository()
        val delayedRepo = DelayedContentBlockRepository(innerRepo, contentDelayMs = 500L)
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, delayedRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val actor = DatabaseWriteActor(delayedRepo, pageRepo, scope = scope)

        pageRepo.savePage(createPage())
        innerRepo.saveBlock(createBlock("b1", content = "original", position = 0))
        val manager = BlockStateManager(
            blockRepository = delayedRepo,
            graphLoader = graphLoader,
            scope = scope,
            writeActor = actor,
        )
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // Queue delayed content write ("Hello World"), then immediately split at position 5.
        // Without the fix, split reads "original" from DB (10 chars, split at 5 → "origi"/"nal").
        // With the fix, content write drains first → split reads "Hello World" → "Hello"/" World".
        manager.updateBlockContent(BlockUuid("b1"), "Hello World", 1)
        manager.splitBlock(BlockUuid("b1"), 5)
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        assertEquals(2, blocks.size, "splitBlock must produce 2 blocks after race resolves")
        assertEquals("Hello", blocks.find { it.uuid.value == "b1" }?.content,
            "b1 must have 'Hello' (first part after split with latest content)")
        assertNotNull(blocks.find { it.content == "World" },
            "New block must have 'World' (second part of 'Hello World' split at 5)")

        actor.close()
    }

    /**
     * TC-N4b: addNewBlock after a pending content write must not lose the new block.
     *
     * Without the fix, addNewBlock's split executes while the content write is still pending:
     * splitBlock reads "original" from DB, creates a new block, then the content write fires
     * and updates b1 to "Hello World". The split is on stale data but the new block still
     * persists (because actor serialization ensures the split runs after the content write).
     *
     * The key invariant: with the fix, the content write drains before the split executes,
     * so the DB reflects the typed content before the structural op reads it. The cursor
     * position for addNewBlock comes from the in-memory _blocks state at call time, which
     * reflects "original" (8 chars) since _blocks is updated after writeContentOnly returns.
     * Result: split at position 8 on "Hello World" → b1 = "Hello Wo", new block = "rld".
     *
     * This is correct behavior: the new block was created from the typed content (not from
     * stale "original"), and no data is silently lost.
     */
    @Test
    fun addNewBlock_after_pending_content_write_preserves_typed_content() = runTest {
        val innerRepo = InMemoryBlockRepository()
        val delayedRepo = DelayedContentBlockRepository(innerRepo, contentDelayMs = 500L)
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, delayedRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val actor = DatabaseWriteActor(delayedRepo, pageRepo, scope = scope)

        pageRepo.savePage(createPage())
        innerRepo.saveBlock(createBlock("b1", content = "original", position = 0))
        val manager = BlockStateManager(
            blockRepository = delayedRepo,
            graphLoader = graphLoader,
            scope = scope,
            writeActor = actor,
        )
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // Queue content write then immediately press Enter (addNewBlock).
        // The content write is delayed 500ms; addNewBlock reads _blocks["b1"].content = "original"
        // (8 chars, not yet updated because applyContentChange updates _blocks after writeContentOnly).
        // With the fix: content write drains first → b1 DB = "Hello World"; then split at cursor 8
        // on "Hello World" → b1 = "Hello Wo", new block = "rld".
        manager.updateBlockContent(BlockUuid("b1"), "Hello World", 1)
        manager.addNewBlock(BlockUuid("b1"))
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        // The key assertion: 2 blocks exist (new block was created and persisted).
        assertEquals(2, blocks.size, "addNewBlock must produce 2 blocks after race resolves")
        // The new block exists (split used typed content from DB, not stale "original").
        val newBlock = blocks.find { it.uuid.value != "b1" }
        assertNotNull(newBlock, "New block must exist after addNewBlock")
        // With the fix: content write drains first → DB has "Hello World"; split at cursor 8
        // (addNewBlock read _blocks "original".length = 8 before _blocks was updated).
        // Split of "Hello World" at 8: b1 = "Hello Wo", new = "rld" (with trim).
        // Key invariant: b1 does NOT contain "original" — the split was on the typed content.
        assertFalse(blocks.find { it.uuid.value == "b1" }?.content?.contains("original") == true,
            "b1 must not contain 'original' after content write drained before split")

        actor.close()
    }

    /**
     * TC-N4c: mergeBlock after a pending content write must use the latest content.
     *
     * Without the fix, mergeBlocks reads stale "original" from b2 → merged = "Hellooriginal".
     * With the fix, content write for b2 drains first → b2 = " World" → merged = "Hello World".
     */
    @Test
    fun mergeBlock_after_pending_content_write_uses_latest_content() = runTest {
        val innerRepo = InMemoryBlockRepository()
        val delayedRepo = DelayedContentBlockRepository(innerRepo, contentDelayMs = 500L)
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, delayedRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val actor = DatabaseWriteActor(delayedRepo, pageRepo, scope = scope)

        pageRepo.savePage(createPage())
        innerRepo.saveBlock(createBlock("b1", content = "Hello", position = 0))
        innerRepo.saveBlock(createBlock("b2", content = "original", position = 1))
        val manager = BlockStateManager(
            blockRepository = delayedRepo,
            graphLoader = graphLoader,
            scope = scope,
            writeActor = actor,
        )
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // Queue content write for b2 (" World"), then immediately merge b2 into b1.
        // With the fix: content write drains first → b2 = " World" → merge → b1 = "Hello World".
        manager.updateBlockContent(BlockUuid("b2"), " World", 1)
        manager.mergeBlock(BlockUuid("b2"))
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        assertEquals(1, blocks.size, "mergeBlock must leave exactly 1 block after race resolves")
        assertEquals("Hello World", blocks.find { it.uuid.value == "b1" }?.content,
            "b1 must have merged content 'Hello World' (not 'Hellooriginal')")
        assertNull(blocks.find { it.uuid.value == "b2" },
            "b2 must be deleted after merge")

        actor.close()
    }

    /**
     * TC-N4d: handleBackspace after a pending content write must merge the latest content.
     *
     * Same as TC-N4c but triggered via handleBackspace with cursor at position 0 of b2.
     */
    @Test
    fun handleBackspace_after_pending_content_write_merges_latest_content() = runTest {
        val innerRepo = InMemoryBlockRepository()
        val delayedRepo = DelayedContentBlockRepository(innerRepo, contentDelayMs = 500L)
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, delayedRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val actor = DatabaseWriteActor(delayedRepo, pageRepo, scope = scope)

        pageRepo.savePage(createPage())
        innerRepo.saveBlock(createBlock("b1", content = "Hello", position = 0))
        innerRepo.saveBlock(createBlock("b2", content = "original", position = 1))
        val manager = BlockStateManager(
            blockRepository = delayedRepo,
            graphLoader = graphLoader,
            scope = scope,
            writeActor = actor,
        )
        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        // b2 live content in _blocks will be " World" after updateBlockContent.
        // Queue content write for b2 (" World"), then backspace at position 0 of b2.
        // handleBackspace reads currentBlock from DB (sees "original" non-empty → merge branch).
        // With the fix: content write drains first → b2 DB = " World" → merge → b1 = "Hello World".
        manager.updateBlockContent(BlockUuid("b2"), " World", 1)
        manager.requestEditBlock(BlockUuid("b2"), 0)
        manager.handleBackspace(BlockUuid("b2"))
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        assertEquals(1, blocks.size, "handleBackspace must leave exactly 1 block after race resolves")
        assertEquals("Hello World", blocks.find { it.uuid.value == "b1" }?.content,
            "b1 must have merged content 'Hello World' after handleBackspace (not 'Hellooriginal')")
        assertNull(blocks.find { it.uuid.value == "b2" },
            "b2 must be deleted after handleBackspace merge")

        actor.close()
    }
}

// ---- dirtyPageUuids: watcher guard for journals refresh (FR-2) ----

/**
 * Tests for [BlockStateManager.dirtyPageUuids] — the StateFlow used by GraphLoader to protect
 * only pages with unsaved edits from auto-reload (journals-page live refresh root cause).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockStateManagerDirtyPageUuidsTest {

    private val now = Clock.System.now()
    private val page1Uuid = "page-1-uuid"
    private val page2Uuid = "page-2-uuid"

    private fun block(uuid: String, pageUuid: String, content: String = "", version: Long = 0) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = content,
        position = 0,
        version = version,
        createdAt = now,
        updatedAt = now,
    )

    private fun page(uuid: String) = Page(
        uuid = PageUuid(uuid),
        name = "Page $uuid",
        filePath = null,
        createdAt = now,
        updatedAt = now,
    )

    /**
     * TC-DPU-1: dirtyPageUuids is empty on construction — no false-positives out of the gate.
     */
    @Test
    fun dirtyPageUuids_is_empty_on_construction() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, graphLoader, scope)

        assertTrue(manager.dirtyPageUuids.value.isEmpty(), "dirtyPageUuids must be empty before any edits")
    }

    /**
     * TC-DPU-2: dirtyPageUuids contains the page UUID when a block edit is in-flight.
     *
     * Uses [DelayedContentBlockRepository] to keep the dirty flag alive during the assertion:
     * with an immediate in-memory repo, the reactive flow re-emits the confirmed version and
     * clears the dirty flag before the assertion runs. The delay extends the window so the
     * combine emits the non-empty set while the DB write is still in-flight.
     */
    @Test
    fun dirtyPageUuids_contains_page_after_block_edit() = runTest {
        val innerRepo = InMemoryBlockRepository()
        val delayedRepo = DelayedContentBlockRepository(innerRepo, contentDelayMs = 500L)
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, delayedRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        pageRepo.savePage(page(page1Uuid))
        innerRepo.saveBlock(block("b1", page1Uuid, content = "original", version = 0))

        val manager = BlockStateManager(delayedRepo, graphLoader, scope)
        manager.observePage(PageUuid(page1Uuid))
        manager.blocks.first { it.containsKey(page1Uuid) }

        // Edit the block — marks it dirty; DB write is delayed so dirty flag persists
        manager.updateBlockContent(BlockUuid("b1"), "edited content", newVersion = 1L)
        // Advance only 100ms — dirty flag is set but DB write hasn't completed (delay=500ms)
        advanceTimeBy(100)

        assertTrue(
            page1Uuid in manager.dirtyPageUuids.value,
            "dirtyPageUuids must contain the page UUID when one of its blocks has an unsaved edit",
        )
    }

    /**
     * TC-DPU-3: An open-but-unedited page must NOT appear in dirtyPageUuids.
     *
     * Journals page is open (observed) but the user hasn't typed anything.
     * dirtyPageUuids must be empty so the watcher reloads the page on external change.
     */
    @Test
    fun dirtyPageUuids_is_empty_for_open_but_unedited_page() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        pageRepo.savePage(page(page1Uuid))
        blockRepo.saveBlock(block("b1", page1Uuid, content = "journal entry", version = 0))

        val manager = BlockStateManager(blockRepo, graphLoader, scope)
        manager.observePage(PageUuid(page1Uuid))
        manager.blocks.first { it.containsKey(page1Uuid) }
        // No edit — user is just reading the page

        advanceUntilIdle()
        assertTrue(
            manager.dirtyPageUuids.value.isEmpty(),
            "An open-but-unedited page must NOT be in dirtyPageUuids (journals refresh must work)",
        )
    }

    /**
     * TC-DPU-4: dirtyPageUuids returns to empty after the DB confirms the save.
     *
     * Uses [DelayedContentBlockRepository]: dirty flag is set during the delay window,
     * then clears when the delayed write completes and the reactive flow re-emits
     * the confirmed version (dirtyVersion == incomingVersion → clear).
     */
    @Test
    fun dirtyPageUuids_clears_after_db_confirms_save() = runTest {
        val innerRepo = InMemoryBlockRepository()
        val delayedRepo = DelayedContentBlockRepository(innerRepo, contentDelayMs = 500L)
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, delayedRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        pageRepo.savePage(page(page1Uuid))
        innerRepo.saveBlock(block("b1", page1Uuid, content = "original", version = 0))

        val manager = BlockStateManager(delayedRepo, graphLoader, scope)
        manager.observePage(PageUuid(page1Uuid))
        manager.blocks.first { it.containsKey(page1Uuid) }

        // Edit — dirty flag set, DB write in-flight
        manager.updateBlockContent(BlockUuid("b1"), "saved content", newVersion = 1L)
        advanceTimeBy(100) // before delay expires — still dirty
        assertTrue(page1Uuid in manager.dirtyPageUuids.value, "page must be dirty before DB confirms")

        // Advance past delay — DB write completes, reactive flow re-emits confirmed version
        advanceUntilIdle()
        assertTrue(
            manager.dirtyPageUuids.value.isEmpty(),
            "dirtyPageUuids must be empty after the DB confirms the save (version matches)",
        )
    }

    /**
     * TC-DPU-5: Only pages with dirty blocks appear in dirtyPageUuids — not all observed pages.
     *
     * Two pages are open. User edits a block on page1 only. page2 must NOT be in dirtyPageUuids
     * so it remains eligible for auto-reload (journals use case with multiple open pages).
     * Uses [DelayedContentBlockRepository] so the dirty flag on page1 persists during assertion.
     */
    @Test
    fun dirtyPageUuids_excludes_pages_with_no_dirty_blocks() = runTest {
        val innerRepo = InMemoryBlockRepository()
        val delayedRepo = DelayedContentBlockRepository(innerRepo, contentDelayMs = 500L)
        val pageRepo = InMemoryPageRepository()
        val graphLoader = GraphLoader(FakeFileSystem(), pageRepo, delayedRepo)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        pageRepo.savePage(page(page1Uuid))
        pageRepo.savePage(page(page2Uuid))
        innerRepo.saveBlock(block("b1", page1Uuid, content = "editing this", version = 0))
        innerRepo.saveBlock(block("b2", page2Uuid, content = "viewing this", version = 0))

        val manager = BlockStateManager(delayedRepo, graphLoader, scope)
        manager.observePage(PageUuid(page1Uuid))
        manager.observePage(PageUuid(page2Uuid))
        manager.blocks.first { it.containsKey(page1Uuid) && it.containsKey(page2Uuid) }

        // Only edit page1's block; DB write is delayed so dirty flag persists
        manager.updateBlockContent(BlockUuid("b1"), "my edits", newVersion = 1L)
        advanceTimeBy(100) // before delay expires — page1 still dirty

        val dirty = manager.dirtyPageUuids.value
        assertTrue(page1Uuid in dirty, "page1 (edited) must be in dirtyPageUuids")
        assertFalse(page2Uuid in dirty, "page2 (unedited) must NOT be in dirtyPageUuids — it must be auto-reloadable")
    }
}

// ---- Test doubles for optimistic update and rollback tests ----

/**
 * Wraps [InMemoryBlockRepository] and introduces a configurable suspend delay in [splitBlock]
 * to simulate a slow DB round-trip. Used by TC-04 and TC-05 to verify optimistic updates
 * occur before the DB returns.
 */
@OptIn(DirectRepositoryWrite::class)
private class DelayedBlockRepository(
    val delegate: InMemoryBlockRepository,
    private val splitDelayMs: Long = 500L,
) : BlockRepository by delegate {

    @DirectRepositoryWrite
    override suspend fun splitBlock(
        blockUuid: BlockUuid,
        cursorPosition: Int,
        newBlockUuid: BlockUuid?,
    ): Either<DomainError, Block> {
        delay(splitDelayMs)
        return delegate.splitBlock(blockUuid, cursorPosition, newBlockUuid)
    }
}

/**
 * Wraps [InMemoryBlockRepository] and always returns [Left] (DB failure) from [splitBlock]
 * and [mergeBlocks]. Used by TC-06 and TC-07 to verify rollback behaviour.
 */
@OptIn(DirectRepositoryWrite::class)
private class FailingBlockRepository(
    val delegate: InMemoryBlockRepository,
) : BlockRepository by delegate {

    @DirectRepositoryWrite
    override suspend fun splitBlock(
        blockUuid: BlockUuid,
        cursorPosition: Int,
        newBlockUuid: BlockUuid?,
    ): Either<DomainError, Block> =
        DomainError.DatabaseError.WriteFailed("injected splitBlock failure").left()

    @DirectRepositoryWrite
    override suspend fun mergeBlocks(blockUuid: BlockUuid, nextBlockUuid: BlockUuid, separator: String): Either<DomainError, Unit> =
        DomainError.DatabaseError.WriteFailed("injected mergeBlocks failure").left()
}

// ---- Race-condition test double ----

/**
 * Delays [updateBlockContentOnly] by [contentDelayMs] milliseconds.
 * Used to reproduce the race: a content write is queued in the actor but has not
 * yet committed to the repository when a structural op fires.
 *
 * This is distinct from [DelayedBlockRepository] which delays [splitBlock].
 */
@OptIn(DirectRepositoryWrite::class)
private class DelayedContentBlockRepository(
    val delegate: InMemoryBlockRepository,
    private val contentDelayMs: Long = 500L,
) : BlockRepository by delegate {
    @DirectRepositoryWrite
    override suspend fun updateBlockContentOnly(
        blockUuid: BlockUuid,
        content: String,
    ): Either<DomainError, Unit> {
        delay(contentDelayMs)
        return delegate.updateBlockContentOnly(blockUuid, content)
    }
}

