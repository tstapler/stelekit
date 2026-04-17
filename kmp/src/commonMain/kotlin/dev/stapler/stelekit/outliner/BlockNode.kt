package dev.stapler.stelekit.outliner

import dev.stapler.stelekit.model.Block

/**
 * Represents a node in the block tree structure.
 * This is an in-memory representation optimized for outliner operations,
 * distinct from the flat DB entity [Block].
 */
data class BlockNode(
    val id: Long,
    val uuid: String,
    val content: String,
    // Mutable pointers for tree manipulation
    var parent: BlockNode? = null,
    val children: MutableList<BlockNode> = mutableListOf(),
    // Reference to original block for properties not stored in tree
    val originalBlock: Block
) {
    override fun toString(): String {
        return "BlockNode(id=$id, uuid='$uuid', children=${children.size})"
    }
}
