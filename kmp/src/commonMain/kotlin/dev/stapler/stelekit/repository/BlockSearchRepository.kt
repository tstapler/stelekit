package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.flow.Flow

/**
 * Search and reference queries over the block store.
 */
interface BlockSearchRepository {

    /**
     * Find all blocks that contain a wiki link to the given page name
     * (i.e., blocks containing [[Page Name]])
     */
    fun getLinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>>

    /**
     * Find all blocks that contain a wiki link to the given page name with pagination.
     */
    fun getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>>

    /**
     * Find all blocks that mention the page name as plain text
     * (not as a wiki link)
     */
    fun getUnlinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>>

    /**
     * Find all blocks that mention the page name as plain text with pagination.
     */
    fun getUnlinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>>

    /**
     * Search blocks by content
     */
    fun searchBlocksByContent(query: String, limit: Int = 50, offset: Int = 0): Flow<Either<DomainError, List<Block>>>

    /**
     * Count blocks that contain a wiki link to the given page name.
     */
    fun countLinkedReferences(pageName: String): Flow<Either<DomainError, Long>>

    /**
     * Find groups of blocks whose content is identical (potential duplicates).
     *
     * The implementation first queries by content_hash (fast index scan) and then
     * performs a second pass comparing the actual content strings directly. This
     * guards against the astronomically rare but theoretically possible SHA-256
     * collision where two different content strings produce the same hash — such
     * blocks would NOT be reported as duplicates.
     *
     * @param limit maximum number of distinct hash groups to inspect
     */
    fun findDuplicateBlocks(limit: Int = 50): Flow<Either<DomainError, List<DuplicateGroup>>>
}

data class DuplicateGroup(
    val contentHash: String,
    val blocks: List<Block>,
)
