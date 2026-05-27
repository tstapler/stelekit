package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.flow.Flow

/**
 * Read-only view of the block store.
 * Covers reactive queries, hierarchical traversals, and cache invalidation.
 */
interface BlockReadRepository {

    /**
     * Retrieve a single block by its UUID
     */
    fun getBlockByUuid(uuid: String): Flow<Either<DomainError, Block?>>

    /**
     * Get all immediate children of a block (one level deep)
     */
    fun getBlockChildren(blockUuid: String): Flow<Either<DomainError, List<Block>>>

    /**
     * Get complete hierarchy starting from a root block (recursive)
     * Returns all descendants with their depth in hierarchy
     */
    fun getBlockHierarchy(rootUuid: String): Flow<Either<DomainError, List<BlockWithDepth>>>

    /**
     * Get all ancestors of a block (from immediate parent up to root)
     */
    fun getBlockAncestors(blockUuid: String): Flow<Either<DomainError, List<Block>>>

    /**
     * Get the immediate parent of a block
     */
    fun getBlockParent(blockUuid: String): Flow<Either<DomainError, Block?>>

    /**
     * Get sibling blocks (blocks with same parent)
     */
    fun getBlockSiblings(blockUuid: String): Flow<Either<DomainError, List<Block>>>

    /**
     * Get all blocks for a specific page
     */
    fun getBlocksForPage(pageUuid: String): Flow<Either<DomainError, List<Block>>>

    /** Evict all in-memory caches without touching the database. No-op by default. */
    suspend fun cacheEvictAll() {}

    /**
     * Evict in-memory cache entries for [pageUuid] only.
     * Called after external file changes so unrelated pages stay warm.
     * No-op by default.
     */
    suspend fun cacheEvictPage(pageUuid: String) {}
}

data class BlockWithDepth(
    val block: Block,
    val depth: Int
)
