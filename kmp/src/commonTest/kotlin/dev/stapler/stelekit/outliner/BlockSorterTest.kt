package dev.stapler.stelekit.outliner

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.util.TestUtils
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockSorterTest {
    
    @Test
    fun `test sort hierarchical`() {
        // Create unordered blocks
        // Root 2 (pos 1)
        // Root 1 (pos 0)
        //   - Child 1.2 (pos 1)
        //   - Child 1.1 (pos 0)
        //     - Grandchild (pos 0)
        
        val root2 = TestUtils.createBlock(uuid = "2", content = "Root 2", position = 1)
        val root1 = TestUtils.createBlock(uuid = "1", content = "Root 1", position = 0)
        val child1_2 = TestUtils.createBlock(uuid = "12", parentUuid = "1", content = "Child 1.2", position = 1)
        val child1_1 = TestUtils.createBlock(uuid = "11", parentUuid = "1", content = "Child 1.1", position = 0)
        val grandchild = TestUtils.createBlock(uuid = "111", parentUuid = "11", content = "Grandchild", position = 0)
        
        val input = listOf(root2, grandchild, child1_2, root1, child1_1) // Random order
        
        val sorted = BlockSorter.sort(input)
        
        // Expected order:
        // Root 1
        // Child 1.1
        // Grandchild
        // Child 1.2
        // Root 2
        
        assertEquals(5, sorted.size)
        assertEquals("Root 1", sorted[0].content)
        assertEquals("Child 1.1", sorted[1].content)
        assertEquals("Grandchild", sorted[2].content)
        assertEquals("Child 1.2", sorted[3].content)
        assertEquals("Root 2", sorted[4].content)
    }
}
