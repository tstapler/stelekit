package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.cache.LruCache
import dev.stapler.stelekit.cache.RepoCacheConfig
import dev.stapler.stelekit.cache.RequestCoalescer
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * SQLDelight implementation of PageRepository.
 * Uses the generated SteleDatabaseQueries for all operations.
 */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightPageRepository(
    private val database: SteleDatabase
) : PageRepository {

    private val queries = database.steleDatabaseQueries

    private val cacheConfig = RepoCacheConfig.fromPlatform()

    // Page LRU caches: 20% each of platform cache budget.
    // Weigher: 300 bytes fixed + 2 bytes per char in name and filePath.
    // Only caches found pages; null (not found) results are not cached.
    private val pageByUuidCache = LruCache<String, Page>(
        maxWeight = cacheConfig.pageByUuidCacheBytes,
        weigher = { _, p -> 300L + p.name.length * 2 + (p.filePath?.length ?: 0) * 2 }
    )
    private val pageByNameCache = LruCache<String, Page>(
        maxWeight = cacheConfig.pageByNameCacheBytes,
        weigher = { _, p -> 300L + p.name.length * 2 + (p.filePath?.length ?: 0) * 2 }
    )

    // Deduplicates concurrent identical getPageBy* lookups (e.g. during parallel indexing).
    private val byUuidCoalescer = RequestCoalescer<String, Page?>()
    private val byNameCoalescer = RequestCoalescer<String, Page?>()

    override fun getPageByUuid(uuid: String): Flow<Either<DomainError, Page?>> = flow {
        val cached = pageByUuidCache.get(uuid)
        if (cached != null) {
            emit(cached.right())
            return@flow
        }
        val page = byUuidCoalescer.execute(uuid) {
            queries.selectPageByUuid(uuid).executeAsOneOrNull()?.toModel()
        }
        if (page != null) {
            pageByUuidCache.put(page.uuid, page)
            pageByNameCache.put(page.name.lowercase(), page)
        }
        emit(page.right())
    }.flowOn(PlatformDispatcher.DB)

    override fun getPageByName(name: String): Flow<Either<DomainError, Page?>> = flow {
        val cached = pageByNameCache.get(name.lowercase())
        if (cached != null) {
            emit(cached.right())
            return@flow
        }
        val page = byNameCoalescer.execute(name.lowercase()) {
            queries.selectPageByName(name).executeAsOneOrNull()?.toModel()
        }
        if (page != null) {
            pageByNameCache.put(name.lowercase(), page)
            pageByUuidCache.put(page.uuid, page)
        }
        emit(page.right())
    }.flowOn(PlatformDispatcher.DB)

    override fun getPagesInNamespace(namespace: String): Flow<Either<DomainError, List<Page>>> = 
        queries.selectPagesByNamespaceUnpaginated(namespace)
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> list.map { it.toModel() }.right() }

    override fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> = 
        queries.selectAllPagesPaginated(limit.toLong(), offset.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> list.map { it.toModel() }.right() }

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> = 
        queries.selectPagesByNameLikePaginated("%$query%", limit.toLong(), offset.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> list.map { it.toModel() }.right() }

    override fun getAllPages(): Flow<Either<DomainError, List<Page>>> =
        queries.selectAllPages()
            .asFlow()
            .conflate()  // drop intermediate invalidations during bulk import to avoid O(N²) full-table scans
            .mapToList(PlatformDispatcher.DB)
            .map { list -> list.map { it.toModel() }.right() }

    override fun getJournalPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        queries.selectJournalPages(limit.toLong(), offset.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> list.map { it.toModel() }.right() }

    override fun getJournalPageByDate(date: kotlinx.datetime.LocalDate): Flow<Either<DomainError, Page?>> =
        queries.selectJournalPageByDate(date.toString())
            .asFlow()
            .mapToOneOrNull(PlatformDispatcher.DB)
            .map { it?.toModel().right() }

    override fun getRecentPages(limit: Int): Flow<Either<DomainError, List<Page>>> = 
        queries.selectRecentlyUpdatedPages(limit.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> list.map { it.toModel() }.right() }

    override fun getUnloadedPages(): Flow<Either<DomainError, List<Page>>> = 
        queries.selectUnloadedPages()
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> list.map { it.toModel() }.right() }

    override suspend fun savePage(page: Page): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            upsertPage(page)
            pageByUuidCache.put(page.uuid, page)
            pageByNameCache.put(page.name.lowercase(), page)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun savePages(pages: List<Page>): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        if (pages.isEmpty()) return@withContext Unit.right()
        try {
            queries.transaction {
                pages.forEach { page -> upsertPage(page) }
            }
            pages.forEach { page ->
                pageByUuidCache.put(page.uuid, page)
                pageByNameCache.put(page.name.lowercase(), page)
            }
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    private fun upsertPage(page: Page) {
        queries.insertPage(
            uuid = page.uuid,
            name = page.name,
            namespace = page.namespace,
            file_path = page.filePath,
            created_at = page.createdAt.toEpochMilliseconds(),
            updated_at = page.updatedAt.toEpochMilliseconds(),
            properties = page.properties.entries.joinToString(",") { "${it.key}:${it.value}" },
            version = page.version,
            is_favorite = if (page.isFavorite) 1L else 0L,
            is_journal = if (page.isJournal) 1L else 0L,
            journal_date = page.journalDate?.toString(),
            is_content_loaded = if (page.isContentLoaded) 1L else 0L
        )
        queries.updatePage(
            namespace = page.namespace,
            file_path = page.filePath,
            updated_at = page.updatedAt.toEpochMilliseconds(),
            properties = page.properties.entries.joinToString(",") { "${it.key}:${it.value}" },
            version = page.version,
            is_favorite = if (page.isFavorite) 1L else 0L,
            is_journal = if (page.isJournal) 1L else 0L,
            journal_date = page.journalDate?.toString(),
            is_content_loaded = if (page.isContentLoaded) 1L else 0L,
            uuid = page.uuid
        )
    }

    override suspend fun toggleFavorite(pageUuid: String): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val page = queries.selectPageByUuid(pageUuid).executeAsOneOrNull()
            if (page != null) {
                val newFavorite = if (page.is_favorite == 1L) 0L else 1L
                queries.updatePageFavorite(newFavorite, pageUuid)
            }
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun renamePage(pageUuid: String, newName: String): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val old = pageByUuidCache.get(pageUuid)
            if (old != null) pageByNameCache.remove(old.name.lowercase())
            queries.updatePageName(newName, pageUuid)
            pageByUuidCache.remove(pageUuid)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun deletePage(pageUuid: String): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val old = pageByUuidCache.get(pageUuid)
            if (old != null) pageByNameCache.remove(old.name.lowercase())
            queries.deletePageByUuid(pageUuid)
            pageByUuidCache.remove(pageUuid)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override fun countPages(): Flow<Either<DomainError, Long>> = flow {
        try {
            val count = queries.countPages().executeAsOne()
            emit(count.right())
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override suspend fun cacheEvictAll(): Unit = withContext(PlatformDispatcher.DB) {
        pageByUuidCache.invalidateAll()
        pageByNameCache.invalidateAll()
    }

    override suspend fun clear(): Unit = withContext(PlatformDispatcher.DB) {
        queries.deleteAllPages()
        pageByUuidCache.invalidateAll()
        pageByNameCache.invalidateAll()
    }

    private fun dev.stapler.stelekit.db.Pages.toModel(): Page {
        return Page(
            uuid = this.uuid,
            name = this.name,
            namespace = this.namespace,
            filePath = this.file_path,
            createdAt = Instant.fromEpochMilliseconds(this.created_at),
            updatedAt = Instant.fromEpochMilliseconds(this.updated_at),
            version = this.version,
            properties = this.properties?.split(",")?.filter { it.isNotBlank() }?.associate {
                val parts = it.split(":", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else "" to ""
            }?.filter { it.key.isNotBlank() } ?: emptyMap(),
            isJournal = this.is_journal == 1L,
            journalDate = this.journal_date?.let { kotlinx.datetime.LocalDate.parse(it) },
            isContentLoaded = this.is_content_loaded == 1L
        )
    }
}
