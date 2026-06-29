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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for optimistic block position (Epic 2, Task 2.3).
 *
 * Before the fix, `position = sourceBlock.position + 1` did string concatenation ("a01"),
 * producing an invalid FractionalIndexing key. These tests assert the new block's position
 * is a valid key that sorts correctly between its neighbours.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockStateManagerPositionTest {

    private val now = Clock.System.now()
    private val pageUuid = "pos-test-page-uuid"

    private fun block(uuid: String, content: String = uuid, position: String) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = content,
        position = position,
        createdAt = now,
        updatedAt = now,
    )

    private fun page() = Page(
        uuid = PageUuid(pageUuid),
        name = "Position Test Page",
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun addNewBlock_new_block_position_sorts_between_neighbours() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, GraphLoader(FakeFileSystem(), pageRepo, blockRepo), scope)

        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", position = "a0"))
        blockRepo.saveBlock(block("b2", position = "a2"))

        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.addNewBlock(BlockUuid("b1")).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: error("no blocks")
        val newBlock = blocks.find { it.uuid.value != "b1" && it.uuid.value != "b2" }
        assertNotNull(newBlock, "addNewBlock must insert a new block")
        assertTrue(newBlock.position > "a0", "New block must sort after 'a0' (b1)")
        assertTrue(newBlock.position < "a2", "New block must sort before 'a2' (b2)")
    }

    @Test
    fun addNewBlock_last_block_gets_valid_position_after_end() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, GraphLoader(FakeFileSystem(), pageRepo, blockRepo), scope)

        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", position = "a0"))

        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.addNewBlock(BlockUuid("b1")).join()
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: error("no blocks")
        val newBlock = blocks.find { it.uuid.value != "b1" }
        assertNotNull(newBlock, "addNewBlock must insert a new block after the last block")
        assertTrue(newBlock.position > "a0", "New block must sort after 'a0'")
    }

    @Test
    fun splitBlock_split_block_position_sorts_between_neighbours() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val manager = BlockStateManager(blockRepo, GraphLoader(FakeFileSystem(), pageRepo, blockRepo), scope)

        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", content = "Hello World", position = "a0"))
        blockRepo.saveBlock(block("b2", position = "a2"))

        manager.observePage(PageUuid(pageUuid))
        manager.blocks.first { it.containsKey(pageUuid) }

        manager.splitBlock(BlockUuid("b1"), 5).join()  // "Hello" | " World"
        advanceUntilIdle()

        val blocks = manager.blocks.value[pageUuid] ?: error("no blocks")
        val splitBlock = blocks.find { it.uuid.value != "b1" && it.uuid.value != "b2" }
        assertNotNull(splitBlock, "splitBlock must create a second block")
        assertTrue(splitBlock.position > "a0", "Split block must sort after b1 at 'a0'")
        assertTrue(splitBlock.position < "a2", "Split block must sort before b2 at 'a2'")
    }
}
