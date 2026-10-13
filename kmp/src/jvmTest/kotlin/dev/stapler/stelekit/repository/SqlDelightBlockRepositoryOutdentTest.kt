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

    /**
     * A parent's first child stores the parent's uuid as its left_uuid, so "the block whose
     * left_uuid is the old parent" matches that child as well as the parent's real next sibling.
     * Picking the child inverted the fractional-index bounds and outdent failed with
     * "a must be less than b".
     */
    private fun block(
        now: kotlin.time.Instant, uuid: String, parent: String?, left: String?, level: Int, position: String,
    ) = Block(
        uuid = BlockUuid(uuid), pageUuid = PageUuid("page-1"), parentUuid = parent?.let(::BlockUuid),
        leftUuid = left?.let(::BlockUuid), content = uuid, level = level, position = position,
        createdAt = now, updatedAt = now,
    )

    private fun outdentTree(now: kotlin.time.Instant): List<Block> = listOf(
        block(now, "r", null, null, 0, "a0"),
        block(now, "q", "r", "r", 1, "a0"),
        block(now, "p", "r", "q", 1, "a1"),
        block(now, "d1", "p", "p", 2, "a0"), // first child: left_uuid is the parent's uuid
        block(now, "e", "p", "d1", 2, "a1"),
    )

    @Test
    fun outdentBlock_should_succeed_when_oldParentsFirstChildPointsLeftAtParent() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = PageUuid("page-1"), name = "Test Page", createdAt = now, updatedAt = now))
        blockRepo.saveBlocks(outdentTree(now))

        val result = blockRepo.outdentBlock(BlockUuid("e"))
        assertTrue(result.isRight(), "outdent of the last child must succeed: $result")

        val e = blockRepo.getBlockByUuid(BlockUuid("e")).first().getOrNull()!!
        assertEquals(BlockUuid("r"), e.parentUuid, "E becomes a sibling of its old parent")
        assertEquals(1, e.level)
        assertEquals(BlockUuid("p"), e.leftUuid, "E sits immediately after its old parent")
        assertTrue(e.position > "a1", "E sorts after its old parent: ${e.position}")
        assertEquals(BlockUuid("p"), blockRepo.getBlockByUuid(BlockUuid("d1")).first().getOrNull()!!.leftUuid,
            "the old parent's first child keeps pointing at the parent")
    }

    @Test
    fun outdentBlock_should_insertBetweenOldParentAndItsRealNextSibling() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = PageUuid("page-1"), name = "Test Page", createdAt = now, updatedAt = now))
        blockRepo.saveBlocks(outdentTree(now) + block(now, "s", "r", "p", 1, "a2"))

        val result = blockRepo.outdentBlock(BlockUuid("e"))
        assertTrue(result.isRight(), "outdent must succeed when the old parent has a following sibling: $result")

        val e = blockRepo.getBlockByUuid(BlockUuid("e")).first().getOrNull()!!
        val s = blockRepo.getBlockByUuid(BlockUuid("s")).first().getOrNull()!!
        assertTrue(e.position > "a1" && e.position < "a2", "E sorts between P and S: ${e.position}")
        assertEquals(BlockUuid("e"), s.leftUuid, "the block that followed the old parent now follows E")
        assertEquals(BlockUuid("p"), blockRepo.getBlockByUuid(BlockUuid("d1")).first().getOrNull()!!.leftUuid,
            "the old parent's first child is not re-chained")
    }

    // Same unscoped-left_uuid class as above, for the other operations that look up "the block after X".

    @Test
    fun splitBlock_should_succeed_when_blockHasChildren() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = PageUuid("page-1"), name = "Test Page", createdAt = now, updatedAt = now))
        blockRepo.saveBlocks(listOf(block(now, "r", null, null, 0, "a0"), block(now, "c", "r", "r", 1, "a0")))

        val result = blockRepo.splitBlock(BlockUuid("r"), cursorPosition = 1, newBlockUuid = null)

        val newBlock = result.getOrNull()
        assertTrue(newBlock != null, "Enter at the end of a block with children must succeed: $result")
        assertNull(newBlock.parentUuid, "the new block is a sibling of R, not a child")
        assertTrue(newBlock.position > "a0", "the new block sorts after R: ${newBlock.position}")
    }

    @Test
    fun deleteBlock_should_rechainRealNextSibling_when_deletedBlockHasChildren() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = PageUuid("page-1"), name = "Test Page", createdAt = now, updatedAt = now))
        blockRepo.saveBlocks(
            listOf(
                block(now, "p0", null, null, 0, "a0"),
                block(now, "r", null, "p0", 0, "a1"),
                block(now, "c", "r", "r", 1, "a0"), // R's first child: left_uuid is R's uuid
                block(now, "s", null, "r", 0, "a2"),
            ),
        )

        val result = blockRepo.deleteBlock(BlockUuid("r"), deleteChildren = true)
        assertTrue(result.isRight(), "delete must succeed: $result")

        assertEquals(BlockUuid("p0"), blockRepo.getBlockByUuid(BlockUuid("s")).first().getOrNull()!!.leftUuid,
            "S must follow R's old left sibling, not stay pointing at the deleted block")
    }

    @Test
    fun indentBlock_should_notRechainBlocksOwnChildren() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        val now = now()
        pageRepo.savePage(Page(uuid = PageUuid("page-1"), name = "Test Page", createdAt = now, updatedAt = now))
        blockRepo.saveBlocks(
            listOf(
                block(now, "a", null, null, 0, "a0"),
                block(now, "x", null, "a", 0, "a1"),
                block(now, "xc", "x", "x", 1, "a0"), // X's first child: left_uuid is X's uuid
            ),
        )

        val result = blockRepo.indentBlock(BlockUuid("x"))
        assertTrue(result.isRight(), "indent must succeed: $result")

        assertEquals(BlockUuid("x"), blockRepo.getBlockByUuid(BlockUuid("xc")).first().getOrNull()!!.leftUuid,
            "X's own first child must keep pointing at X; it is not X's next sibling")
    }
}
