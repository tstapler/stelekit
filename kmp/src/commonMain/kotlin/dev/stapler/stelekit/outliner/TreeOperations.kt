package dev.stapler.stelekit.outliner

import dev.stapler.stelekit.model.Block

/**
 * Pure logic for tree manipulation operations.
 * Updated to use UUID-native storage.
 */
object TreeOperations {

    /**
     * Indents a block by moving it into the children of its preceding sibling.
     * Returns a list of all affected blocks: the indented block and its former next sibling (if any).
     *
     * @param block The block to indent
     * @param siblings The list of blocks at the current level (must include [block] and its predecessor)
     * @param lastChildOfNewParent The last child of the new parent (the preceding sibling), used to set the correct leftUuid.
     */
    fun indent(
        block: Block,
        siblings: List<Block>,
        lastChildOfNewParent: Block? = null
    ): List<Block>? {
        val index = siblings.indexOfFirst { it.uuid == block.uuid }
        if (index <= 0) return null // Cannot indent the first sibling

        val newParent = siblings[index - 1]
        val nextSibling = siblings.getOrNull(index + 1)

        val updates = mutableListOf<Block>()

        // 1. Update the indented block
        // It becomes the last child of the new parent
        updates.add(
            block.copy(
                parentUuid = newParent.uuid,
                level = newParent.level + 1,
                leftUuid = lastChildOfNewParent?.uuid // If null, it becomes the first child
            )
        )

        // 2. Close the gap in the old list
        // The next sibling should now point to the new parent (which was the block's left sibling)
        if (nextSibling != null) {
            updates.add(
                nextSibling.copy(
                    leftUuid = newParent.uuid // newParent was the block's left sibling
                )
            )
        }

        return updates
    }

    /**
     * Outdents a block by moving it to become a sibling of its parent.
     * Returns a list of all affected blocks: the outdented block, its former next sibling (closing gap), 
     * and its new next sibling (opening gap).
     * 
     * @param block The block to outdent
     * @param parent The current parent of the block
     * @param siblings The list of blocks at the current level (siblings of [block])
     * @param parentSiblings The list of blocks at the parent's level (to determine where to insert)
     */
    fun outdent(
        block: Block,
        parent: Block?,
        siblings: List<Block>,
        parentSiblings: List<Block>
    ): List<Block>? {
        if (parent == null) return null // Already at top level

        val updates = mutableListOf<Block>()
        
        // Find current next sibling to close the gap
        val index = siblings.indexOfFirst { it.uuid == block.uuid }
        val nextSibling = siblings.getOrNull(index + 1)
        val prevSibling = siblings.getOrNull(index - 1) // Should match block.leftUuid

        // 1. Update the outdented block
        // It moves to be after the parent
        updates.add(
            block.copy(
                parentUuid = parent.parentUuid,
                level = parent.level,
                leftUuid = parent.uuid
            )
        )

        // 2. Close the gap in the old list (children of parent)
        if (nextSibling != null) {
            updates.add(
                nextSibling.copy(
                    leftUuid = prevSibling?.uuid // Points to whatever was before the block (or null if block was first)
                )
            )
        }

        // 3. Open gap in new list (siblings of parent)
        // We need to update the block that currently follows the parent
        val parentIndex = parentSiblings.indexOfFirst { it.uuid == parent.uuid }
        val parentNextSibling = parentSiblings.getOrNull(parentIndex + 1)
        
        if (parentNextSibling != null) {
            updates.add(
                parentNextSibling.copy(
                    leftUuid = block.uuid // Now points to the outdented block
                )
            )
        }

        return updates
    }

    /**
     * Moves a block up among its siblings.
     */
    fun moveUp(
        block: Block,
        siblings: List<Block>
    ): List<Block>? {
        val index = siblings.indexOfFirst { it.uuid == block.uuid }
        if (index <= 0) return null

        val prevSibling = siblings[index - 1]
        val nextSibling = siblings.getOrNull(index + 1)
        
        val updates = mutableListOf<Block>()
        
        // Swap positions and leftUuids
        // Current block (B) takes previous sibling's (A) leftUuid and position
        updates.add(block.copy(
            leftUuid = prevSibling.leftUuid,
            position = prevSibling.position
        ))
        
        // Previous sibling (A) now follows current block (B)
        updates.add(prevSibling.copy(
            leftUuid = block.uuid,
            position = block.position
        ))
        
        // If there was a next sibling (C) following B, it now follows A
        if (nextSibling != null) {
            updates.add(nextSibling.copy(
                leftUuid = prevSibling.uuid
            ))
        }
        
        return updates
    }

    /**
     * Moves a block down among its siblings.
     */
    fun moveDown(
        block: Block,
        siblings: List<Block>
    ): List<Block>? {
        val index = siblings.indexOfFirst { it.uuid == block.uuid }
        if (index < 0 || index >= siblings.size - 1) return null

        val nextSibling = siblings[index + 1]
        val afterNextSibling = siblings.getOrNull(index + 2)
        
        val updates = mutableListOf<Block>()

        // Current block (A) now follows next sibling (B)
        updates.add(block.copy(
            leftUuid = nextSibling.uuid,
            position = nextSibling.position
        ))
        
        // Next sibling (B) takes current block's (A) leftUuid and position
        updates.add(nextSibling.copy(
            leftUuid = block.leftUuid,
            position = block.position
        ))
        
        // If there was a block (C) following B, it now follows A
        if (afterNextSibling != null) {
            updates.add(afterNextSibling.copy(
                leftUuid = block.uuid
            ))
        }
        
        return updates
    }

    /**
     * Recursively updates the level of a block and all its descendants.
     */
    fun updateLevels(
        block: Block,
        newLevel: Int,
        childrenProvider: (String) -> List<Block>
    ): List<Block> {
        val updatedBlock = block.copy(level = newLevel)
        val children = childrenProvider(block.uuid)
        
        val updatedChildren = children.flatMap { child ->
            updateLevels(child, newLevel + 1, childrenProvider)
        }
        
        return listOf(updatedBlock) + updatedChildren
    }

    /**
     * Reorders a list of siblings to ensure consistent leftUuid and position values.
     */
    fun reorderSiblings(siblings: List<Block>): List<Block> {
        var currentLeftUuid: String? = null
        return siblings.mapIndexed { index, b ->
            val updated = b.copy(
                leftUuid = currentLeftUuid,
                position = index
            )
            currentLeftUuid = updated.uuid
            updated
        }
    }
}
