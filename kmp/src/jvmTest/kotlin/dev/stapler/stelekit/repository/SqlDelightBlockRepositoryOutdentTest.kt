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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Regression tests for outdentBlock's descendant-level bookkeeping on the SQLDelight (production)
 * backend — TreeOperations.outdent only computes the outdented block's own new level; each backend
 * is responsible for shifting its descendants' levels by the same delta.
 */
class SqlDelightBlockRepositoryOutdentTest {

    private fun buildRepos(): Pair<SqlDelightBlockRepository, SqlDelightPageRepository> {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        return SqlDelightBlockRepository(database) to SqlDelightPageRepository(database)
    }

    private fun now() = Clock.System.now()

    @Test
    fun outdentBlock_should_shiftDescendantLevels_when_outdentingBlockWithChildren() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = PageUuid("page-1"), name = "Test Page", createdAt = now, updatedAt = now))

        // Three-level tree: A (root) -> B (child of A) -> C (child of B)
        val a = Block(uuid = BlockUuid("a"), pageUuid = PageUuid("page-1"), content = "A", level = 0, position = "a0", createdAt = now, updatedAt = now)
        val b = Block(uuid = BlockUuid("b"), pageUuid = PageUuid("page-1"), parentUuid = BlockUuid("a"), content = "B", level = 1, position = "b0", createdAt = now, updatedAt = now)
        val c = Block(uuid = BlockUuid("c"), pageUuid = PageUuid("page-1"), parentUuid = BlockUuid("b"), content = "C", level = 2, position = "c0", createdAt = now, updatedAt = now)
        blockRepo.saveBlocks(listOf(a, b, c))

        val result = blockRepo.outdentBlock(BlockUuid("b"))
        assertTrue(result.isRight(), "outdentBlock must succeed: $result")

        val outdentedB = blockRepo.getBlockByUuid(BlockUuid("b")).first().getOrNull()
        val shiftedC = blockRepo.getBlockByUuid(BlockUuid("c")).first().getOrNull()

        assertNull(outdentedB?.parentUuid, "B should now be a top-level sibling of A")
        assertEquals(0, outdentedB?.level, "B's level should match its new parent's level (root)")
        assertEquals(BlockUuid("b"), shiftedC?.parentUuid, "C's parent link is unchanged by outdenting its parent")
        assertEquals(1, shiftedC?.level, "C must shift down by the same delta as B (2 -> 1), not stay stale at 2")
    }

    @Test
    fun outdentBlock_should_beNoOp_when_blockIsAlreadyTopLevel() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = PageUuid("page-1"), name = "Test Page", createdAt = now, updatedAt = now))

        val a = Block(uuid = BlockUuid("a"), pageUuid = PageUuid("page-1"), content = "A", level = 0, position = "a0", createdAt = now, updatedAt = now)
        blockRepo.saveBlocks(listOf(a))

        val result = blockRepo.outdentBlock(BlockUuid("a"))
        assertTrue(result.isRight(), "outdentBlock on a top-level block must be a safe no-op, not an error: $result")

        val unchanged = blockRepo.getBlockByUuid(BlockUuid("a")).first().getOrNull()
        assertNull(unchanged?.parentUuid)
        assertEquals(0, unchanged?.level)
    }
}
