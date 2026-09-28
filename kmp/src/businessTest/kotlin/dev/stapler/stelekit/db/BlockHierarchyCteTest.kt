@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

/**
 * Verifies that [SqlDelightBlockRepository.getBlockHierarchy] fetches the full
 * hierarchy in a single WITH RECURSIVE CTE, replacing the old BFS loop.
 *
 * Uses an in-memory SQLite database populated with a 50-block chain
 * (root → child₁ → child₂ → … → child₅₀) to confirm:
 *   - 51 total rows returned (root + 50 descendants)
 *   - depths are 0–50 in ascending order (no gaps, no out-of-order)
 *   - no duplicate UUIDs
 *   - root node is at depth 0
 */
class BlockHierarchyCteTest {

    private fun buildRepos(): Pair<SqlDelightBlockRepository, SqlDelightPageRepository> {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        return SqlDelightBlockRepository(database) to SqlDelightPageRepository(database)
    }

    private fun now() = Clock.System.now()

    private fun page(uuid: String) = Page(uuid = PageUuid(uuid), name = uuid, createdAt = now(), updatedAt = now())

    private fun block(uuid: String, pageUuid: String, parentUuid: String? = null, position: String = "a0") = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        parentUuid = parentUuid?.let { BlockUuid(it) },
        content = "content-$uuid",
        position = position,
        createdAt = now(),
        updatedAt = now()
    )

    @Test
    fun `getBlockHierarchy CTE returns all 51 blocks in a 50-deep chain`() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()

        // Set up: one page and a chain of 51 blocks (root + 50 descendants)
        pageRepo.savePage(page("p1"))

        val rootUuid = "block-0"
        blockRepo.saveBlock(block(rootUuid, "p1"))

        var previousUuid = rootUuid
        for (i in 1..50) {
            val uuid = "block-$i"
            blockRepo.saveBlock(block(uuid, "p1", parentUuid = previousUuid, position = "a0"))
            previousUuid = uuid
        }

        val result = blockRepo.getBlockHierarchy(BlockUuid(rootUuid)).first()

        // Must succeed
        assertNotNull(result.getOrNull(), "Expected Either.Right but got: $result")
        val hierarchy = result.getOrNull()!!

        // Must contain all 51 blocks
        assertEquals(51, hierarchy.size, "Expected 51 blocks (root + 50 descendants), got ${hierarchy.size}")

        // Must have no duplicate UUIDs
        val uuids = hierarchy.map { it.block.uuid.value }
        assertEquals(uuids.size, uuids.toSet().size, "Duplicate UUIDs found: ${uuids.groupBy { it }.filter { it.value.size > 1 }.keys}")

        // Root must be at depth 0
        assertEquals(rootUuid, hierarchy.first().block.uuid.value, "First row must be the root block")
        assertEquals(0, hierarchy.first().depth, "Root block must be at depth 0")

        // Depths must be non-decreasing (0, 1, 2, ..., 50 in order)
        val depths = hierarchy.map { it.depth }
        for (i in 1 until depths.size) {
            assert(depths[i] >= depths[i - 1]) {
                "Depths must be non-decreasing but got ${depths[i - 1]} then ${depths[i]} at index $i"
            }
        }

        // Every depth from 0 to 50 must appear exactly once
        val depthSet = depths.toSet()
        for (d in 0..50) {
            assert(d in depthSet) { "Missing depth $d in hierarchy output" }
        }
    }

    @Test
    fun `getBlockHierarchy CTE returns empty list when root block does not exist`() = runBlocking {
        val (blockRepo, _) = buildRepos()

        val result = blockRepo.getBlockHierarchy(BlockUuid("nonexistent-uuid")).first()

        assertNotNull(result.getOrNull(), "Expected Either.Right but got: $result")
        val hierarchy = result.getOrNull()!!
        assertEquals(0, hierarchy.size, "Expected empty list for nonexistent root, got ${hierarchy.size}")
    }

    @Test
    fun `getBlockHierarchy CTE returns only root for leaf block with no children`() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()

        pageRepo.savePage(page("p2"))
        blockRepo.saveBlock(block("leaf-only", "p2"))

        val result = blockRepo.getBlockHierarchy(BlockUuid("leaf-only")).first()

        assertNotNull(result.getOrNull(), "Expected Either.Right but got: $result")
        val hierarchy = result.getOrNull()!!
        assertEquals(1, hierarchy.size, "Expected exactly 1 block (the leaf) but got ${hierarchy.size}")
        assertEquals("leaf-only", hierarchy.first().block.uuid.value)
        assertEquals(0, hierarchy.first().depth)
    }
}
