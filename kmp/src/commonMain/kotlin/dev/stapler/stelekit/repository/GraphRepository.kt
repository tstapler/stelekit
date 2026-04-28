package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.Property
import dev.stapler.stelekit.platform.EncryptionManager
import kotlinx.coroutines.flow.Flow

/**
 * Repository interfaces for graph database operations in Logseq.
 * Designed to support hierarchical block structures and reference relationships.
 */

// ===== CORE DOMAIN INTERFACES =====

/**
 * Repository for block operations with hierarchical support.
 * Handles the core hierarchical structure of Logseq's block system.
 */
interface BlockRepository {
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
     * Clear all blocks from the repository
     */
    @DirectRepositoryWrite
    suspend fun clear()

    /** Evict all in-memory caches without touching the database. No-op by default. */
    suspend fun cacheEvictAll() {}

    /**
     * Evict in-memory cache entries for [pageUuid] only.
     * Called after external file changes so unrelated pages stay warm.
     * No-op by default.
     */
    suspend fun cacheEvictPage(pageUuid: String) {}

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
     * Move a block to a new parent and/or position
     */
    @DirectRepositoryWrite
    suspend fun moveBlock(blockUuid: String, newParentUuid: String?, newPosition: Int): Either<DomainError, Unit>

    /**
     * Indent a block (move it to be a child of its preceding sibling)
     */
    @DirectRepositoryWrite
    suspend fun indentBlock(blockUuid: String): Either<DomainError, Unit>

    /**
     * Outdent a block (move it to be a sibling of its parent)
     */
    @DirectRepositoryWrite
    suspend fun outdentBlock(blockUuid: String): Either<DomainError, Unit>

    /**
     * Move a block up among its siblings
     */
    @DirectRepositoryWrite
    suspend fun moveBlockUp(blockUuid: String): Either<DomainError, Unit>

    /**
     * Move a block down among its siblings
     */
    @DirectRepositoryWrite
    suspend fun moveBlockDown(blockUuid: String): Either<DomainError, Unit>

    /**
     * Merge two blocks atomically
     */
    @DirectRepositoryWrite
    suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Either<DomainError, Unit>

    /**
     * Split a block into two atomically at the given cursor position
     */
    @DirectRepositoryWrite
    suspend fun splitBlock(blockUuid: String, cursorPosition: Int): Either<DomainError, Block>

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

/**
 * Repository for page operations.
 * Pages are special blocks that serve as roots of block hierarchies.
 */
interface PageRepository {
    /**
     * Get a page by its UUID
     */
    fun getPageByUuid(uuid: String): Flow<Either<DomainError, Page?>>

    /**
     * Get a page by its name/title
     */
    fun getPageByName(name: String): Flow<Either<DomainError, Page?>>

    /**
     * Get all pages in a namespace
     */
    fun getPagesInNamespace(namespace: String): Flow<Either<DomainError, List<Page>>>

    /**
     * Get all pages with pagination
     */
    fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>>

    /**
     * Search pages by name with pagination
     */
    fun searchPages(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>>

    /**
     * Get all pages (unpaginated)
     */
    fun getAllPages(): Flow<Either<DomainError, List<Page>>>

    /**
     * Get journal pages with pagination
     */
    fun getJournalPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>>

    /**
     * Find a journal page by its date. Format-agnostic — always use this instead of
     * getPageByName for journal pages to avoid duplicate creation from name format differences
     * (e.g. "2026_04_11" on disk vs "2026-04-11" created in-app).
     */
    fun getJournalPageByDate(date: kotlinx.datetime.LocalDate): Flow<Either<DomainError, Page?>>

    /**
     * Get recently modified pages
     */
    fun getRecentPages(limit: Int = 50): Flow<Either<DomainError, List<Page>>>

    /**
     * Get all pages that haven't been fully loaded/indexed yet
     */
    fun getUnloadedPages(): Flow<Either<DomainError, List<Page>>>

    /**
     * Save a new or updated page
     */
    @DirectRepositoryWrite
    suspend fun savePage(page: Page): Either<DomainError, Unit>

    /**
     * Save multiple pages in a single transaction.
     * Significantly faster than calling [savePage] in a loop during bulk loads.
     */
    @DirectRepositoryWrite
    suspend fun savePages(pages: List<Page>): Either<DomainError, Unit>

    /**
     * Toggle favorite status for a page
     */
    @DirectRepositoryWrite
    suspend fun toggleFavorite(pageUuid: String): Either<DomainError, Unit>

    /**
     * Rename a page
     * Updates the page name and associated indexes
     */
    @DirectRepositoryWrite
    suspend fun renamePage(pageUuid: String, newName: String): Either<DomainError, Unit>

    /**
     * Delete a page
     */
    @DirectRepositoryWrite
    suspend fun deletePage(pageUuid: String): Either<DomainError, Unit>

    /**
     * Get total number of pages in the repository
     */
    fun countPages(): Flow<Either<DomainError, Long>>

    /**
     * Clear all pages from the repository
     */
    @DirectRepositoryWrite
    suspend fun clear()

    /** Evict all in-memory caches without touching the database. No-op by default. */
    suspend fun cacheEvictAll() {}
}

/**
 * Repository for property operations.
 * Properties are key-value metadata attached to blocks.
 */
interface PropertyRepository {
    /**
     * Get all properties for a specific block
     */
    fun getPropertiesForBlock(blockUuid: String): Flow<Either<DomainError, List<Property>>>

