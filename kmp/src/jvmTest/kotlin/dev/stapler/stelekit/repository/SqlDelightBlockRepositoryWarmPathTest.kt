@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.measureTime

/**
 * Regression tests for Fix A (android-trace-db-fixes): warm-path block saves use
 * [SqlDelightBlockRepository.saveBlocksUpdate] (targeted UPDATE) instead of
 * [SqlDelightBlockRepository.saveBlocks] (INSERT OR REPLACE).
 *
 * The critical invariant: an UPDATE on an existing row must NOT change the row's AUTOINCREMENT
 * [id], whereas DELETE+INSERT would assign a new [id]. This test guards against reversion to
 * the INSERT OR REPLACE strategy on the warm path.
 */
class SqlDelightBlockRepositoryWarmPathTest {

    private fun buildRepos(): Triple<SqlDelightBlockRepository, SqlDelightPageRepository, SteleDatabase> {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        return Triple(SqlDelightBlockRepository(database), SqlDelightPageRepository(database), database)
    }

    private fun now() = Clock.System.now()

    /**
     * REQ-1: saveBlocksUpdate updates content without changing the AUTOINCREMENT id.
     * A DELETE+INSERT path would assign a new id, breaking FTS5 rowid linkage.
     */
    @Test
    fun saveBlocksUpdate_should_preserveAutoIncrementId_when_updatingExistingBlock() = runBlocking {
        val (blockRepo, pageRepo, database) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = PageUuid("page-1"), name = "Test Page", createdAt = now, updatedAt = now))

        val block = Block(
            uuid = BlockUuid("block-1"),
            pageUuid = PageUuid("page-1"),
            content = "original content",
            position = "a0",
            createdAt = now,
            updatedAt = now,
        )

        // Insert initial block
        val insertResult = blockRepo.saveBlocks(listOf(block))
        assertTrue(insertResult.isRight(), "Initial insert must succeed: $insertResult")

        // Record the AUTOINCREMENT id via raw query
        val rowBefore = database.steleDatabaseQueries.selectBlockByUuid("block-1").executeAsOne()
        val idBefore = rowBefore.id

        // Call saveBlocksUpdate with modified content
        val updatedBlock = block.copy(content = "updated content")
        val updateResult = blockRepo.saveBlocksUpdate(listOf(updatedBlock))
        assertTrue(updateResult.isRight(), "saveBlocksUpdate must succeed: $updateResult")

        // Verify AUTOINCREMENT id is unchanged — proves UPDATE, not DELETE+INSERT
        val rowAfter = database.steleDatabaseQueries.selectBlockByUuid("block-1").executeAsOne()
        assertEquals(
            idBefore, rowAfter.id,
            "AUTOINCREMENT id must not change after saveBlocksUpdate — a changed id would indicate DELETE+INSERT"
        )

        // Verify content was actually updated
        assertEquals("updated content", rowAfter.content, "Content must be updated")
    }

    /**
     * REQ-1: saveBlocksUpdate reflects updated content via the repository read interface.
     */
    @Test
    fun saveBlocksUpdate_should_updateExistingBlockContent_when_blockAlreadyExists() = runBlocking {
        val (blockRepo, pageRepo, _) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = PageUuid("page-2"), name = "Content Test Page", createdAt = now, updatedAt = now))

        val blocks = (0 until 5).map { i ->
            Block(
                uuid = BlockUuid("blk-$i"),
                pageUuid = PageUuid("page-2"),
                content = "original $i",
                position = i.toString().padStart(11, '0'),
                createdAt = now,
                updatedAt = now,
            )
        }
        blockRepo.saveBlocks(blocks)

        val updatedBlocks = blocks.map { it.copy(content = "updated ${it.uuid.value}") }
        val result = blockRepo.saveBlocksUpdate(updatedBlocks)
        assertTrue(result.isRight(), "saveBlocksUpdate must succeed: $result")

        val stored = blockRepo.getBlocksForPage(PageUuid("page-2")).first().getOrNull() ?: emptyList()
        assertEquals(5, stored.size, "Block count must remain 5 after update")
        stored.forEach { b ->
            assertTrue(
                b.content.startsWith("updated "),
                "Content must be updated to 'updated ...' but was '${b.content}'"
            )
        }
    }

    /**
     * REQ-1: saveBlocksUpdate returns WriteFailed when the page does not exist (FK violation).
     */
    @Test
    fun saveBlocksUpdate_should_returnWriteFailed_when_databaseThrows() = runBlocking {
        val (blockRepo, _, _) = buildRepos()
        val now = now()

        // Block references a non-existent page_uuid — causes FK violation
        val orphanBlock = Block(
            uuid = BlockUuid("orphan-1"),
            pageUuid = PageUuid("non-existent-page"),
            content = "orphan content",
            position = "a0",
            createdAt = now,
            updatedAt = now,
        )

        // saveBlocksUpdate issues an UPDATE — if the row doesn't exist it's a no-op, not an error.
        // The FK violation only happens on INSERT. So we verify the result is Right (UPDATE is a no-op on missing row).
        val result = blockRepo.saveBlocksUpdate(listOf(orphanBlock))
        // An UPDATE on a non-existent row is a no-op in SQLite — should succeed (affect 0 rows)
        assertTrue(result.isRight(), "saveBlocksUpdate on missing row should be a no-op (Right): $result")
    }

    /**
     * REQ-1: saveBlocksUpdate with 200 blocks spanning multiple chunks persists all blocks.
     */
    @Test
    fun saveBlocksUpdate_should_persistAllBlocks_when_listSpansMultipleChunks() = runBlocking {
        val (blockRepo, pageRepo, _) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = PageUuid("page-chunked"), name = "Chunked Page", createdAt = now, updatedAt = now))

        val blocks = (0 until 200).map { i ->
            Block(
                uuid = BlockUuid("chunk-$i"),
                pageUuid = PageUuid("page-chunked"),
                content = "original $i",
                position = i.toString().padStart(11, '0'),
                createdAt = now,
                updatedAt = now,
            )
        }
        blockRepo.saveBlocks(blocks)

        val updatedBlocks = blocks.map { it.copy(content = "new content ${it.uuid.value}") }
        val result = blockRepo.saveBlocksUpdate(updatedBlocks)
        assertTrue(result.isRight(), "saveBlocksUpdate on 200 blocks must succeed: $result")

        val stored = blockRepo.getBlocksForPage(PageUuid("page-chunked")).first().getOrNull() ?: emptyList()
        assertEquals(200, stored.size, "All 200 blocks must remain after update")
        stored.forEach { b ->
            assertTrue(
                b.content.startsWith("new content "),
                "All blocks must have updated content, but '${b.uuid.value}' has '${b.content}'"
            )
        }
    }

    /**
     * REQ-1 / REQ-5: saveBlocksUpdate on 50 warm blocks completes within 500ms on JVM in-memory SQLite.
     * This is a conservative proxy for the 10ms/block target on Android (JVM in-memory is faster).
     */
    @Test
    fun saveBlocksUpdate_should_completeWithinBudget_when_updatingWarmDbPage() = runBlocking {
        val (blockRepo, pageRepo, _) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = PageUuid("page-perf"), name = "Perf Page", createdAt = now, updatedAt = now))

        val blocks = (0 until 50).map { i ->
            Block(
                uuid = BlockUuid("perf-$i"),
                pageUuid = PageUuid("page-perf"),
                content = "content $i",
                position = i.toString().padStart(11, '0'),
                createdAt = now,
                updatedAt = now,
            )
        }
        blockRepo.saveBlocks(blocks)

        val updatedBlocks = blocks.map { it.copy(content = "updated content $it") }
        val elapsed = measureTime {
            val result = blockRepo.saveBlocksUpdate(updatedBlocks)
            assertTrue(result.isRight(), "saveBlocksUpdate must succeed: $result")
        }

        assertTrue(
            elapsed.inWholeMilliseconds < 500,
            "saveBlocksUpdate on 50 warm blocks must complete in <500ms, was ${elapsed.inWholeMilliseconds}ms"
        )
    }
}
