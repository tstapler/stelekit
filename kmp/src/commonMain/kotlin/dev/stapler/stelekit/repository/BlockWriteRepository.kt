package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid

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
     * Save multiple existing blocks using targeted UPDATE statements (not INSERT OR REPLACE).
     * Use for the warm-path diff.toUpdate list — avoids DELETE+INSERT FTS5 trigger storms.
     */
    @DirectRepositoryWrite
    suspend fun saveBlocksUpdate(blocks: List<Block>): Either<DomainError, Unit>

    /**
     * Diff-aware bulk save: INSERTs [toInsert] and UPDATEs [toUpdate] via separate SQL paths.
     * UPDATE fires blocks_au (scoped to content changes) instead of the INSERT OR REPLACE
     * double-trigger (blocks_ad + blocks_ai), halving FTS5 work for updated blocks.
     * Both lists are wrapped with FTS5 automerge=0 / merge pass to prevent compounding segment
     * merges during large page saves. Default impl delegates to saveBlocks for compatibility.
     */
    @DirectRepositoryWrite
    suspend fun saveBlocksDiff(toInsert: List<Block>, toUpdate: List<Block>): Either<DomainError, Unit> =
        saveBlocks(toInsert + toUpdate)

    /**
     * Fold WAL frames into the main DB file. Call after bulk imports to prevent WAL bloat
     * from slowing subsequent reads. No-op by default (non-SQLite backends).
     */
    @DirectRepositoryWrite
    suspend fun walCheckpoint() {}

    /**
     * Update only the content of a block. Does NOT touch structural fields
     * (parentUuid, position, level, leftUuid), eliminating the race condition
     * where a full saveBlock() with stale structural fields clobbers concurrent
     * indent/outdent/move operations.
     */
    @DirectRepositoryWrite
    suspend fun updateBlockContentOnly(blockUuid: BlockUuid, content: String): Either<DomainError, Unit>

    /**
     * Batch-update block content for a page rename. More efficient than N calls to
     * [updateBlockContentOnly]: single transaction, no per-block content re-read,
     * and backlink counts are recomputed only for the two pages whose counts changed.
     *
     * Implementations should override this for performance. The default delegates to
     * [updateBlockContentOnly] in a loop, which is correct but not optimized.
     *
     * @param updates list of (blockUuid, newContent) — the caller must supply the new content
     * @param oldPageName page being renamed (its backlink count decreases)
     * @param newPageName new page name (its backlink count increases)
     */
    @DirectRepositoryWrite
    suspend fun updateBlockContentsForRename(
        updates: List<Pair<BlockUuid, String>>,
        oldPageName: String,
        newPageName: String,
    ): Either<DomainError, Unit> {
        for ((uuid, content) in updates) {
            val result = updateBlockContentOnly(uuid, content)
            if (result.isLeft()) return result
        }
        return Unit.right()
    }

    /**
     * Update only the properties of a block. Does NOT touch content or structural fields.
     */
    @DirectRepositoryWrite
    suspend fun updateBlockPropertiesOnly(blockUuid: BlockUuid, properties: Map<String, String>): Either<DomainError, Unit>

    /**
     * Delete a block and optionally its children
     */
    @DirectRepositoryWrite
    suspend fun deleteBlock(blockUuid: BlockUuid, deleteChildren: Boolean = false): Either<DomainError, Unit>

    /**
     * Delete multiple blocks and optionally their children in a single atomic operation.
     * Chain repair is performed for each deletion to maintain linked-list integrity.
     */
    @DirectRepositoryWrite
    suspend fun deleteBulk(blockUuids: List<BlockUuid>, deleteChildren: Boolean = true): Either<DomainError, Unit>

    /**
     * Delete all blocks associated with a specific page
     */
    @DirectRepositoryWrite
    suspend fun deleteBlocksForPage(pageUuid: PageUuid): Either<DomainError, Unit>

    /**
     * Delete all blocks for multiple pages in a single transaction.
     * Significantly faster than calling [deleteBlocksForPage] in a loop during bulk loads.
     */
    @DirectRepositoryWrite
    suspend fun deleteBlocksForPages(pageUuids: List<PageUuid>): Either<DomainError, Unit>

    /**
     * Merge two blocks atomically
     */
    @DirectRepositoryWrite
    suspend fun mergeBlocks(blockUuid: BlockUuid, nextBlockUuid: BlockUuid, separator: String): Either<DomainError, Unit>

    /**
     * Split a block into two atomically at the given cursor position.
     *
     * @param newBlockUuid UUID to assign to the newly-created block. When provided (optimistic
     *   path) the caller and the repository use the same UUID, eliminating the need for a
     *   post-split UUID-correction pass in [BlockStateManager].
     */
    @DirectRepositoryWrite
    suspend fun splitBlock(
        blockUuid: BlockUuid,
        cursorPosition: Int,
        newBlockUuid: BlockUuid? = null,
    ): Either<DomainError, Block>

    /**
     * Clear all blocks from the repository
     */
    @DirectRepositoryWrite
    suspend fun clear(): Either<DomainError, Unit>
}
