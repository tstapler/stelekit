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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for reorder persistence (Epic 1, Task 1.2).
 *
 * Guards the chain: moveBlockUp/Down/indent/outdent
 *   → refreshBlocksForPage → queueDiskSave → diskWriteDebounce.debounce
 *
 * If that chain breaks, hasPendingDiskWrite returns false and the tests fail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockStateManagerReorderTest {

    private val now = Clock.System.now()
    private val pageUuid = "reorder-page-uuid"

    private fun block(uuid: String, position: String, parentUuid: String? = null) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = uuid,
        position = position,
        parentUuid = parentUuid?.let { BlockUuid(it) },
        createdAt = now,
        updatedAt = now,
    )

    private fun page() = Page(
        uuid = PageUuid(pageUuid),
        name = "Reorder Test Page",
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
    fun moveBlockUp_triggers_pending_disk_save() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val m = manager(blockRepo, pageRepo)
        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", "a0"))
        blockRepo.saveBlock(block("b2", "a1"))

        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }

        m.moveBlockUp(BlockUuid("b2")).join()

        assertTrue(m.hasPendingDiskWrite(pageUuid), "moveBlockUp must trigger queueDiskSave")
    }

    @Test
    fun moveBlockDown_triggers_pending_disk_save() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val m = manager(blockRepo, pageRepo)
        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", "a0"))
        blockRepo.saveBlock(block("b2", "a1"))

        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }

        m.moveBlockDown(BlockUuid("b1")).join()

        assertTrue(m.hasPendingDiskWrite(pageUuid), "moveBlockDown must trigger queueDiskSave")
        val pos = m.blocks.value[pageUuid]?.associate { it.uuid.value to it.position } ?: error("no blocks")
        assertTrue(pos["b1"]!! > pos["b2"]!!, "b1 must sort after b2 after moveBlockDown(b1)")
    }

    @Test
    fun indentBlock_triggers_pending_disk_save() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val m = manager(blockRepo, pageRepo)
        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", "a0"))
        blockRepo.saveBlock(block("b2", "a1"))

        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }

        m.indentBlock(BlockUuid("b2")).join()

        assertTrue(m.hasPendingDiskWrite(pageUuid), "indentBlock must trigger queueDiskSave")
        val b2 = m.blocks.value[pageUuid]?.find { it.uuid.value == "b2" } ?: error("b2 not found")
        assertEquals("b1", b2.parentUuid?.value, "b2 must be a child of b1 after indent")
    }

    @Test
    fun outdentBlock_triggers_pending_disk_save() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val m = manager(blockRepo, pageRepo)
        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", "a0"))
        blockRepo.saveBlock(block("b2", "a1", parentUuid = "b1"))

        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }

        m.outdentBlock(BlockUuid("b2")).join()

        assertTrue(m.hasPendingDiskWrite(pageUuid), "outdentBlock must trigger queueDiskSave")
        val b2 = m.blocks.value[pageUuid]?.find { it.uuid.value == "b2" } ?: error("b2 not found")
        assertNull(b2.parentUuid, "b2 must be at root level after outdent")
    }

    @Test
    fun moveBlockUp_updates_block_order_in_memory() = runTest {
        val blockRepo = InMemoryBlockRepository()
        val pageRepo = InMemoryPageRepository()
        val m = manager(blockRepo, pageRepo)
        pageRepo.savePage(page())
        blockRepo.saveBlock(block("b1", "a0"))
        blockRepo.saveBlock(block("b2", "a1"))

        m.observePage(PageUuid(pageUuid))
        m.blocks.first { it.containsKey(pageUuid) }

        m.moveBlockUp(BlockUuid("b2")).join()
        advanceUntilIdle()

        val blocks = m.blocks.value[pageUuid] ?: error("no blocks for page")
        val pos = blocks.associate { it.uuid.value to it.position }
        assertTrue(pos["b2"]!! < pos["b1"]!!, "b2 must sort before b1 after moveBlockUp")
    }
}