    /**
     * Get a specific property by block UUID and key
     */
    fun getProperty(blockUuid: String, key: String): Flow<Either<DomainError, Property?>>

    /**
     * Save a property (create or update)
     */
    @DirectRepositoryWrite
    suspend fun saveProperty(property: Property): Either<DomainError, Unit>

    /**
     * Delete a property
     */
    @DirectRepositoryWrite
    suspend fun deleteProperty(blockUuid: String, key: String): Either<DomainError, Unit>

    /**
     * Get all blocks that have a specific property key
     */
    fun getBlocksWithPropertyKey(key: String): Flow<Either<DomainError, List<Block>>>

    /**
     * Get all blocks that have a specific property value
     */
    fun getBlocksWithPropertyValue(key: String, value: String): Flow<Either<DomainError, List<Block>>>
}

// ===== REFERENCE & RELATIONSHIP INTERFACES =====

/**
 * Repository for managing block-to-block references.
 * References are the links between blocks that form Logseq's knowledge graph.
 */
interface ReferenceRepository {
    /**
     * Get all blocks referenced by a specific block
     */
    fun getOutgoingReferences(blockUuid: String): Flow<Either<DomainError, List<Block>>>

    /**
     * Get all blocks that reference a specific block
     */
    fun getIncomingReferences(blockUuid: String): Flow<Either<DomainError, List<Block>>>

    /**
     * Get all references (bidirectional) for a block
     */
    fun getAllReferences(blockUuid: String): Flow<Either<DomainError, BlockReferences>>

    /**
     * Add a reference from one block to another
     */
    @DirectRepositoryWrite
    suspend fun addReference(fromBlockUuid: String, toBlockUuid: String): Either<DomainError, Unit>

    /**
     * Remove a reference between blocks
     */
    @DirectRepositoryWrite
    suspend fun removeReference(fromBlockUuid: String, toBlockUuid: String): Either<DomainError, Unit>

    /**
     * Get blocks that are not referenced by any other block (orphans)
     */
    fun getOrphanedBlocks(): Flow<Either<DomainError, List<Block>>>

    /**
     * Get the most connected blocks (by reference count)
     */
    fun getMostConnectedBlocks(limit: Int = 20): Flow<Either<DomainError, List<BlockWithReferenceCount>>>
}

// ===== SEARCH & QUERY INTERFACES =====

/**
 * Repository for search operations across the graph.
 * Supports full-text search and graph-based queries.
 */
interface SearchRepository {
    /**
     * Search blocks by content (full-text search)
     */
    fun searchBlocksByContent(query: String, limit: Int = 50, offset: Int = 0): Flow<Either<DomainError, List<Block>>>

    /**
     * Search pages by title/name
     */
    fun searchPagesByTitle(query: String, limit: Int = 20): Flow<Either<DomainError, List<Page>>>

    /**
     * Find blocks that reference specific content
     */
    fun findBlocksReferencing(blockUuid: String): Flow<Either<DomainError, List<Block>>>

