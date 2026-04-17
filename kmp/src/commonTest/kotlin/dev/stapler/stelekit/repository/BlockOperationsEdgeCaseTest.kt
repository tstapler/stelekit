package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Edge case tests for block operations to prevent regression of bugs:
 * - Cross-page block isolation (pageId filtering)
 * - Position/leftId consistency after operations
 * - First/last block handling
 * - Sibling reordering after delete
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockOperationsEdgeCaseTest {

    private lateinit var repository: DatascriptBlockRepository
    private val now = Clock.System.now()

    @BeforeTest
    fun setup() {
        repository = DatascriptBlockRepository()
    }

    private fun createBlock(
        uuidSuffix: Long,
        pageUuidSuffix: Long = 1,
        parentUuidSuffix: Long? = null,
        position: Int,
        level: Int = 0,
        content: String = "Block $uuidSuffix"
    ): Block {
        val uuid = "00000000-0000-0000-0000-${uuidSuffix.toString().padStart(12, '0')}"
        val pageUuid = "00000000-0000-0000-0000-${pageUuidSuffix.toString().padStart(12, '0')}"
        val parentUuid = parentUuidSuffix?.let { "00000000-0000-0000-0000-${it.toString().padStart(12, '0')}" }

        return Block(
            uuid = uuid,
            pageUuid = pageUuid,
            content = content,
            parentUuid = parentUuid,
            position = position,
            level = level,
            leftUuid = null,
            createdAt = now,
            updatedAt = now
        )
    }

    // ============================================================
    // CROSS-PAGE ISOLATION TESTS
    // Bug: Siblings were queried without pageUuid filter, mixing blocks from different pages
    // ============================================================

    @Test
    fun `indent should only consider siblings from same page`() = runTest {
        // Page 1: A, B (B should indent into A)
        val page1BlockA = createBlock(1, pageUuidSuffix = 1, position = 0, content = "Page1-A")
        val page1BlockB = createBlock(2, pageUuidSuffix = 1, position = 1, content = "Page1-B")

        // Page 2: X, Y (should NOT interfere with page 1 operations)
        val page2BlockX = createBlock(3, pageUuidSuffix = 2, position = 0, content = "Page2-X")
        val page2BlockY = createBlock(4, pageUuidSuffix = 2, position = 1, content = "Page2-Y")

        repository.saveBlock(page1BlockA)
        repository.saveBlock(page1BlockB)
        repository.saveBlock(page2BlockX)
        repository.saveBlock(page2BlockY)

        // Indent B into A (both on page 1)
        val result = repository.indentBlock(page1BlockB.uuid)
        assertTrue(result.isSuccess)

        // Verify B is now child of A
        val blockB = repository.getBlockByUuid(page1BlockB.uuid).first().getOrNull()
        assertNotNull(blockB)
        assertEquals(page1BlockA.uuid, blockB.parentUuid, "B should be child of A")

        // Verify page 2 blocks are unchanged
        val page2Uuid = "00000000-0000-0000-0000-000000000002"
        val page2Blocks = repository.getBlocksForPage(page2Uuid).first().getOrNull() ?: emptyList()
        assertEquals(2, page2Blocks.size)
        assertEquals(null, page2Blocks[0].parentUuid, "Page 2 blocks should be unchanged")
        assertEquals(null, page2Blocks[1].parentUuid, "Page 2 blocks should be unchanged")
    }

    @Test
    fun `outdent should only consider siblings from same page`() = runTest {
        // Page 1: Parent -> Child (Child should outdent to root)
        val parent = createBlock(1, pageUuidSuffix = 1, position = 0)
        val child = createBlock(2, pageUuidSuffix = 1, parentUuidSuffix = 1, position = 0, level = 1)

        // Page 2: Another block at root (should NOT be considered as sibling)
        val page2Root = createBlock(3, pageUuidSuffix = 2, position = 0)

        repository.saveBlock(parent)
        repository.saveBlock(child)
        repository.saveBlock(page2Root)

        // Outdent child
        val result = repository.outdentBlock(child.uuid)
        assertTrue(result.isSuccess)

        // Verify child is now at root level of page 1
        val outdentedChild = repository.getBlockByUuid(child.uuid).first().getOrNull()
        assertNotNull(outdentedChild)
        assertEquals(null, outdentedChild.parentUuid, "Child should be at root after outdent")
        val page1Uuid = "00000000-0000-0000-0000-000000000001"
        assertEquals(page1Uuid, outdentedChild.pageUuid, "Child should remain on page 1")
    }

    @Test
    fun `getBlockSiblings should only return siblings from same page`() = runTest {
        // Page 1: A, B
        val page1A = createBlock(1, pageUuidSuffix = 1, position = 0)
        val page1B = createBlock(2, pageUuidSuffix = 1, position = 1)

        // Page 2: X (same parentUuid=null, but different page)
        val page2X = createBlock(3, pageUuidSuffix = 2, position = 0)

        repository.saveBlock(page1A)
        repository.saveBlock(page1B)
        repository.saveBlock(page2X)

        // Get siblings of A (should only return B, not X)
        val siblings = repository.getBlockSiblings(page1A.uuid).first().getOrNull() ?: emptyList()
        assertEquals(1, siblings.size, "Should only have 1 sibling from same page")
        assertEquals(page1B.uuid, siblings[0].uuid, "Sibling should be B from page 1")
    }

    // ============================================================
    // POSITION/LEFTUUID CONSISTENCY TESTS
    // Bug: Operations updated leftUuid but not position, causing sort order issues
    // ============================================================

    @Test
    fun `outdent should update positions for new siblings`() = runTest {
        // Setup: Parent -> [Child1, Child2]. Outdent Child1.
        val parent = createBlock(1, pageUuidSuffix = 1, position = 0)
        val child1 = createBlock(2, pageUuidSuffix = 1, parentUuidSuffix = 1, position = 0, level = 1)
        val child2 = createBlock(3, pageUuidSuffix = 1, parentUuidSuffix = 1, position = 1, level = 1)

        repository.saveBlock(parent)
        repository.saveBlock(child1)
        repository.saveBlock(child2)

        // Outdent Child1
        repository.outdentBlock(child1.uuid)

        // Get all root blocks (should be: Parent, Child1)
        val page1Uuid = "00000000-0000-0000-0000-000000000001"
        val rootBlocks = repository.getBlocksForPage(page1Uuid).first().getOrNull()
            ?.filter { it.parentUuid == null }
            ?.sortedBy { it.position }
            ?: emptyList()

        // Verify positions are sequential
        assertEquals(2, rootBlocks.size)
        assertEquals(0, rootBlocks[0].position, "First root block should have position 0")
        assertEquals(1, rootBlocks[1].position, "Second root block should have position 1")
    }

    @Test
    fun `indent should update positions for remaining siblings`() = runTest {
        // Setup: A, B, C. Indent B into A.
        val blockA = createBlock(1, pageUuidSuffix = 1, position = 0)
        val blockB = createBlock(2, pageUuidSuffix = 1, position = 1)
        val blockC = createBlock(3, pageUuidSuffix = 1, position = 2)

        repository.saveBlock(blockA)
        repository.saveBlock(blockB)
        repository.saveBlock(blockC)

        // Indent B into A
        repository.indentBlock(blockB.uuid)

        // Get root blocks (should be: A, C)
        val page1Uuid = "00000000-0000-0000-0000-000000000001"
        val rootBlocks = repository.getBlocksForPage(page1Uuid).first().getOrNull()
            ?.filter { it.parentUuid == null }
            ?.sortedBy { it.position }
            ?: emptyList()

        // Verify positions are sequential (no gaps)
        assertEquals(2, rootBlocks.size)
        assertEquals(0, rootBlocks[0].position)
        assertEquals(1, rootBlocks[1].position, "C should have position 1 after B is indented")
    }

    // ============================================================
    // DELETE BLOCK EDGE CASES
    // ============================================================

    @Test
    fun `delete block should maintain valid sibling chain`() = runTest {
        // Setup: A, B, C. Delete B.
        val blockA = createBlock(1, pageUuidSuffix = 1, position = 0)
        val blockB = createBlock(2, pageUuidSuffix = 1, position = 1)
        val blockC = createBlock(3, pageUuidSuffix = 1, position = 2)

        repository.saveBlock(blockA)
        repository.saveBlock(blockB)
        repository.saveBlock(blockC)

        // Delete B
        repository.deleteBlock(blockB.uuid)

        // Verify remaining blocks
        val page1Uuid = "00000000-0000-0000-0000-000000000001"
        val remaining = repository.getBlocksForPage(page1Uuid).first().getOrNull()
            ?.sortedBy { it.position }
            ?: emptyList()

        assertEquals(2, remaining.size)
        assertEquals(blockA.uuid, remaining[0].uuid)
        assertEquals(blockC.uuid, remaining[1].uuid)
    }

    @Test
    fun `delete first block should work correctly`() = runTest {
        // Setup: A, B. Delete A.
        val blockA = createBlock(1, pageUuidSuffix = 1, position = 0)
        val blockB = createBlock(2, pageUuidSuffix = 1, position = 1)

        repository.saveBlock(blockA)
        repository.saveBlock(blockB)

        // Delete A
        repository.deleteBlock(blockA.uuid)

        // Verify B remains
        val page1Uuid = "00000000-0000-0000-0000-000000000001"
        val remaining = repository.getBlocksForPage(page1Uuid).first().getOrNull() ?: emptyList()
        assertEquals(1, remaining.size)
        assertEquals(blockB.uuid, remaining[0].uuid)
    }

    @Test
    fun `delete last block should work correctly`() = runTest {
        // Setup: A, B. Delete B.
        val blockA = createBlock(1, pageUuidSuffix = 1, position = 0)
        val blockB = createBlock(2, pageUuidSuffix = 1, position = 1)

        repository.saveBlock(blockA)
        repository.saveBlock(blockB)

        // Delete B
        repository.deleteBlock(blockB.uuid)

        // Verify A remains
        val page1Uuid = "00000000-0000-0000-0000-000000000001"
        val remaining = repository.getBlocksForPage(page1Uuid).first().getOrNull() ?: emptyList()
        assertEquals(1, remaining.size)
        assertEquals(blockA.uuid, remaining[0].uuid)
    }

    // ============================================================
    // MOVE OPERATIONS EDGE CASES
    // ============================================================

    @Test
    fun `moveUp at top of list should be no-op`() = runTest {
        val blockA = createBlock(1, pageUuidSuffix = 1, position = 0)
        val blockB = createBlock(2, pageUuidSuffix = 1, position = 1)

        repository.saveBlock(blockA)
        repository.saveBlock(blockB)

        // Try to move A up (already at top)
        val result = repository.moveBlockUp(blockA.uuid)
        assertTrue(result.isSuccess) // Should succeed but do nothing

        // Verify order unchanged
        val page1Uuid = "00000000-0000-0000-0000-000000000001"
        val blocks = repository.getBlocksForPage(page1Uuid).first().getOrNull()
            ?.sortedBy { it.position }
            ?: emptyList()

        assertEquals(blockA.uuid, blocks[0].uuid)
        assertEquals(blockB.uuid, blocks[1].uuid)
    }

    @Test
    fun `moveDown at bottom of list should be no-op`() = runTest {
        val blockA = createBlock(1, pageUuidSuffix = 1, position = 0)
        val blockB = createBlock(2, pageUuidSuffix = 1, position = 1)

        repository.saveBlock(blockA)
        repository.saveBlock(blockB)

        // Try to move B down (already at bottom)
        val result = repository.moveBlockDown(blockB.uuid)
        assertTrue(result.isSuccess) // Should succeed but do nothing

        // Verify order unchanged
        val page1Uuid = "00000000-0000-0000-0000-000000000001"
        val blocks = repository.getBlocksForPage(page1Uuid).first().getOrNull()
            ?.sortedBy { it.position }
            ?: emptyList()

        assertEquals(blockA.uuid, blocks[0].uuid)
        assertEquals(blockB.uuid, blocks[1].uuid)
    }

    // ============================================================
    // HIERARCHY PRESERVATION TESTS
    // ============================================================

    @Test
    fun `indent should set correct level`() = runTest {
        val parent = createBlock(1, pageUuidSuffix = 1, position = 0, level = 0)
        val sibling = createBlock(2, pageUuidSuffix = 1, position = 1, level = 0)

        repository.saveBlock(parent)
        repository.saveBlock(sibling)

        // Indent sibling into parent
        repository.indentBlock(sibling.uuid)

        val indented = repository.getBlockByUuid(sibling.uuid).first().getOrNull()
        assertNotNull(indented)
        assertEquals(1, indented.level, "Indented block should have level = parent.level + 1")
    }

    @Test
    fun `outdent should set correct level`() = runTest {
        val parent = createBlock(1, pageUuidSuffix = 1, position = 0, level = 0)
        val child = createBlock(2, pageUuidSuffix = 1, parentUuidSuffix = 1, position = 0, level = 1)

        repository.saveBlock(parent)
        repository.saveBlock(child)

        // Outdent child
        repository.outdentBlock(child.uuid)

        val outdented = repository.getBlockByUuid(child.uuid).first().getOrNull()
        assertNotNull(outdented)
        assertEquals(0, outdented.level, "Outdented block should have level = parent.level")
    }
}
