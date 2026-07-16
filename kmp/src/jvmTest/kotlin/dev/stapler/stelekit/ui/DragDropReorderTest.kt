package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.PopulatedFakePageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Tests for the block drag-drop reorder logic.
 *
 * Test 1: Pure data structure verification — after a move, the block's parent
 * would be updated (exercises the model layer used by moveSelectedBlocks).
 *
 * Tests 2 & 3: Pure logic tests of the own-subtree guard that mirrors the
 * `allDescendants` computation in BlockList.kt.
 */
class DragDropReorderTest {

    private val now = Clock.System.now()

    // ---------------------------------------------------------------------------
    // Helper — mirrors the own-subtree guard from BlockList.kt
    // ---------------------------------------------------------------------------

    private fun allDescendants(draggedUuids: Set<String>, blocks: List<Block>): Set<String> {
        return draggedUuids + draggedUuids.flatMap { uuid ->
            val queue = ArrayDeque<String>()
            queue.add(uuid)
            val result = mutableSetOf<String>()
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                blocks.filter { it.parentUuid?.value == current }.forEach {
                    result.add(it.uuid.value)
                    queue.add(it.uuid.value)
                }
            }
            result
        }
    }

    // ---------------------------------------------------------------------------
    // Test 1: moves block to new parent (drop as child of root)
    // ---------------------------------------------------------------------------

    @Test
    fun movingBlock_updatesParentUuid() {
        // Page: A (root) → B (child of A) → C (child of B)
        val blockA = Block(
            uuid = BlockUuid("A"),
            pageUuid = PageUuid("page1"),
            content = "Block A",
            parentUuid = null,
            level = 0,
            position = "a0",
            createdAt = now,
            updatedAt = now
        )
        val blockB = Block(
            uuid = BlockUuid("B"),
            pageUuid = PageUuid("page1"),
            content = "Block B",
            parentUuid = BlockUuid("A"),
            level = 1,
            position = "a0",
            createdAt = now,
            updatedAt = now
        )
        val blockC = Block(
            uuid = BlockUuid("C"),
            pageUuid = PageUuid("page1"),
            content = "Block C",
            parentUuid = BlockUuid("B"),
            level = 2,
            position = "a0",
            createdAt = now,
            updatedAt = now
        )

        // Simulate moving C to a root position (parentUuid = null)
        val movedC = blockC.copy(parentUuid = null, level = 0)

        // After the move, C's parent should be null (promoted to root)
        assertTrue(movedC.parentUuid == null, "C should have no parent after move to root")
        // A and B are unaffected
        assertTrue(blockA.parentUuid == null, "A should remain at root")
        assertTrue(blockB.parentUuid == BlockUuid("A"), "B should still be a child of A")
    }

    // ---------------------------------------------------------------------------
    // Test 2: own-subtree guard — single dragged block
    // ---------------------------------------------------------------------------

    @Test
    fun ownSubtreeGuard_identifiesDescendantsCorrectly() {
        // Hierarchy: A (root) → B (child of A) → C (child of B)
        val now = Clock.System.now()
        val blocks = listOf(
            Block(uuid = BlockUuid("A"), pageUuid = PageUuid("p"), content = "A", parentUuid = null,  level = 0, position = "a0", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("B"), pageUuid = PageUuid("p"), content = "B", parentUuid = BlockUuid("A"),   level = 1, position = "a0", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("C"), pageUuid = PageUuid("p"), content = "C", parentUuid = BlockUuid("B"),   level = 2, position = "a0", createdAt = now, updatedAt = now)
        )

        // Dragging A — both B and C are descendants
        val descendantsOfA = allDescendants(setOf("A"), blocks)
        assertTrue("B" in descendantsOfA, "B is a descendant of A")
        assertTrue("C" in descendantsOfA, "C is a descendant of A (via B)")

        // Dragging B — C is a descendant, but A is not
        val descendantsOfB = allDescendants(setOf("B"), blocks)
        assertFalse("A" in descendantsOfB, "A is NOT a descendant of B (it is B's ancestor)")
        assertTrue("C" in descendantsOfB, "C is a descendant of B")
    }

    // ---------------------------------------------------------------------------
    // Test 3: multi-block drag includes all descendants
    // ---------------------------------------------------------------------------

    @Test
    fun ownSubtreeGuard_multiBlockDrag_includesAllDescendants() {
        // Hierarchy: A (root) with children B and C; C has child D
        val now = Clock.System.now()
        val blocks = listOf(
            Block(uuid = BlockUuid("A"), pageUuid = PageUuid("p"), content = "A", parentUuid = null, level = 0, position = "a0", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("B"), pageUuid = PageUuid("p"), content = "B", parentUuid = BlockUuid("A"),  level = 1, position = "a0", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("C"), pageUuid = PageUuid("p"), content = "C", parentUuid = BlockUuid("A"),  level = 1, position = "a1", createdAt = now, updatedAt = now),
            Block(uuid = BlockUuid("D"), pageUuid = PageUuid("p"), content = "D", parentUuid = BlockUuid("C"),  level = 2, position = "a0", createdAt = now, updatedAt = now)
        )

        // Dragging A — all three children/grandchildren are included
        val descendantsOfA = allDescendants(setOf("A"), blocks)
        assertTrue("B" in descendantsOfA, "B is a descendant of A")
        assertTrue("C" in descendantsOfA, "C is a descendant of A")
        assertTrue("D" in descendantsOfA, "D is a descendant of A (via C)")

        // Dragging B and C together — D (child of C) is included, but A is not
        val descendantsOfBC = allDescendants(setOf("B", "C"), blocks)
        assertTrue("D" in descendantsOfBC, "D is a descendant when dragging C")
        assertFalse("A" in descendantsOfBC, "A is NOT a descendant when dragging B and C")
    }

    // ---------------------------------------------------------------------------
    // Test 4 (docs/ux/block-reorder-permutations.md §8 punch-list item 6):
    // multi-selecting an ancestor AND one of its own descendants and dragging
    // together must move only the ancestor — the descendant "tags along"
    // implicitly (still a child of the moved ancestor) rather than being
    // moved a second time as an explicit sibling of the new parent.
    // Exercises the real BlockStateManager (selection + subtreeDedup +
    // moveSelectedBlocks), not a duplicated helper.
    // ---------------------------------------------------------------------------

    @Test
    fun movingAncestorAndDescendantTogether_onlyMovesAncestor() = runBlocking {
        val blockA = Block(
            uuid = BlockUuid("A"), pageUuid = PageUuid("p"), content = "A",
            parentUuid = null, level = 0, position = "a0", createdAt = now, updatedAt = now
        )
        val blockB = Block(
            uuid = BlockUuid("B"), pageUuid = PageUuid("p"), content = "B",
            parentUuid = BlockUuid("A"), level = 1, position = "a0", createdAt = now, updatedAt = now
        )
        val target = Block(
            uuid = BlockUuid("TARGET"), pageUuid = PageUuid("p"), content = "Target",
            parentUuid = null, level = 0, position = "a1", createdAt = now, updatedAt = now
        )
        val blockRepo = FakeBlockRepository(mapOf("p" to listOf(blockA, blockB, target)))
        val pageRepo = PopulatedFakePageRepository()
        val fileSystem = FakeFileSystem()
        val graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val blockStateManager = BlockStateManager(blockRepo, graphLoader, scope)

        blockStateManager.observePage(PageUuid("p"))
        blockStateManager.enterSelectionMode(BlockUuid("A"))
        blockStateManager.toggleBlockSelection(BlockUuid("B"))

        blockStateManager.moveSelectedBlocks(BlockUuid("TARGET"), null).join()

        val finalBlocks = blockRepo.getBlocksForPage(PageUuid("p")).first().getOrNull()
            ?: error("expected blocks")
        val movedA = finalBlocks.find { it.uuid.value == "A" } ?: error("A missing")
        val movedB = finalBlocks.find { it.uuid.value == "B" } ?: error("B missing")

        assertEquals(BlockUuid("TARGET"), movedA.parentUuid, "A should move under TARGET")
        assertEquals(BlockUuid("A"), movedB.parentUuid, "B should remain a child of A, not be moved separately")
        assertEquals(3, finalBlocks.size, "no blocks should be lost or duplicated by the move")
    }
}