    /**
     * Advanced graph search with filters
     */
    fun searchWithFilters(searchRequest: SearchRequest): Flow<Either<DomainError, SearchResult>>
}

// ===== DATA STRUCTURES =====

/**
 * A group of blocks that share identical content (confirmed by both hash and direct comparison).
 */
data class DuplicateGroup(
    /** The SHA-256 hex digest that all blocks in this group share. */
    val contentHash: String,
    /** Blocks whose content strings are byte-for-byte identical. */
    val blocks: List<Block>,
    val count: Int
)

/**
 * Represents a block with its depth in a hierarchy
 */
data class BlockWithDepth(
    val block: Block,
    val depth: Int
)

/**
 * Represents bidirectional references for a block
 */
data class BlockReferences(
    val outgoing: List<Block>,  // blocks this block references
    val incoming: List<Block>   // blocks that reference this block
)

/**
 * Represents a block with its reference count
 */
data class BlockWithReferenceCount(
    val block: Block,
    val referenceCount: Int
)

/** Scope for search — limits which content types or contexts are searched. */
enum class SearchScope {
    ALL,
    PAGES_ONLY,
    BLOCKS_ONLY,
    CURRENT_PAGE,
    JOURNAL,
    FAVORITES
}

/** Which data fields to include in the search. */
enum class DataType {
    TITLES,
    CONTENT,
    PROPERTIES,
    BACKLINKS
}

/**
 * Search request with multiple filters
 */
data class SearchRequest(
    val query: String? = null,
    val pageUuid: String? = null,
    val scope: SearchScope = SearchScope.ALL,
    val dataTypes: Set<DataType> = setOf(DataType.TITLES, DataType.CONTENT),
    val propertyFilters: Map<String, String> = emptyMap(),
    val dateRange: DateRange? = null,
    val limit: Int = 50,
    val offset: Int = 0
)

/**
 * Date range for filtering
 */
data class DateRange(
    val startDate: kotlin.time.Instant? = null,
    val endDate: kotlin.time.Instant? = null
)

/** A page result with an optional snippet and raw BM25 score from FTS5. */
data class SearchedPage(
    val page: Page,
    val snippet: String? = null,
    val bm25Score: Double = 0.0
)

/** A block result with an optional snippet and raw BM25 score from FTS5. */
data class SearchedBlock(
    val block: Block,
    val snippet: String? = null,
    val bm25Score: Double = 0.0
)

/**
 * A search hit carrying an absolute relevance score suitable for cross-type ranking.
 *
 * BM25 returns negative values (more negative = more relevant). [score] is the
 * absolute value, optionally multiplied by [SqlDelightSearchRepository.PAGE_BOOST]
 * for page-title hits so they sort above body-text hits.
 */
sealed class RankedSearchHit {
    abstract val score: Double
    data class PageHit(val page: Page, val snippet: String?, override val score: Double) : RankedSearchHit()
    data class BlockHit(val block: Block, val snippet: String?, override val score: Double) : RankedSearchHit()
}

/**
 * Search result with metadata.
 *
 * [searchedPages] and [searchedBlocks] carry BM25-ranked results with highlight snippets.
 * [blocks] / [pages] are kept for backward compatibility with callers that do not need snippets.
 * [ranked] interleaves pages and blocks sorted by boosted relevance score (highest first).
 */
data class SearchResult(
    val blocks: List<Block>,
    val pages: List<Page>,
    val searchedBlocks: List<SearchedBlock> = emptyList(),
    val searchedPages: List<SearchedPage> = emptyList(),
    val ranked: List<RankedSearchHit> = emptyList(),
    val totalCount: Int,
    val hasMore: Boolean
)

// ===== BACKEND ENUMERATION =====

/**
 * Supported graph database backends for evaluation
 */
enum class GraphBackend {
    SQLDELIGHT,
    DATASCRIPT,
    KUZU,
    NEO4J,
    IN_MEMORY  // For testing and reference implementation
}

// ===== REPOSITORY FACTORY =====

/**
 * Factory for creating repository instances based on backend type
 */
interface RepositoryFactory {
    fun createBlockRepository(backend: GraphBackend): BlockRepository
    fun createPageRepository(backend: GraphBackend): PageRepository
    fun createPropertyRepository(backend: GraphBackend): PropertyRepository
    fun createReferenceRepository(backend: GraphBackend): ReferenceRepository
    fun createSearchRepository(backend: GraphBackend): SearchRepository
    /**
     * Close the underlying database connection and release resources.
     */
    fun close()
}

/**
 * A complete set of repositories for a single graph database.
 */
data class RepositorySet(
    val blockRepository: BlockRepository,
    val pageRepository: PageRepository,
    /** Write-only view of [pageRepository] with cache population disabled. For background indexing. */
    val backgroundPageRepository: PageRepository = pageRepository,
    val propertyRepository: PropertyRepository,
    val referenceRepository: ReferenceRepository,
    val searchRepository: SearchRepository,
    val journalService: JournalService,
    val writeActor: dev.stapler.stelekit.db.DatabaseWriteActor? = null,
    val undoManager: dev.stapler.stelekit.db.UndoManager? = null,
    val histogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = null,
    val debugFlagRepository: dev.stapler.stelekit.performance.DebugFlagRepository? = null,
    val ringBuffer: dev.stapler.stelekit.performance.RingBufferSpanExporter? = null,
    val spanRepository: dev.stapler.stelekit.performance.SpanRepository? = null,
    val bugReportBuilder: dev.stapler.stelekit.performance.BugReportBuilder? = null,
    val perfExporter: dev.stapler.stelekit.performance.PerfExporter? = null,
    val spanEmitter: dev.stapler.stelekit.performance.SpanEmitter? = null,
    val sloChecker: dev.stapler.stelekit.performance.SloChecker? = null,
    val spanLogSink: dev.stapler.stelekit.performance.SpanLogSink? = null,
    /** Callback that runs WAL checkpoint after bulk graph import. Pass to [GraphLoader.onBulkImportComplete]. */
    val onBulkImportComplete: (suspend () -> Unit)? = null,
    val queryStatsRepository: dev.stapler.stelekit.performance.QueryStatsRepository? = null,
    val queryStatsCollector: dev.stapler.stelekit.performance.QueryStatsCollector? = null,
)
