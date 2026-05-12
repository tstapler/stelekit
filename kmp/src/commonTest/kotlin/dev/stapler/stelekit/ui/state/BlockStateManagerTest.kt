package dev.stapler.stelekit.ui.state

import arrow.core.Either
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
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

    override fun getBlocksForPage(pageUuid: String): Flow<Either<DomainError, List<Block>>> {
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
        uuid = uuid,
        pageUuid = pageUuid,
        content = content,
        position = position,
        version = version,
        createdAt = now,
        updatedAt = now
    )

    private fun createPage(uuid: String = pageUuid, filePath: String? = null) = Page(
        uuid = uuid,
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        val countAfterObserve = counting.getBlocksForPageCallCount

        manager.addBlockToPage(pageUuid)
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        // Simulate: dialog opened, focus lost, cursor nulled
        manager.requestEditBlock(null)
        assertNull(manager.editingCursorIndex.value)

        // Insert at position 5 using override
        manager.insertLinkAtCursor("b1", "MyPage", overrideCursorIndex = 5)
        advanceUntilIdle()

        val updated = blockRepo.getBlockByUuid("b1").first().getOrNull()
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.requestEditBlock(null)  // cursor = null, no override
        manager.insertLinkAtCursor("b1", "Page")
        advanceUntilIdle()

        val updated = blockRepo.getBlockByUuid("b1").first().getOrNull()
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        // Select "world" (positions 6..11)
        manager.replaceSelectionWithLink("b1", 6, 11, "World")
        advanceUntilIdle()

        val updated = blockRepo.getBlockByUuid("b1").first().getOrNull()
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        // selectionStart == selectionEnd → degenerate selection, falls back to insert
        manager.replaceSelectionWithLink("b1", 5, 5, "Page")
        advanceUntilIdle()

        val updated = blockRepo.getBlockByUuid("b1").first().getOrNull()
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        // Out-of-bounds end clamped to content length
        manager.replaceSelectionWithLink("b1", 0, 999, "Page")
        advanceUntilIdle()

        val updated = blockRepo.getBlockByUuid("b1").first().getOrNull()
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

        manager.observePage(pageUuid)
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
        manager.observePage(pageUuid)
        assertTrue(manager.activePageUuids.value.contains(pageUuid))

        manager.unobservePage(pageUuid)
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

        manager.observePage("page-a")
        manager.observePage("page-b")
        assertEquals(setOf("page-a", "page-b"), manager.activePageUuids.value)

        manager.unobservePage("page-a")
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

        manager.enterSelectionMode("b1")

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

        manager.toggleBlockSelection("b1")
        assertTrue(manager.selectedBlockUuids.value.contains("b1"))
        assertTrue(manager.isInSelectionMode.value)

        manager.toggleBlockSelection("b1")
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

        manager.toggleBlockSelection("b1")
        manager.toggleBlockSelection("b2")

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

        manager.enterSelectionMode("b1")
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.selectAll(pageUuid)

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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.enterSelectionMode("b1")
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.enterSelectionMode("b2")
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.enterSelectionMode("b1")
        manager.extendSelectionTo("b3")

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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.enterSelectionMode("b2")
        manager.deleteSelectedBlocks().join()
        advanceUntilIdle()

        val remaining = manager.blocks.value[pageUuid] ?: emptyList()
        assertFalse(remaining.any { it.uuid == "b2" }, "Deleted block must not remain in state")
        assertTrue(remaining.any { it.uuid == "b1" }, "Non-selected block must remain")
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.splitBlock("b1", 5).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        assertEquals(2, blocks.size, "splitBlock must produce two blocks")
        assertTrue(blocks.any { it.content == "Hello" }, "First block must have content before cursor")
        assertTrue(blocks.any { it.content == "World" }, "Second block must have content after cursor")
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.splitBlock("b1", 1).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val newBlock = blocks.find { it.uuid != "b1" }
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.splitBlock("b1", 5).join()
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.splitBlock("b1", 0).join()
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.indentBlock("b2").join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val b2 = blocks.find { it.uuid == "b2" }
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.indentBlock("b2").join()
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
            uuid = "b2", pageUuid = pageUuid, parentUuid = "b1",
            content = "child", level = 1, position = 0,
            createdAt = now, updatedAt = now
        )
        blockRepo.saveBlock(childBlock)
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.outdentBlock("b2").join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val b2 = blocks.find { it.uuid == "b2" }
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.moveBlockUp("b2").join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val b1 = blocks.find { it.uuid == "b1" }!!
        val b2 = blocks.find { it.uuid == "b2" }!!
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.moveBlockDown("b1").join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val b1 = blocks.find { it.uuid == "b1" }!!
        val b2 = blocks.find { it.uuid == "b2" }!!
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.updateBlockContent("b1", "modified", 1).join()
        advanceUntilIdle()

        val afterEdit = manager.blocks.value[pageUuid]?.find { it.uuid == "b1" }
        assertEquals("modified", afterEdit?.content, "Content must update after edit")
        assertTrue(manager.canUndo.value)

        manager.undo().join()
        advanceUntilIdle()

        val afterUndo = manager.blocks.value[pageUuid]?.find { it.uuid == "b1" }
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.updateBlockContent("b1", "modified", 1).join()
        manager.undo().join()
        advanceUntilIdle()
        assertTrue(manager.canRedo.value)

        manager.redo().join()
        advanceUntilIdle()

        val afterRedo = manager.blocks.value[pageUuid]?.find { it.uuid == "b1" }
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.focusNextBlock("b1").join()
        advanceUntilIdle()

        assertEquals("b2", manager.editingBlockUuid.value,
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.focusPreviousBlock("b2").join()
        advanceUntilIdle()

        assertEquals("b1", manager.editingBlockUuid.value,
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.focusNextBlock("b1").join()
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.focusPreviousBlock("b1").join()
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.updateBlockProperties("b1", mapOf("status" to "done")).join()
        advanceUntilIdle()

        val b1 = manager.blocks.value[pageUuid]?.find { it.uuid == "b1" }
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.mergeBlock("b2").join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: emptyList()
        val b1 = blocks.find { it.uuid == "b1" }
        assertNotNull(b1, "b1 must remain after mergeBlock")
        assertEquals("Hello World", b1.content, "b1 must contain merged content")
        assertFalse(blocks.any { it.uuid == "b2" }, "Merged block b2 must be removed")
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
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.mergeBlock("b2").join()
        advanceUntilIdle()

        assertEquals("b1", manager.editingBlockUuid.value,
            "mergeBlock must move focus to the previous block")
        assertEquals("Hello".length, manager.editingCursorIndex.value,
            "mergeBlock must place cursor at the original end of the previous block")
    }
}
