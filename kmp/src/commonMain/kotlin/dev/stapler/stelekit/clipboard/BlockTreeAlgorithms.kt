package dev.stapler.stelekit.clipboard

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.util.FractionalIndexing
import kotlin.time.Instant

object BlockTreeAlgorithms {

    /** Pre-index blocks by UUID for O(1) lookups (replaces allBlocks.find in recursive calls). */
    fun indexByUuid(blocks: List<Block>): Map<String, Block> =
        blocks.associateBy { it.uuid.value }

    /** Pre-index blocks by parentUuid for O(1) child lookups. */
    fun indexChildren(blocks: List<Block>): Map<String?, List<Block>> =
        blocks.groupBy { it.parentUuid?.value }

    /**
     * Collect a block and all its descendants in DFS pre-order.
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
        val minClipLevel = clipBlocks.minOfOrNull { it.level } ?: 0
        val insertionLevel = afterBlock.level

        val result = mutableListOf<Block>()

        fun build(original: Block, newParentUuid: BlockUuid?, position: String, prevLeftUuid: BlockUuid?) {
            val newUuidStr = uuidMap[original.uuid.value] ?: return
            val newUuid = BlockUuid(newUuidStr)
            result.add(
                original.copy(
                    uuid = newUuid,
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
            val children = (clipChildrenByParent[original.uuid] ?: emptyList())
                .sortedBy { it.position }
            var prevChildPos: String? = null
            var prevChildLeftUuid: BlockUuid? = null
            children.forEach { child ->
                val childPos = FractionalIndexing.generateKeyBetween(prevChildPos, null)
                prevChildPos = childPos
                build(child, newUuid, childPos, prevChildLeftUuid)
                prevChildLeftUuid = uuidMap[child.uuid.value]?.let { BlockUuid(it) }
            }
        }

        var prevRootPos: String? = afterBlock.position
        var prevRootLeftUuid: BlockUuid? = afterBlock.uuid
        rootBlocks.forEach { root ->
            val pos = FractionalIndexing.generateKeyBetween(prevRootPos, nextSiblingPos)
            prevRootPos = pos
            build(root, insertionParentUuid?.let { BlockUuid(it) }, pos, prevRootLeftUuid)
            prevRootLeftUuid = uuidMap[root.uuid.value]?.let { BlockUuid(it) }
        }

        return result
    }

    data class MergeResult(
        val mergedBlocks: List<Block>,
        val clearedDirtyUuids: Set<String>,
    )

    /**
     * Merge incoming DB blocks with local state using dirty-set semantics.
     * Dirty blocks keep their local version; clean blocks accept the DB version.
     * Pending new blocks (optimistically inserted, DB write still in-flight) are
     * preserved at the end of the list so they don't disappear on reactive re-emissions.
     */
    fun mergeBlocks(
        localBlocks: List<Block>,
        incomingBlocks: List<Block>,
        dirtyBlocks: Map<String, Long>,
        pendingNewBlockUuids: Set<String>,
    ): MergeResult {
        val clearedDirty = mutableSetOf<String>()
        val merged = incomingBlocks.map { incoming ->
            val dirtyVersion = dirtyBlocks[incoming.uuid.value]
            if (dirtyVersion != null && dirtyVersion > incoming.version) {
                val local = localBlocks.find { it.uuid == incoming.uuid }
                local ?: incoming
            } else {
                if (dirtyBlocks.containsKey(incoming.uuid.value)) {
                    clearedDirty.add(incoming.uuid.value)
                }
                incoming
            }
        }
        val incomingUuids = incomingBlocks.mapTo(HashSet()) { it.uuid }
        val pending = localBlocks.filter { it.uuid.value in pendingNewBlockUuids && it.uuid !in incomingUuids }
        return MergeResult(
            mergedBlocks = if (pending.isEmpty()) merged else merged + pending,
            clearedDirtyUuids = clearedDirty,
        )
    }

    /**
     * Reconciles a single incoming block against existing page blocks.
     */
    fun mergeIncomingBlock(
        pageBlocks: List<Block>,
        incoming: Block,
        dirtyBlocks: Map<String, Long>,
    ): MergeResult {
        val clearedDirty = mutableSetOf<String>()
        if (pageBlocks.none { it.uuid == incoming.uuid }) {
            return MergeResult(pageBlocks + incoming, clearedDirty)
        }
        val merged = pageBlocks.map { block ->
            if (block.uuid != incoming.uuid) {
                block
            } else {
                val dirtyVersion = dirtyBlocks[block.uuid.value]
                if (dirtyVersion != null && dirtyVersion > incoming.version) {
                    block
                } else {
                    if (dirtyBlocks.containsKey(block.uuid.value)) {
                        clearedDirty.add(block.uuid.value)
                    }
                    incoming
                }
            }
        }
        return MergeResult(merged, clearedDirty)
    }
}
