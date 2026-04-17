package dev.stapler.stelekit.outliner

import dev.stapler.stelekit.model.Block

object BlockSorter {
    /**
     * Sorts a flat list of blocks into a hierarchical depth-first order (visual display order).
     * 
     * Algorithm:
     * 1. Group blocks by parentUuid.
     * 2. Find root blocks (parentUuid = null or parent not in list).
     * 3. Sort roots by position/index.
     * 4. Recursively append children (sorted by position) for each block.
     * 
     * Updated to use UUID-native storage.
     */
    fun sort(blocks: List<Block>): List<Block> {
        if (blocks.isEmpty()) return emptyList()

        val childrenByParent = blocks.groupBy { it.parentUuid }
        val allBlockUuids = blocks.map { it.uuid }.toSet()

        // Roots are blocks with no parent OR parent is not in the current list
        // Sort descending because we'll push them onto a stack (LIFO)
        val roots = blocks.filter { 
            it.parentUuid == null || !allBlockUuids.contains(it.parentUuid) 
        }.sortedWith(compareByDescending<Block> { it.position }.thenByDescending { it.uuid })

        val result = mutableListOf<Block>()
        val visited = mutableSetOf<String>()
        // Stack stores pair of (Block, ActualLevel) to repair levels during traversal
        val stack = mutableListOf<Pair<Block, Int>>()
        
        roots.forEach { stack.add(it to 0) }
        
        while (stack.isNotEmpty()) {
            val (block, actualLevel) = stack.removeAt(stack.size - 1)
            if (visited.contains(block.uuid)) continue
            
            visited.add(block.uuid)
            
            // Repair the level if it doesn't match the actual depth in the hierarchy
            val repairedBlock = if (block.level != actualLevel) {
                block.copy(level = actualLevel)
            } else {
                block
            }
            result.add(repairedBlock)
            
            // Push children in reverse order so the first child is popped first
            val children = childrenByParent[block.uuid]
                ?.sortedWith(compareByDescending<Block> { it.position }.thenByDescending { it.uuid }) 
                ?: emptyList()
            
            children.forEach { stack.add(it to actualLevel + 1) }
        }

        // Sanity check: If we missed any blocks (e.g. cycles), add them at the end
        if (result.size < blocks.size) {
            val remaining = blocks.filter { !visited.contains(it.uuid) }
            println("BlockSorter WARNING: ${remaining.size} orphaned blocks found (orphaned from hierarchy or cycle). Appending to end.")
            remaining.forEach { println(" - Orphan: ${it.content} (Parent: ${it.parentUuid})") }
            result.addAll(remaining)
        }

        return result
    }
}
