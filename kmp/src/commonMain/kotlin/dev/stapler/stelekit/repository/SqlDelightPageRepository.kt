package dev.stapler.stelekit.repository

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
import kotlin.Result.Companion.success

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

    // Deduplicates concurrent identical getPageByName lookups (e.g. during parallel indexing).
    private val byNameCoalescer = RequestCoalescer<String, Page?>()

    override fun getPageByUuid(uuid: String): Flow<Result<Page?>> = flow {
        val cached = pageByUuidCache.get(uuid)
        if (cached != null) {
            emit(success(cached))
            return@flow
        }
        val page = queries.selectPageByUuid(uuid).executeAsOneOrNull()?.toModel()
        if (page != null) {
            pageByUuidCache.put(page.uuid, page)
            pageByNameCache.put(page.name.lowercase(), page)
        }
        emit(success(page))
    }.flowOn(PlatformDispatcher.DB)

    override fun getPageByName(name: String): Flow<Result<Page?>> = flow {
        val cached = pageByNameCache.get(name.lowercase())
        if (cached != null) {
            emit(success(cached))
            return@flow
        }
        val page = byNameCoalescer.execute(name.lowercase()) {
            queries.selectPageByName(name).executeAsOneOrNull()?.toModel()
        }
        if (page != null) {
            pageByNameCache.put(name.lowercase(), page)
            pageByUuidCache.put(page.uuid, page)
        }
        emit(success(page))
    }.flowOn(PlatformDispatcher.DB)

    override fun getPagesInNamespace(namespace: String): Flow<Result<List<Page>>> = 
        queries.selectPagesByNamespaceUnpaginated(namespace)
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> success(list.map { it.toModel() }) }

    override fun getPages(limit: Int, offset: Int): Flow<Result<List<Page>>> = 
        queries.selectAllPagesPaginated(limit.toLong(), offset.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> success(list.map { it.toModel() }) }

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Result<List<Page>>> = 
        queries.selectPagesByNameLikePaginated("%$query%", limit.toLong(), offset.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> success(list.map { it.toModel() }) }

    override fun getAllPages(): Flow<Result<List<Page>>> =
        queries.selectAllPages()
            .asFlow()
            .conflate()  // drop intermediate invalidations during bulk import to avoid O(N²) full-table scans
            .mapToList(PlatformDispatcher.DB)
            .map { list -> success(list.map { it.toModel() }) }

    override fun getJournalPages(limit: Int, offset: Int): Flow<Result<List<Page>>> =
        queries.selectJournalPages(limit.toLong(), offset.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> success(list.map { it.toModel() }) }

    override fun getJournalPageByDate(date: kotlinx.datetime.LocalDate): Flow<Result<Page?>> =
        queries.selectJournalPageByDate(date.toString())
            .asFlow()
            .mapToOneOrNull(PlatformDispatcher.DB)
            .map { success(it?.toModel()) }

    override fun getRecentPages(limit: Int): Flow<Result<List<Page>>> = 
        queries.selectRecentlyUpdatedPages(limit.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> success(list.map { it.toModel() }) }

    override fun getUnloadedPages(): Flow<Result<List<Page>>> = 
        queries.selectUnloadedPages()
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { list -> success(list.map { it.toModel() }) }

    override suspend fun savePage(page: Page): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            upsertPage(page)
            pageByUuidCache.put(page.uuid, page)
            pageByNameCache.put(page.name.lowercase(), page)
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun savePages(pages: List<Page>): Result<Unit> = withContext(PlatformDispatcher.DB) {
        if (pages.isEmpty()) return@withContext success(Unit)
        try {
            queries.transaction {
                pages.forEach { page -> upsertPage(page) }
            }
            pages.forEach { page ->
                pageByUuidCache.put(page.uuid, page)
                pageByNameCache.put(page.name.lowercase(), page)
            }
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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

    override suspend fun toggleFavorite(pageUuid: String): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val page = queries.selectPageByUuid(pageUuid).executeAsOneOrNull()
            if (page != null) {
                val newFavorite = if (page.is_favorite == 1L) 0L else 1L
                queries.updatePageFavorite(newFavorite, pageUuid)
            }
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun renamePage(pageUuid: String, newName: String): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val old = pageByUuidCache.get(pageUuid)
            if (old != null) pageByNameCache.remove(old.name.lowercase())
            queries.updatePageName(newName, pageUuid)
            pageByUuidCache.remove(pageUuid)
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deletePage(pageUuid: String): Result<Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val old = pageByUuidCache.get(pageUuid)
            if (old != null) pageByNameCache.remove(old.name.lowercase())
            queries.deletePageByUuid(pageUuid)
            pageByUuidCache.remove(pageUuid)
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun countPages(): Flow<Result<Long>> = flow {
        try {
            val count = queries.countPages().executeAsOne()
            emit(success(count))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

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
