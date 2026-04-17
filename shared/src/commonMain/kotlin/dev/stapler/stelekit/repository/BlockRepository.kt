package dev.stapler.stelekit.repository

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import dev.stapler.stelekit.model.Block

/**
 * Search criteria for block queries
 */
data class BlockSearchCriteria(
    val query: String? = null,
    val pageId: Long? = null,
    val parentId: Long? = null,
    val properties: Map<String, String> = emptyMap(),
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val updatedAfter: Instant? = null,
    val updatedBefore: Instant? = null,
    val collapsed: Boolean? = null,
    val marker: String? = null,
    val priority: String? = null,
    val type: String? = null
)

/**
 * Block repository interface for CRUD operations and hierarchical queries
 */
interface BlockRepository : BaseRepository<Block, Long> {

    // CRUD operations (inherited from BaseRepository)

    // UUID-based operations (for external API compatibility)
    suspend fun findByUuid(uuid: String): Block?
    suspend fun findByUuids(uuids: List<String>): List<Block>
    suspend fun existsByUuid(uuid: String): Boolean

    // Hierarchical queries
    suspend fun findChildren(parentId: Long, pagination: Pagination = Pagination()): Page<Block>
    suspend fun findSiblings(blockId: Long): List<Block>
    suspend fun findAncestors(blockId: Long): List<Block>
    suspend fun findDescendants(blockId: Long, maxDepth: Int? = null): List<Block>
    suspend fun findRootBlocks(pageId: Long, pagination: Pagination = Pagination()): Page<Block>

    // Search operations
    suspend fun search(criteria: BlockSearchCriteria, pagination: Pagination = Pagination()): Page<Block>
    suspend fun searchByContent(query: String, pagination: Pagination = Pagination()): Page<Block>
    suspend fun searchByProperties(properties: Map<String, String>, pagination: Pagination = Pagination()): Page<Block>

    // Reference queries
    suspend fun findReferences(blockId: Long): List<Block>
    suspend fun findBackReferences(blockId: Long): List<Block>

    // Page-related queries
    suspend fun findBlocksByPage(pageId: Long, pagination: Pagination = Pagination()): Page<Block>
    suspend fun countBlocksByPage(pageId: Long): Long

    // Task management
    suspend fun findTasks(marker: String? = null, pagination: Pagination = Pagination()): Page<Block>
    suspend fun findTasksByPage(pageId: Long, marker: String? = null): List<Block>

    // Bulk operations
    suspend fun updateParent(blockIds: List<Long>, newParentId: Long?): List<Block>
    suspend fun moveBlocks(blockIds: List<Long>, targetParentId: Long?, targetLeftId: Long?): List<Block>
    suspend fun collapseBlocks(blockIds: List<Long>, collapsed: Boolean): List<Block>

    // Property operations
    suspend fun updateProperties(blockId: Long, properties: Map<String, String>): Block?
    suspend fun getProperties(blockId: Long): Map<String, String>

    // Flow-based operations for reactive updates
    fun observeBlock(blockId: Long): Flow<Block?>
    fun observeBlocksByPage(pageId: Long): Flow<List<Block>>
    fun observeSearchResults(criteria: BlockSearchCriteria): Flow<List<Block>>
}