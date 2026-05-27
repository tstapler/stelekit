package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block

/**
 * Write operations on the block store.
 * All methods are gated behind [DirectRepositoryWrite] and must be routed
 * through [dev.stapler.stelekit.db.DatabaseWriteActor].
 */
interface BlockWriteRepository {

    /**
     * Save a new or updated block
     */
    @DirectRepositoryWrite
    suspend fun saveBlock(block: Block): Either<DomainError, Unit>

    /**
     * Save multiple blocks in a batch operation
     */
    @DirectRepositoryWrite
    suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, Unit>

    /**
     * Update only the content of a block. Does NOT touch structural fields
     * (parentUuid, position, level, leftUuid), eliminating the race condition
     * where a full saveBlock() with stale structural fields clobbers concurrent
     * indent/outdent/move operations.
     */
    @DirectRepositoryWrite
    suspend fun updateBlockContentOnly(blockUuid: String, content: String): Either<DomainError, Unit>

    /**
     * Update only the properties of a block. Does NOT touch content or structural fields.
     */
    @DirectRepositoryWrite
    suspend fun updateBlockPropertiesOnly(blockUuid: String, properties: Map<String, String>): Either<DomainError, Unit>

    /**
     * Delete a block and optionally its children
     */
    @DirectRepositoryWrite
    suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean = false): Either<DomainError, Unit>

    /**
     * Delete multiple blocks and optionally their children in a single atomic operation.
     * Chain repair is performed for each deletion to maintain linked-list integrity.
     */
    @DirectRepositoryWrite
    suspend fun deleteBulk(blockUuids: List<String>, deleteChildren: Boolean = true): Either<DomainError, Unit>

    /**
     * Delete all blocks associated with a specific page
     */
    @DirectRepositoryWrite
    suspend fun deleteBlocksForPage(pageUuid: String): Either<DomainError, Unit>

    /**
     * Delete all blocks for multiple pages in a single transaction.
     * Significantly faster than calling [deleteBlocksForPage] in a loop during bulk loads.
     */
    @DirectRepositoryWrite
    suspend fun deleteBlocksForPages(pageUuids: List<String>): Either<DomainError, Unit>

    /**
     * Merge two blocks atomically
     */
    @DirectRepositoryWrite
    suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Either<DomainError, Unit>

    /**
     * Split a block into two atomically at the given cursor position.
     *
     * @param newBlockUuid UUID to assign to the newly-created block. When provided (optimistic
     *   path) the caller and the repository use the same UUID, eliminating the need for a
     *   post-split UUID-correction pass in [BlockStateManager].
     */
    @DirectRepositoryWrite
    suspend fun splitBlock(
        blockUuid: String,
        cursorPosition: Int,
        newBlockUuid: String? = null,
    ): Either<DomainError, Block>

    /**
     * Clear all blocks from the repository
     */
    @DirectRepositoryWrite
    suspend fun clear(): Either<DomainError, Unit>
}
