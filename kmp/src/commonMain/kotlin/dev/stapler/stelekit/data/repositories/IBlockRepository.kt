package dev.stapler.stelekit.data.repositories

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.Property
import dev.stapler.stelekit.platform.EncryptionManager
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.flow.Flow
import kotlin.Result

/**
 * Repository interface for block operations with hierarchical support.
 * Handles the core hierarchical structure of Logseq's block system.
 *
 * Version: 1.0.0
 * Stability: Stable - Core block repository contract
 *
 * @since 1.0.0
 */
interface IBlockRepository {

    // ===== BASIC BLOCK OPERATIONS =====

    /**
     * Retrieve a single block by its UUID.
     *
     * @param uuid The block UUID
     * @return Flow emitting Result with the block or null
     */
    fun getBlockByUuid(uuid: String): Flow<Either<DomainError, Block?>>
    /**
     * Save a new or updated block.
     *
     * @param block The block to save
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun saveBlock(block: Block): Either<DomainError, Unit>
    /**
     * Save multiple blocks in a batch operation.
     *
     * @param blocks List of blocks to save
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun saveBlocks(blocks: List<Block>): Either<DomainError, Unit>
    /**
     * Delete a block and optionally its children.
     *
     * @param blockUuid The block UUID to delete
     * @param deleteChildren Whether to delete child blocks
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean = false): Either<DomainError, Unit>
    // ===== HIERARCHICAL OPERATIONS =====

    /**
     * Get all immediate children of a block (one level deep).
     *
     * @param blockUuid The parent block UUID
     * @return Flow emitting Result with list of child blocks
     */
    fun getBlockChildren(blockUuid: String): Flow<Either<DomainError, List<Block>>>
    /**
     * Get complete hierarchy starting from a root block (recursive).
     * Returns all descendants with their depth in hierarchy.
     *
     * @param rootUuid The root block UUID
     * @return Flow emitting Result with list of blocks and their depths
     */
    fun getBlockHierarchy(rootUuid: String): Flow<Either<DomainError, List<BlockWithDepth>>>
    /**
     * Get all ancestors of a block (from immediate parent up to root).
     *
     * @param blockUuid The block UUID
     * @return Flow emitting Result with list of ancestor blocks
     */
    fun getBlockAncestors(blockUuid: String): Flow<Either<DomainError, List<Block>>>
    /**
     * Get the immediate parent of a block.
     *
     * @param blockUuid The block UUID
     * @return Flow emitting Result with parent block or null
     */
    fun getBlockParent(blockUuid: String): Flow<Either<DomainError, Block?>>
    /**
     * Get sibling blocks (blocks with same parent).
     *
     * @param blockUuid The block UUID
     * @return Flow emitting Result with list of sibling blocks
     */
    fun getBlockSiblings(blockUuid: String): Flow<Either<DomainError, List<Block>>>
    // ===== PAGE-LEVEL OPERATIONS =====

    /**
     * Get all blocks for a specific page.
     *
     * @param pageUuid The page UUID
     * @return Flow emitting Result with list of blocks
     */
    fun getBlocksForPage(pageUuid: String): Flow<Either<DomainError, List<Block>>>
    /**
     * Get all blocks for a page in hierarchical order.
     *
     * @param pageUuid The page UUID
     * @return Flow emitting Result with list of blocks with depths
     */
    fun getBlocksForPageHierarchy(pageUuid: String): Flow<Either<DomainError, List<BlockWithDepth>>>
    /**
     * Delete all blocks associated with a specific page.
     *
     * @param pageUuid The page UUID
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun deleteBlocksForPage(pageUuid: String): Either<DomainError, Unit>
    // ===== BLOCK MANIPULATION OPERATIONS =====

    /**
     * Move a block to a new parent and/or position.
     *
     * @param blockUuid The block UUID to move
     * @param newParentUuid The new parent UUID (null for root level)
     * @param newPosition The new position index
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun moveBlock(blockUuid: String, newParentUuid: String?, newPosition: Int): Either<DomainError, Unit>
    /**
     * Indent a block (move it to be a child of its preceding sibling).
     *
     * @param blockUuid The block UUID to indent
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun indentBlock(blockUuid: String): Either<DomainError, Unit>
    /**
     * Outdent a block (move it to be a sibling of its parent).
     *
     * @param blockUuid The block UUID to outdent
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun outdentBlock(blockUuid: String): Either<DomainError, Unit>
    /**
     * Move a block up among its siblings.
     *
     * @param blockUuid The block UUID to move up
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun moveBlockUp(blockUuid: String): Either<DomainError, Unit>
    /**
     * Move a block down among its siblings.
     *
     * @param blockUuid The block UUID to move down
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun moveBlockDown(blockUuid: String): Either<DomainError, Unit>
    // ===== SEARCH AND QUERY OPERATIONS =====

    /**
     * Find all blocks that contain a wiki link to the given page name.
     * (i.e., blocks containing [[Page Name]])
     *
     * @param pageName The page name to search for
     * @return Flow emitting Result with list of blocks
     */
    fun getLinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>>
    /**
     * Find all blocks that mention the page name as plain text.
     * (not as a wiki link)
     *
     * @param pageName The page name to search for
     * @return Flow emitting Result with list of blocks
     */
    fun getUnlinkedReferences(pageName: String): Flow<Either<DomainError, List<Block>>>
    /**
     * Search blocks by content.
     *
     * @param query The search query
     * @return Flow emitting Result with list of blocks
     */
    fun searchBlocksByContent(query: String): Flow<Either<DomainError, List<Block>>>
    /**
     * Search blocks by content with pagination.
     *
     * @param query The search query
     * @param limit Maximum number of results
     * @param offset Offset for pagination
     * @return Flow emitting Result with list of blocks
     */
    fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>>
    /**
     * Get blocks by content pattern.
     *
     * @param pattern The content pattern (supports regex)
     * @return Flow emitting Result with list of blocks
     */
    fun getBlocksByContentPattern(pattern: String): Flow<Either<DomainError, List<Block>>>
    // ===== BATCH OPERATIONS =====

