package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block

/**
 * Position-aware diff between the existing DB blocks for a page and a freshly parsed block list.
 *
 * Blocks are matched by UUID (which is now position-derived and stable across content edits).
 * A match is a SKIP if the content_hash is identical; otherwise it is an UPDATE.
 * Blocks present only in [parsed] are INSERTs; blocks only in [existing] are DELETEs.
 */
object DiffMerge {

    data class ExistingBlockSummary(
        val uuid: String,
        val contentHash: String?,
        val isLoaded: Boolean = true,
    )

    data class BlockDiff(
        val toInsert: List<Block>,
        val toUpdate: List<Block>,
        val toDelete: List<String>,   // UUIDs no longer present
        val unchanged: List<String>,  // UUIDs with matching contentHash
    )

    fun diff(existing: List<ExistingBlockSummary>, parsed: List<Block>): BlockDiff {
        val existingByUuid = existing.associateBy { it.uuid }
        val parsedByUuid = parsed.associateBy { it.uuid }

        val toInsert = mutableListOf<Block>()
        val toUpdate = mutableListOf<Block>()
        val unchanged = mutableListOf<String>()
        val toDelete = mutableListOf<String>()

        for (block in parsed) {
            val existingSummary = existingByUuid[block.uuid]
            when {
                existingSummary == null -> toInsert.add(block)
                // Force an update when upgrading a METADATA_ONLY stub to a fully-loaded block,
                // even if the content hash matches — isLoaded must be promoted to true.
                !existingSummary.isLoaded && block.isLoaded -> toUpdate.add(block)
                existingSummary.contentHash != null &&
                    existingSummary.contentHash == block.contentHash &&
                    existingSummary.isLoaded -> unchanged.add(block.uuid)
                else -> toUpdate.add(block)
            }
        }

        for (existingBlock in existing) {
            if (existingBlock.uuid !in parsedByUuid) {
                toDelete.add(existingBlock.uuid)
            }
        }

        return BlockDiff(
            toInsert = toInsert,
            toUpdate = toUpdate,
            toDelete = toDelete,
            unchanged = unchanged,
        )
    }
}
