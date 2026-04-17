package dev.stapler.stelekit.outliner

import dev.stapler.stelekit.model.Block
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockSorterLevelRepairTest {

    private fun createBlock(uuidSuffix: Long, parentUuidSuffix: Long?, content: String, position: Int, level: Int): Block {
        val uuid = "00000000-0000-0000-0000-${uuidSuffix.toString().padStart(12, '0')}"
        val pageUuid = "00000000-0000-0000-0000-000000000001"
        val parentUuid = parentUuidSuffix?.let { "00000000-0000-0000-0000-${it.toString().padStart(12, '0')}" }
        return Block(
            uuid = uuid,
            pageUuid = pageUuid,
            parentUuid = parentUuid,
            content = content,
            level = level,
            position = position,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            properties = emptyMap()
        )
    }

    @Test
    fun `test level repair`() {
        // Create blocks with incorrect levels
        // Root (level 10 - WRONG, should be 0)
        //   - Child (level 0 - WRONG, should be 1)
        //     - Grandchild (level 5 - WRONG, should be 2)
        
        val root = createBlock(1, null, "Root", 0, 10)
        val child = createBlock(2, 1, "Child", 0, 0)
        val grandchild = createBlock(3, 2, "Grandchild", 0, 5)
        
        val input = listOf(grandchild, root, child)
        val sorted = BlockSorter.sort(input)
        
        assertEquals(3, sorted.size)
        
        assertEquals("Root", sorted[0].content)
        assertEquals(0, sorted[0].level, "Root level should be repaired to 0")
        
        assertEquals("Child", sorted[1].content)
        assertEquals(1, sorted[1].level, "Child level should be repaired to 1")
        
        assertEquals("Grandchild", sorted[2].content)
        assertEquals(2, sorted[2].level, "Grandchild level should be repaired to 2")
    }

    @Test
    fun `test stable sorting with duplicate positions`() {
        // Blocks with same position should be sorted by UUID descending to be stable
        val b1 = createBlock(1, null, "B1", 0, 0)
        val b2 = createBlock(2, null, "B2", 0, 0)
        val b3 = createBlock(3, null, "B3", 0, 0)
        
        val sorted = BlockSorter.sort(listOf(b1, b2, b3))
        println("Sorted UUIDs: ${sorted.map { it.uuid }}")
        
        // roots.sortedWith(compareByDescending<Block> { it.position }.thenByDescending { it.uuid })
        // stack order (bottom to top): UUID 3, UUID 2, UUID 1
        // pop order: UUID 1, UUID 2, UUID 3
        
        assertEquals(3, sorted.size)
        assertEquals("00000000-0000-0000-0000-000000000001", sorted[0].uuid)
        assertEquals("00000000-0000-0000-0000-000000000002", sorted[1].uuid)
        assertEquals("00000000-0000-0000-0000-000000000003", sorted[2].uuid)
    }
}