    /**
     * Create multiple blocks in a batch.
     *
     * @param blocks List of blocks to create
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun createBlocks(blocks: List<Block>): Either<DomainError, Unit>
    /**
     * Update multiple blocks in a batch.
     *
     * @param blocks List of blocks to update
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun updateBlocks(blocks: List<Block>): Either<DomainError, Unit>
    /**
     * Delete multiple blocks in a batch.
     *
     * @param blockUuids List of block UUIDs to delete
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun deleteBlocks(blockUuids: List<String>): Either<DomainError, Unit>
    // ===== BLOCK METADATA OPERATIONS =====

    /**
     * Get block metadata.
     *
     * @param blockUuid The block UUID
     * @return Flow emitting Result with metadata map
     */
    fun getBlockMetadata(blockUuid: String): Flow<Either<DomainError, Map<String, String>>>
    /**
     * Update block metadata.
     *
     * @param blockUuid The block UUID
     * @param metadata The metadata to update
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun updateBlockMetadata(blockUuid: String, metadata: Map<String, String>): Either<DomainError, Unit>
    /**
     * Delete block metadata key.
     *
     * @param blockUuid The block UUID
     * @param key The metadata key to delete
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun deleteBlockMetadata(blockUuid: String, key: String): Either<DomainError, Unit>
    // ===== BLOCK PROPERTIES OPERATIONS =====

    /**
     * Get all properties for a specific block.
     *
     * @param blockUuid The block UUID
     * @return Flow emitting Result with list of properties
     */
    fun getBlockProperties(blockUuid: String): Flow<Either<DomainError, List<Property>>>
    /**
     * Get a specific property by block UUID and key.
     *
     * @param blockUuid The block UUID
     * @param key The property key
     * @return Flow emitting Result with property or null
     */
    fun getBlockProperty(blockUuid: String, key: String): Flow<Either<DomainError, Property?>>
    /**
     * Save a block property (create or update).
     *
     * @param property The property to save
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun saveBlockProperty(property: Property): Either<DomainError, Unit>
    /**
     * Delete a block property.
     *
     * @param blockUuid The block UUID
     * @param key The property key
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun deleteBlockProperty(blockUuid: String, key: String): Either<DomainError, Unit>
    // ===== BLOCK VERSIONING OPERATIONS =====

    /**
     * Get block version history.
     *
     * @param blockUuid The block UUID
     * @return Flow emitting Result with list of block versions
     */
    fun getBlockVersionHistory(blockUuid: String): Flow<Either<DomainError, List<BlockVersion>>>
    /**
     * Get a specific version of a block.
     *
     * @param blockUuid The block UUID
     * @param version The version number
     * @return Flow emitting Result with block version or null
     */
    fun getBlockVersion(blockUuid: String, version: Long): Flow<Either<DomainError, BlockVersion?>>
    /**
     * Create a new version of a block.
     *
     * @param blockUuid The block UUID
     * @param changeDescription Description of the change
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun createBlockVersion(blockUuid: String, changeDescription: String): Either<DomainError, Unit>
    // ===== REPOSITORY MAINTENANCE =====

    /**
     * Clear all blocks from the repository.
     *
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun clear(): Either<DomainError, Unit>
    /**
     * Get repository statistics.
     *
     * @return BlockRepositoryStatistics with repository metrics
     */
    suspend fun getStatistics(): Either<DomainError, BlockRepositoryStatistics>
    /**
     * Optimize the repository.
     *
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun optimize(): Either<DomainError, Unit>
    /**
     * Validate repository integrity.
     *
     * @return Result with validation report
     */
    @DirectRepositoryWrite
    suspend fun validateIntegrity(): Either<DomainError, ValidationReport>
    // ===== CACHING OPERATIONS =====

