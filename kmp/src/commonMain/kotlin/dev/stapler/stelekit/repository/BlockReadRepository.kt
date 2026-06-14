package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Read-only view of the block store.
 * Covers reactive queries, hierarchical traversals, and cache invalidation.
 */
interface BlockReadRepository {

    /**
     * Retrieve a single block by its UUID
     */
    fun getBlockByUuid(uuid: BlockUuid): Flow<Either<DomainError, Block?>>

    /**
     * Get all immediate children of a block (one level deep)
     */
    fun getBlockChildren(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>>

    /**
     * Get complete hierarchy starting from a root block (recursive)
     * Returns all descendants with their depth in hierarchy
     */
    fun getBlockHierarchy(rootUuid: BlockUuid): Flow<Either<DomainError, List<BlockWithDepth>>>

    /**
     * Get all ancestors of a block (from immediate parent up to root)
     */
    fun getBlockAncestors(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>>

    /**
     * Get the immediate parent of a block
     */
    fun getBlockParent(blockUuid: BlockUuid): Flow<Either<DomainError, Block?>>

    /**
     * Get sibling blocks (blocks with same parent)
     */
    fun getBlockSiblings(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>>

    /**
     * Get all blocks for a specific page
     */
    fun getBlocksForPage(pageUuid: PageUuid): Flow<Either<DomainError, List<Block>>>

    /**
     * Batch-fetch blocks by UUID set in a single round-trip.
     * Used by [dev.stapler.stelekit.db.DatabaseWriteActor] to look up pre-existing blocks
     * before a save batch, replacing N individual [getBlockByUuid] calls.
     *
     * Production implementations (SQLDelight) chunk the list to stay below SQLite's
     * per-statement bind-variable limit (999 on Android API < 30).
     *
     * The default implementation falls back to per-UUID lookups and is suitable for test
     * fakes. Production implementations should override with a `WHERE uuid IN ?` query.
     */
    suspend fun getBlocksByUuids(uuids: List<BlockUuid>): Either<DomainError, List<Block>> {
        if (uuids.isEmpty()) return emptyList<Block>().right()
        val results = mutableListOf<Block>()
        for (uuid in uuids) {
            getBlockByUuid(uuid).first().getOrNull()?.let { results.add(it) }
        }
        return results.right()
    }

    /** Evict all in-memory caches without touching the database. No-op by default. */
    suspend fun cacheEvictAll() {}

    /**
     * Evict in-memory cache entries for [pageUuid] only.
     * Called after external file changes so unrelated pages stay warm.
     * No-op by default.
     */
    suspend fun cacheEvictPage(pageUuid: PageUuid) {}
}

data class BlockWithDepth(
    val block: Block,
    val depth: Int
)
