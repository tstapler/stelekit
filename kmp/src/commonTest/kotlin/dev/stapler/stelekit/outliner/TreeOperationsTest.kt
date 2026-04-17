package dev.stapler.stelekit.outliner

import dev.stapler.stelekit.model.Block
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TreeOperationsTest {

    private fun createBlock(uuidSuffix: String, parentUuid: String? = null, leftUuid: String? = null, position: Int = 0, level: Int = 0): Block {
        val idStr = uuidSuffix.padStart(12, '0')
        return Block(
            uuid = "00000000-0000-0000-0000-$idStr",
            content = "Block $uuidSuffix",
            pageUuid = "page-1",
            parentUuid = parentUuid,
            leftUuid = leftUuid,
            position = position,
            level = level,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0)
        )
    }

    private fun uuid(suffix: String): String {
        return "00000000-0000-0000-0000-${suffix.padStart(12, '0')}"
    }

    @Test
    fun testIndent() {
        // Setup: B1 -> B2. Indent B2 to be child of B1.
        val b1 = createBlock("1")
        val b2 = createBlock("2", leftUuid = uuid("1"), position = 1)
        val siblings = listOf(b1, b2)

        // Indent B2. B1 has no children, so lastChildOfNewParent is null.
        val result = TreeOperations.indent(b2, siblings, null)
        assertNotNull(result)
        assertEquals(1, result.size) // No next sibling to update
        
        val indented = result[0]
        assertEquals(uuid("1"), indented.parentUuid)
        assertEquals(1, indented.level)
        assertNull(indented.leftUuid) // First child of B1
    }

    @Test
    fun testIndentWithGapClosing() {
        // Setup: B1 -> B2 -> B3
        val b1 = createBlock("1", position = 0)
        val b2 = createBlock("2", leftUuid = uuid("1"), position = 1)
        val b3 = createBlock("3", leftUuid = uuid("2"), position = 2)
        val siblings = listOf(b1, b2, b3)

        // Indent B2 into B1.
        val result = TreeOperations.indent(b2, siblings, null)
        assertNotNull(result)
        assertEquals(2, result.size) // B2 updated, B3 updated
        
        val indentedB2 = result.find { it.uuid == uuid("2") }!!
        val updatedB3 = result.find { it.uuid == uuid("3") }!!

        // Check B2
        assertEquals(uuid("1"), indentedB2.parentUuid)
        
        // Check B3: Should now point to B1 (closing the gap)
        assertEquals(uuid("1"), updatedB3.leftUuid)
    }

    @Test
    fun testIndentWithExistingChildren() {
        // Setup: B1 -> B2. B1 already has child C1.
        val b1 = createBlock("1")
        val b2 = createBlock("2", leftUuid = uuid("1"), position = 1)
        val c1 = createBlock("10", parentUuid = uuid("1"), level = 1) // Child of B1
        val siblings = listOf(b1, b2)

        // Indent B2 into B1. Provide C1 as the last child.
        val result = TreeOperations.indent(b2, siblings, lastChildOfNewParent = c1)
        assertNotNull(result)
        
        val indentedB2 = result[0]
        assertEquals(uuid("1"), indentedB2.parentUuid)
        assertEquals(uuid("10"), indentedB2.leftUuid) // Should follow C1
    }


    @Test
    fun testIndentFirstSiblingFails() {
        val b1 = createBlock("1")
        val result = TreeOperations.indent(b1, listOf(b1))
        assertNull(result)
    }

    @Test
    fun testOutdent() {
        // Setup: Parent -> B2 (Child). Parent is sibling of Uncle.
        val parent = createBlock("1", leftUuid = null)
        val uncle = createBlock("99", leftUuid = uuid("1")) // Follows Parent
        
        val b2 = createBlock("2", parentUuid = uuid("1"), level = 1, leftUuid = null) // First child of Parent
        val b3 = createBlock("3", parentUuid = uuid("1"), level = 1, leftUuid = uuid("2")) // Second child of Parent

        val siblings = listOf(b2, b3)
        val parentSiblings = listOf(parent, uncle)
        
        // Outdent B2. It should become a sibling of Parent, between Parent and Uncle.
        val result = TreeOperations.outdent(b2, parent, siblings, parentSiblings)
        assertNotNull(result)
        assertEquals(3, result.size) // B2, B3 (gap close), Uncle (gap open)
        
        val outdentedB2 = result.find { it.uuid == uuid("2") }!!
        val updatedB3 = result.find { it.uuid == uuid("3") }!!
        val updatedUncle = result.find { it.uuid == uuid("99") }!!

        // Check B2
        assertNull(outdentedB2.parentUuid) // Top level now
        assertEquals(0, outdentedB2.level)
        assertEquals(uuid("1"), outdentedB2.leftUuid) // Follows Parent

        // Check B3 (Gap closed in children list)
        assertNull(updatedB3.leftUuid) // Was pointing to 2, now first child (null)
        
        // Check Uncle (Gap opened in parent list)
        assertEquals(uuid("2"), updatedUncle.leftUuid) // Now follows B2
    }

    @Test
    fun testMoveUp() {
        val b1 = createBlock("1")
        val b2 = createBlock("2", leftUuid = uuid("1"), position = 1)
        val siblings = listOf(b1, b2)

        val result = TreeOperations.moveUp(b2, siblings)
        assertNotNull(result)
        assertEquals(2, result.size)
        
        val updatedB2 = result.find { it.uuid == uuid("2") }!!
        val updatedB1 = result.find { it.uuid == uuid("1") }!!

        assertNull(updatedB2.leftUuid)
        assertEquals(uuid("2"), updatedB1.leftUuid)
    }

    @Test
    fun testMoveDown() {
        val b1 = createBlock("1")
        val b2 = createBlock("2", leftUuid = uuid("1"), position = 1)
        val siblings = listOf(b1, b2)

        val result = TreeOperations.moveDown(b1, siblings)
        assertNotNull(result)
        assertEquals(2, result.size)

        val updatedB1 = result.find { it.uuid == uuid("1") }!!
        val updatedB2 = result.find { it.uuid == uuid("2") }!!

        assertEquals(uuid("2"), updatedB1.leftUuid)
        assertNull(updatedB2.leftUuid)
    }

    @Test
    fun testReorderSiblings() {
        val b1 = createBlock("1", leftUuid = uuid("99"), position = 5)
        val b2 = createBlock("2", leftUuid = uuid("1"), position = 6)
        val b3 = createBlock("3", leftUuid = uuid("2"), position = 7)
        
        val reordered = TreeOperations.reorderSiblings(listOf(b1, b2, b3))
        
        assertEquals(0, reordered[0].position)
        assertNull(reordered[0].leftUuid)
        
        assertEquals(1, reordered[1].position)
        assertEquals(uuid("1"), reordered[1].leftUuid)
        
        assertEquals(2, reordered[2].position)
        assertEquals(uuid("2"), reordered[2].leftUuid)
    }
}