    /**
     * Enable or disable caching.
     *
     * @param enabled Whether to enable caching
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun setCachingEnabled(enabled: Boolean): Either<DomainError, Unit>
    /**
     * Clear cache.
     *
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun clearCache(): Either<DomainError, Unit>
    /**
     * Get cache statistics.
     *
     * @return CacheStatistics with cache metrics
     */
    suspend fun getCacheStatistics(): Either<DomainError, CacheStatistics>
    // ===== ENCRYPTION OPERATIONS =====

    /**
     * Set encryption manager for encrypted repositories.
     *
     * @param encryptionManager The encryption manager
     * @return Result indicating success or error
     */
    @DirectRepositoryWrite
    suspend fun setEncryptionManager(encryptionManager: EncryptionManager): Either<DomainError, Unit>
    /**
     * Check if repository is encrypted.
     *
     * @return true if repository is encrypted
     */
    fun isEncrypted(): Boolean
}

/**
 * Represents a block with its depth in a hierarchy.
 *
 * @param block The block
 * @param depth The depth in hierarchy (0 = root level)
 */
data class BlockWithDepth(
    val block: Block,
    val depth: Int
)

/**
 * Represents a version of a block.
 *
 * @param blockUuid The block UUID
 * @param version The version number
 * @param content The block content at this version
 * * @param timestamp When this version was created
 * @param changeDescription Description of the change
 * @param author The author of the change
 */
data class BlockVersion(
    val blockUuid: String,
    val version: Long,
    val content: String,
    val timestamp: kotlin.time.Instant,
    val changeDescription: String,
    val author: String
)

/**
 * Block repository statistics.
 *
 * @param totalBlocks Total number of blocks
 * @param rootBlocks Number of root-level blocks
 * @param maxDepth Maximum hierarchy depth
 * @param averageDepth Average hierarchy depth
 * @val orphanedBlocks Number of orphaned blocks
 * @val lastModified When repository was last modified
 * @val repositorySize Repository size in bytes
 */
data class BlockRepositoryStatistics(
    val totalBlocks: Long,
    val rootBlocks: Long,
    val maxDepth: Int,
    val averageDepth: Float,
    val orphanedBlocks: Long,
    val lastModified: kotlin.time.Instant,
    val repositorySize: Long
)

/**
 * Repository validation report.
 *
 * @param isValid Whether repository is valid
 * @val errors List of validation errors
 * @val warnings List of validation warnings
 * @val recommendations List of recommendations
 */
data class ValidationReport(
    val isValid: Boolean,
    val errors: List<ValidationError>,
    val warnings: List<ValidationWarning>,
    val recommendations: List<String>
)

/**
 * Validation error.
 *
 * @param code Error code
 * @param message Error message
 * @param severity Error severity
 * @param affectedBlocks List of affected block UUIDs
 */
data class ValidationError(
    val code: String,
    val message: String,
    val severity: ValidationSeverity,
    val affectedBlocks: List<String>
)

/**
 * Validation warning.
 *
 * @param code Warning code
 * @param message Warning message
 * @param affectedBlocks List of affected block UUIDs
 */
data class ValidationWarning(
    val code: String,
    val message: String,
    val affectedBlocks: List<String>
)

/**
 * Validation severity levels.
 */
enum class ValidationSeverity {
    ERROR, WARNING, INFO
}

/**
 * Cache statistics.
 *
 * @param hitCount Number of cache hits
 * @param missCount Number of cache misses
 * @val hitRate Cache hit rate (0-1)
 * @val size Current cache size
 * @val maxSize Maximum cache size
 * @val evictionCount Number of evictions
 */
data class CacheStatistics(
    val hitCount: Long,
    val missCount: Long,
    val hitRate: Float,
    val size: Long,
    val maxSize: Long,
    val evictionCount: Long
)
