package dev.stapler.stelekit.clipboard

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.util.FractionalIndexing
import kotlin.time.Instant

object BlockTreeAlgorithms {

    /** Pre-index blocks by UUID for O(1) lookups (replaces allBlocks.find in recursive calls). */
    fun indexByUuid(blocks: List<Block>): Map<String, Block> =
        blocks.associateBy { it.uuid.value }

    /** Pre-index blocks by parentUuid for O(1) child lookups. */
    fun indexChildren(blocks: List<Block>): Map<String?, List<Block>> =
        blocks.groupBy { it.parentUuid }

    /**
     * Collect a block and all its descendants in BFS order.
     * [byUuid] and [childrenByParent] are pre-built indexes — callers must provide them
     * to avoid repeated O(n) scans across recursive calls.
     */
    fun collectSubtree(
        rootUuid: String,
        byUuid: Map<String, Block>,
        childrenByParent: Map<String?, List<Block>>,
    ): List<Block> {
        val root = byUuid[rootUuid] ?: return emptyList()
        val children = childrenByParent[rootUuid] ?: emptyList()
        return listOf(root) + children.flatMap { collectSubtree(it.uuid.value, byUuid, childrenByParent) }
    }

    /**
     * Find the minimal set of clipboard roots — blocks whose ancestor is NOT also in the selection.
     * Input [uuids] is the raw selection; [childrenByParent] is the page-wide child index.
     */
    fun findRoots(uuids: Set<String>, childrenByParent: Map<String?, List<Block>>): Set<String> {
        fun hasSelectedAncestor(block: Block, byUuid: Map<String, Block>): Boolean {
            var current = block.parentUuid?.let { byUuid[it] }
            while (current != null) {
                if (current.uuid.value in uuids) return true
                current = current.parentUuid?.let { byUuid[it] }
            }
            return false
        }
        val byUuid = childrenByParent.values.flatten().associateBy { it.uuid.value }
        return uuids.filter { uuid ->
            val block = byUuid[uuid] ?: return@filter false
            !hasSelectedAncestor(block, byUuid)
        }.toSet()
    }

    /**
     * Build the list of pasted blocks with new UUIDs, remapped parent/left references,
     * adjusted positions, and level normalization.
     *
     * @param clipBlocks the clipboard blocks (COPY of original blocks)
     * @param rootBlocks subset of clipBlocks that are paste roots (no parent in clipboard)
     * @param uuidMap old-uuid → new-uuid remapping (pre-built by caller)
     * @param afterBlock the destination block (pasted items go after this)
     * @param insertionParentUuid parentUuid for root pasted blocks
     * @param nextSiblingPos position of the first sibling after afterBlock (null = afterBlock is last)
     * @param now timestamp for createdAt/updatedAt
     */
    fun buildPastedTree(
        clipBlocks: List<Block>,
        rootBlocks: List<Block>,
        uuidMap: Map<String, String>,
        afterBlock: Block,
        insertionParentUuid: String?,
        nextSiblingPos: String?,
        now: Instant,
    ): List<Block> {
        val clipChildrenByParent = clipBlocks.groupBy { it.parentUuid }
        val minClipLevel = clipBlocks.minOf { it.level }
        val insertionLevel = afterBlock.level

        val result = mutableListOf<Block>()

        fun build(original: Block, newParentUuid: String?, position: String, prevLeftUuid: String?) {
            val newUuid = uuidMap[original.uuid.value] ?: return
            result.add(
                original.copy(
                    uuid = dev.stapler.stelekit.model.BlockUuid(newUuid),
                    pageUuid = afterBlock.pageUuid,
                    parentUuid = newParentUuid,
                    leftUuid = prevLeftUuid,
                    position = position,
                    level = original.level - minClipLevel + insertionLevel,
                    createdAt = now,
                    updatedAt = now,
                    isLoaded = true,
                )
            )
            val children = (clipChildrenByParent[original.uuid.value] ?: emptyList())
                .sortedBy { it.position }
            var prevChildPos: String? = null
            var prevChildLeftUuid: String? = null
            children.forEach { child ->
                val childPos = FractionalIndexing.generateKeyBetween(prevChildPos, null)
                prevChildPos = childPos
                build(child, newUuid, childPos, prevChildLeftUuid)
                prevChildLeftUuid = uuidMap[child.uuid.value]
            }
        }

        var prevRootPos: String? = afterBlock.position
        var prevRootLeftUuid: String? = afterBlock.uuid.value
        rootBlocks.forEach { root ->
            val pos = FractionalIndexing.generateKeyBetween(prevRootPos, nextSiblingPos)
            prevRootPos = pos
            build(root, insertionParentUuid, pos, prevRootLeftUuid)
            prevRootLeftUuid = uuidMap[root.uuid.value]
        }

        return result
    }
}
