package dev.stapler.stelekit.ui.state

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integration tests for copy/paste operations through [BlockStateManager] (Epic 6, Task 6.2).
 *
 * Uses [InMemoryBlockRepository] and the same [FakeFileSystem] available in this package.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockStateManagerCopyPasteTest {

    private val now = Clock.System.now()
    private val pageUuid = "copy-paste-test-page"

    private fun block(
        uuid: String,
        position: String,
        parentUuid: String? = null,
        level: Int = 0,
        leftUuid: String? = null,
    ) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = uuid,
        position = position,
        parentUuid = parentUuid,
        level = level,
        leftUuid = leftUuid,
        createdAt = now,
        updatedAt = now,
    )

    private fun page() = Page(
        uuid = PageUuid(pageUuid),
        name = "Copy Paste Test",
        createdAt = now,
        updatedAt = now,
    )

    private fun TestScope.manager(
        blockRepo: InMemoryBlockRepository,
        pageRepo: InMemoryPageRepository,
    ): BlockStateManager {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return BlockStateManager(
            blockRepo,
            GraphLoader(FakeFileSystem(), pageRepo, blockRepo),
            scope,
            graphWriter = GraphWriter(FakeFileSystem()),
            pageRepository = pageRepo,
            graphPathProvider = { "/fake/graph" },
        )
    }

    @Test
    fun copySelectedBlocks_empty_selection_is_noop() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val m = manager(blockRepo, pageRepo)
        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", "a0"))

        m.copySelectedBlocks().join()

        assertTrue(m.blockClipboard.value.isEmpty, "Clipboard must stay empty when no blocks are selected")
    }

    @Test
    fun copySelectedBlocks_single_block_copies_to_clipboard() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val m = manager(blockRepo, pageRepo)
        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", "a0"))
        blockRepo.saveBlock(block("b2", "a1"))

        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }

        m.enterSelectionMode(BlockUuid("b1"))
        m.copySelectedBlocks().join()

        val entries = m.blockClipboard.value.entries
        assertEquals(1, entries.size, "Clipboard must contain exactly 1 block")
        assertEquals("b1", entries.first().block.uuid.value)
    }

    @Test
    fun copySelectedBlocks_subtree_dedup_no_duplicate_children() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val m = manager(blockRepo, pageRepo)
        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", "a0"))
        blockRepo.saveBlock(block("b2", "a1", parentUuid = "b1", level = 1))

        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }

        // Select parent b1 AND child b2; subtreeDedup removes b2 from roots, then collectSubtree
        // expands b1 to include b2, so the clipboard must have exactly b1 + b2 without duplication.
        m.enterSelectionMode(BlockUuid("b1"))
        m.toggleBlockSelection(BlockUuid("b2"))
        m.copySelectedBlocks().join()

        val entries = m.blockClipboard.value.entries
        assertEquals(2, entries.size, "Clipboard must have exactly b1 + b2 (no duplication)")
        assertTrue(entries.any { it.block.uuid.value == "b1" })
        assertTrue(entries.any { it.block.uuid.value == "b2" })
    }

    @Test
    fun pasteBlocks_single_block_inserts_after_target() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val m = manager(blockRepo, pageRepo)
        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", "a0"))
        blockRepo.saveBlock(block("b2", "a1"))

        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }

        // Copy b2
        m.enterSelectionMode(BlockUuid("b2"))
        m.copySelectedBlocks().join()

        // Paste after b1
        m.pasteBlocks(BlockUuid("b1")).join()
        advanceUntilIdle()

        val blocks = m.blocks.value[pageUuid] ?: error("no blocks for page")
        assertEquals(3, blocks.size, "Page must have 3 blocks after paste")

        val b1 = blocks.find { it.uuid.value == "b1" }!!
        val b2 = blocks.find { it.uuid.value == "b2" }!!
        val pasted = blocks.find { it.uuid.value != "b1" && it.uuid.value != "b2" }!!

        assertTrue(pasted.position > b1.position, "Pasted block must sort after b1")
        assertTrue(pasted.position < b2.position, "Pasted block must sort before b2")
    }

    @Test
    fun pasteBlocks_right_sibling_left_uuid_repaired() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val m = manager(blockRepo, pageRepo)
        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", "a0"))
        blockRepo.saveBlock(block("b2", "a1", leftUuid = "b1"))
        blockRepo.saveBlock(block("b3", "a2", leftUuid = "b2"))

        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }

        // Copy b1, then paste after b1 — inserts a new block between b1 and b2
        m.enterSelectionMode(BlockUuid("b1"))
        m.copySelectedBlocks().join()
        m.pasteBlocks(BlockUuid("b1")).join()
        advanceUntilIdle()

        val blocks = m.blocks.value[pageUuid] ?: error("no blocks for page")
        val pasted = blocks.find { it.uuid.value != "b1" && it.uuid.value != "b2" && it.uuid.value != "b3" }!!
        val b2After = blocks.find { it.uuid.value == "b2" }!!

        // BUG 1 fix: b2 is the right sibling of afterBlock (b1); its leftUuid must be
        // updated from "b1" to the pasted block's new UUID.
        assertNotEquals("b1", b2After.leftUuid, "b2.leftUuid must be repaired away from 'b1'")
        assertEquals(pasted.uuid.value, b2After.leftUuid, "b2.leftUuid must point to the pasted block")
    }

    @Test
    fun pasteBlocks_uuid_uniqueness() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val m = manager(blockRepo, pageRepo)
        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", "a0"))
        blockRepo.saveBlock(block("b2", "a1"))

        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }

        m.enterSelectionMode(BlockUuid("b1"))
        m.copySelectedBlocks().join()
        m.pasteBlocks(BlockUuid("b1")).join()
        advanceUntilIdle()

        val blocks = m.blocks.value[pageUuid] ?: error("no blocks for page")
        val pasted = blocks.find { it.uuid.value != "b1" && it.uuid.value != "b2" }!!
        assertNotEquals("b1", pasted.uuid.value, "Pasted block must have a new UUID, not the original 'b1'")
    }
}
