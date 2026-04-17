package dev.stapler.stelekit.ui

import dev.stapler.stelekit.model.Block
import kotlin.test.Test
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
                blocks.filter { it.parentUuid == current }.forEach {
                    result.add(it.uuid)
                    queue.add(it.uuid)
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
            uuid = "A",
            pageUuid = "page1",
            content = "Block A",
            parentUuid = null,
            level = 0,
            position = 0,
            createdAt = now,
            updatedAt = now
        )
        val blockB = Block(
            uuid = "B",
            pageUuid = "page1",
            content = "Block B",
            parentUuid = "A",
            level = 1,
            position = 0,
            createdAt = now,
            updatedAt = now
        )
        val blockC = Block(
            uuid = "C",
            pageUuid = "page1",
            content = "Block C",
            parentUuid = "B",
            level = 2,
            position = 0,
            createdAt = now,
            updatedAt = now
        )

        // Simulate moving C to a root position (parentUuid = null)
        val movedC = blockC.copy(parentUuid = null, level = 0)

        // After the move, C's parent should be null (promoted to root)
        assertTrue(movedC.parentUuid == null, "C should have no parent after move to root")
        // A and B are unaffected
        assertTrue(blockA.parentUuid == null, "A should remain at root")
        assertTrue(blockB.parentUuid == "A", "B should still be a child of A")
    }

    // ---------------------------------------------------------------------------
    // Test 2: own-subtree guard — single dragged block
    // ---------------------------------------------------------------------------

    @Test
    fun ownSubtreeGuard_identifiesDescendantsCorrectly() {
        // Hierarchy: A (root) → B (child of A) → C (child of B)
        val now = Clock.System.now()
        val blocks = listOf(
            Block(uuid = "A", pageUuid = "p", content = "A", parentUuid = null,  level = 0, position = 0, createdAt = now, updatedAt = now),
            Block(uuid = "B", pageUuid = "p", content = "B", parentUuid = "A",   level = 1, position = 0, createdAt = now, updatedAt = now),
            Block(uuid = "C", pageUuid = "p", content = "C", parentUuid = "B",   level = 2, position = 0, createdAt = now, updatedAt = now)
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
            Block(uuid = "A", pageUuid = "p", content = "A", parentUuid = null, level = 0, position = 0, createdAt = now, updatedAt = now),
            Block(uuid = "B", pageUuid = "p", content = "B", parentUuid = "A",  level = 1, position = 0, createdAt = now, updatedAt = now),
            Block(uuid = "C", pageUuid = "p", content = "C", parentUuid = "A",  level = 1, position = 1, createdAt = now, updatedAt = now),
            Block(uuid = "D", pageUuid = "p", content = "D", parentUuid = "C",  level = 2, position = 0, createdAt = now, updatedAt = now)
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
}
