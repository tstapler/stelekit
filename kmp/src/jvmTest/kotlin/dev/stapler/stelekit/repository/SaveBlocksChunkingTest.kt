@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Regression tests for BUG-008 root cause B: unbounded [BlockRepository.saveBlocks] transaction.
 *
 * Before the fix, [SqlDelightBlockRepository.saveBlocks] wrapped all blocks in a single
 * transaction. A batch of 2000+ blocks held the SQLite write lock for the entire duration,
 * starving concurrent [BlockStateManager.addBlockToPage] calls.
 *
 * The fix chunks large lists into 50-block transactions. These tests verify that chunking
 * preserves correctness: every block must be stored regardless of list size.
 */
class SaveBlocksChunkingTest {

    private fun buildRepos(): Pair<SqlDelightBlockRepository, SqlDelightPageRepository> {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        return SqlDelightBlockRepository(database) to SqlDelightPageRepository(database)
    }

    private fun now() = Clock.System.now()

    /**
     * 200 blocks = 4 chunks of 50.
     *
     * Fails against pre-fix code only if chunking was completely broken (all-or-nothing).
     * More critically, this test documents the correctness contract: every block in
     * a large batch must be persisted.
     */
    @Test
    fun saveBlocks_stores_all_blocks_when_list_spans_multiple_chunks() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = "p1", name = "Test Page", createdAt = now, updatedAt = now))

        val blocks = (0 until 200).map { i ->
            Block(
                uuid = "block-$i",
                pageUuid = "p1",
                content = "content $i",
                position = i,
                createdAt = now,
                updatedAt = now
            )
        }

        val result = blockRepo.saveBlocks(blocks)
        assertTrue(result.isRight(), "saveBlocks must succeed for 200 blocks: $result")

        val stored = blockRepo.getBlocksForPage("p1").first().getOrNull() ?: emptyList()
        assertEquals(200, stored.size,
            "All 200 blocks must be stored — chunking must not drop blocks at chunk boundaries")
    }

    @Test
    fun saveBlocks_stores_exactly_one_chunk_boundary_size() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = "p2", name = "Boundary Page", createdAt = now, updatedAt = now))

        // Exactly 50 blocks — the chunk size — to verify the boundary case
        val blocks = (0 until 50).map { i ->
            Block(
                uuid = "b-$i",
                pageUuid = "p2",
                content = "c$i",
                position = i,
                createdAt = now,
                updatedAt = now
            )
        }

        blockRepo.saveBlocks(blocks)
        val stored = blockRepo.getBlocksForPage("p2").first().getOrNull() ?: emptyList()
        assertEquals(50, stored.size, "Exactly 50 blocks (one chunk) must all be stored")
    }

    @Test
    fun saveBlocks_stores_blocks_that_straddle_a_chunk_boundary() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = "p3", name = "Odd Page", createdAt = now, updatedAt = now))

        // 51 blocks = one full chunk (0..49) + one block in the second chunk — exercises
        // the boundary between chunk 1 and chunk 2 where an off-by-one would drop block 50.
        val blocks = (0 until 51).map { i ->
            Block(
                uuid = "c-$i",
                pageUuid = "p3",
                content = "item $i",
                position = i,
                createdAt = now,
                updatedAt = now
            )
        }

        val result = blockRepo.saveBlocks(blocks)
        assertTrue(result.isRight(), "saveBlocks must succeed for 51 blocks: $result")

        val stored = blockRepo.getBlocksForPage("p3").first().getOrNull() ?: emptyList()
        assertEquals(51, stored.size,
            "Block at chunk boundary (index 50) must not be dropped — chunked(50) creates [0..49], [50]")
    }
}
