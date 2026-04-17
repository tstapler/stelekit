package dev.stapler.stelekit.repository

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import dev.stapler.stelekit.model.Page

/**
 * Search criteria for page queries
 */
data class PageSearchCriteria(
    val query: String? = null,
    val namespace: String? = null,
    val properties: Map<String, String> = emptyMap(),
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val updatedAfter: Instant? = null,
    val updatedBefore: Instant? = null,
    val isJournal: Boolean? = null
)

/**
 * Page repository interface for page management and name resolution
 */
interface PageRepository : BaseRepository<Page, Long> {

    // CRUD operations (inherited from BaseRepository)

    // UUID-based operations (for external API compatibility)
    suspend fun findByUuid(uuid: String): Page?
    suspend fun existsByUuid(uuid: String): Boolean

    // Name resolution
    suspend fun findByName(name: String): Page?
    suspend fun existsByName(name: String): Boolean

    // Journal operations
    suspend fun findJournals(pagination: Pagination = Pagination()): Page<Page>
    suspend fun findJournalByDay(day: Long): Page?
    suspend fun findJournalsInRange(startDay: Long, endDay: Long): List<Page>

    // Namespace operations
    suspend fun findPagesInNamespace(namespace: String, pagination: Pagination = Pagination()): Page<Page>
    suspend fun findChildPages(parentPageId: Long, pagination: Pagination = Pagination()): Page<Page>
    suspend fun findParentPages(childPageId: Long): List<Page>

    // Search operations
    suspend fun search(criteria: PageSearchCriteria, pagination: Pagination = Pagination()): Page<Page>
    suspend fun searchByName(query: String, pagination: Pagination = Pagination()): Page<Page>
    suspend fun searchByProperties(properties: Map<String, String>, pagination: Pagination = Pagination()): Page<Page>

    /**
     * Get pages with pagination (reactive)
     */
    fun getPages(limit: Int, offset: Int): Flow<Result<List<Page>>>

    /**
     * Search pages by name with pagination (reactive)
     */
    fun searchPages(query: String, limit: Int, offset: Int): Flow<Result<List<Page>>>

    // Content-related queries
    suspend fun findPagesWithBlocks(blockIds: List<Long>): List<Page>
    suspend fun findRecentlyUpdated(pagination: Pagination = Pagination()): Page<Page>
    suspend fun findRecentlyCreated(pagination: Pagination = Pagination()): Page<Page>

    // Property operations
    suspend fun updateProperties(pageId: Long, properties: Map<String, String>): Page?
    suspend fun getProperties(pageId: Long): Map<String, String>

    // Bulk operations
    suspend fun renamePage(pageId: Long, newName: String): Page?

    // Flow-based operations for reactive updates
    fun observePage(pageId: Long): Flow<Page?>
    fun observePagesByNamespace(namespace: String): Flow<List<Page>>
    fun observeRecentlyUpdated(): Flow<List<Page>>

    // Utility operations
    suspend fun resolvePageName(name: String): String
    suspend fun normalizePageName(name: String): String
    suspend fun generateUniqueName(baseName: String): String
}