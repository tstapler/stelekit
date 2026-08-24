package dev.stapler.stelekit.repository

import arrow.atomic.AtomicInt
import arrow.atomic.value
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlin.concurrent.Volatile

import dev.stapler.stelekit.cache.LruCache
import dev.stapler.stelekit.cache.RepoCacheConfig
import dev.stapler.stelekit.cache.RequestCoalescer
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.SectionId
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

/**
 * SQLDelight implementation of PageRepository.
 * Uses the generated SteleDatabaseQueries for all operations.
 */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightPageRepository(
    private val database: SteleDatabase,
    private val cacheWrites: Boolean = true,
) : PageRepository {

    private companion object {
        // Safe IN-clause size: SQLITE_MAX_VARIABLE_NUMBER is 999 on Android API < 30.
        const val IN_CLAUSE_CHUNK_SIZE = 500
    }

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

    /** Set after construction by RepositoryFactory once the histogram writer is available. */
    @Volatile var histogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = null
    private val _pendingReads = AtomicInt(0)

    override fun getPageByUuid(uuid: PageUuid): Flow<Either<DomainError, Page?>> {
        val enqueueMs = dev.stapler.stelekit.performance.HistogramWriter.epochMs()
        val depth = _pendingReads.incrementAndGet()
        return flow {
            _pendingReads.decrementAndGet()
            val waitMs = dev.stapler.stelekit.performance.HistogramWriter.epochMs() - enqueueMs
            if (waitMs > 5L || depth > 1) {
                histogramWriter?.record("db.read_queue_wait", waitMs)
                histogramWriter?.record("db.read_queue_depth", depth.toLong())
            }
            val cached = pageByUuidCache.get(uuid.value)
            if (cached != null) {
                emit(cached.right())
                return@flow
            }
            val page = byUuidCoalescer.execute(uuid.value) {
                queries.selectPageByUuid(uuid.value).asFlow().mapToOneOrNull(PlatformDispatcher.DB).first()?.toModel()
            }
            if (page != null) {
                pageByUuidCache.put(page.uuid.value, page)
                pageByNameCache.put(page.name.lowercase(), page)
            }
            emit(page.right())
        }.flowOn(PlatformDispatcher.DB)
    }

    override fun getPageByName(name: String): Flow<Either<DomainError, Page?>> {
        val enqueueMs = dev.stapler.stelekit.performance.HistogramWriter.epochMs()
        val depth = _pendingReads.incrementAndGet()
        return flow {
            _pendingReads.decrementAndGet()
            val waitMs = dev.stapler.stelekit.performance.HistogramWriter.epochMs() - enqueueMs
            if (waitMs > 5L || depth > 1) {
                histogramWriter?.record("db.read_queue_wait", waitMs)
                histogramWriter?.record("db.read_queue_depth", depth.toLong())
            }
            val cached = pageByNameCache.get(name.lowercase())
            if (cached != null) {
                emit(cached.right())
                return@flow
            }
            val page = byNameCoalescer.execute(name.lowercase()) {
                queries.selectPageByName(name).asFlow().mapToOneOrNull(PlatformDispatcher.DB).first()?.toModel()
            }
            if (page != null) {
                pageByNameCache.put(name.lowercase(), page)
                pageByUuidCache.put(page.uuid.value, page)
            }
            emit(page.right())
        }.flowOn(PlatformDispatcher.DB)
    }

    override fun getPagesInNamespace(namespace: String): Flow<Either<DomainError, List<Page>>> =
        queries.selectPagesByNamespaceUnpaginated(namespace)
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        queries.selectAllPagesPaginated(limit.toLong(), offset.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        queries.selectPagesByNameLikePaginated("%$query%", limit.toLong(), offset.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override fun getFavoritePages(): Flow<Either<DomainError, List<Page>>> =
        queries.selectFavoritePages()
            .asFlow()
            .conflate()  // drop intermediate invalidations during bulk import — this flow has a standing UI collector
            .mapToList(PlatformDispatcher.DB)
            .map { list -> list.map { it.toModel() }.right() }
            .catchDbError()

    override fun getJournalPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        queries.selectJournalPages(limit.toLong(), offset.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override fun getJournalPageByDate(date: kotlinx.datetime.LocalDate): Flow<Either<DomainError, Page?>> =
        queries.selectJournalPageByDate(date.toString())
            .asDbFlowOrNull(PlatformDispatcher.DB) { it.toModel() }

    override fun getJournalPageByDateAndSection(
        date: kotlinx.datetime.LocalDate,
        sectionId: String,
    ): Flow<Either<DomainError, Page?>> =
        queries.selectJournalPageByDateAndSection(date.toString(), sectionId)
            .asDbFlowOrNull(PlatformDispatcher.DB) { it.toModel() }

    override fun getRecentPages(limit: Int): Flow<Either<DomainError, List<Page>>> =
        queries.selectRecentlyUpdatedPages(limit.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override fun getUnloadedPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        queries.selectUnloadedPagesPaginated(limit.toLong(), offset.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override fun getUnloadedPagesBySection(sectionIds: Collection<String>, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        queries.selectUnloadedPagesBySection(sectionIds, limit.toLong(), offset.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override suspend fun countUnloadedPages(): Either<DomainError, Long> = withContext(PlatformDispatcher.DB) {
        try {
            queries.countUnloadedPages().asFlow().mapToOne(PlatformDispatcher.DB).first().right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
        }
    }

    override fun getPageNameEntries(): Flow<Either<DomainError, List<PageNameEntry>>> =
        queries.selectPageNameEntries()
            .asFlow()
            .conflate()  // drop intermediate invalidations during bulk import — standing observer (PageNameIndex)
            .mapToList(PlatformDispatcher.DB)
            .map { rows -> rows.map { PageNameEntry(it.name, it.is_journal == 1L) }.right() }
            .catchDbError()

    override suspend fun getPagesByNames(names: Collection<String>): Either<DomainError, List<Page>> =
        withContext(PlatformDispatcher.DB) {
            try {
                // Wrap all chunks in a single read transaction for snapshot isolation —
                // without it, a write between chunks could make the result set inconsistent.
                // Chunk the IN list: SQLITE_MAX_VARIABLE_NUMBER is 999 on Android API < 30.
                var result: List<Page> = emptyList()
                queries.transaction {
                    val allPages = mutableListOf<Page>()
                    for (chunk in names.chunked(IN_CLAUSE_CHUNK_SIZE)) {
                        queries.selectPagesByNames(chunk).asFlow().mapToList(PlatformDispatcher.DB).first().mapTo(allPages) { it.toModel() }
                    }
                    result = allPages
                }
                result.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
            }
        }

    override suspend fun getJournalPagesByDates(
        dates: Collection<kotlinx.datetime.LocalDate>,
    ): Either<DomainError, List<Page>> = withContext(PlatformDispatcher.DB) {
        try {
            var result: List<Page> = emptyList()
            queries.transaction {
                val allPages = mutableListOf<Page>()
                for (chunk in dates.chunked(IN_CLAUSE_CHUNK_SIZE)) {
                    queries.selectJournalPagesByDates(chunk.map { it.toString() })
                        .asFlow().mapToList(PlatformDispatcher.DB).first().mapTo(allPages) { it.toModel() }
                }
                result = allPages
            }
            result.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun savePage(page: Page): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            upsertPage(page)
            if (cacheWrites) {
                pageByUuidCache.put(page.uuid.value, page)
                pageByNameCache.put(page.name.lowercase(), page)
            }
            Unit.right()
        } catch (e: CancellationException) {
            throw e
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
            // Do not populate caches here — savePages is used by background bulk indexing
            // (thousands of cold pages). Caching them evicts the warm journals the user is
            // actively reading. Reads populate the cache on first access, which is sufficient.
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    private suspend fun upsertPage(page: Page) {
        queries.insertPage(
            uuid = page.uuid.value,
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
            is_content_loaded = if (page.isContentLoaded) 1L else 0L,
            section_id = page.sectionId.toDbString(),
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
            section_id = page.sectionId.toDbString(),
            uuid = page.uuid.value,
        )
    }

    override suspend fun toggleFavorite(pageUuid: PageUuid): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val page = queries.selectPageByUuid(pageUuid.value).asFlow().mapToOneOrNull(PlatformDispatcher.DB).first()
            if (page != null) {
                val newFavorite = if (page.is_favorite == 1L) 0L else 1L
                queries.updatePageFavorite(newFavorite, pageUuid.value)
            }
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun renamePage(pageUuid: PageUuid, newName: String): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val old = pageByUuidCache.get(pageUuid.value)
            if (old != null) pageByNameCache.remove(old.name.lowercase())
            queries.updatePageName(newName, pageUuid.value)
            pageByUuidCache.remove(pageUuid.value)
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun deletePage(pageUuid: PageUuid): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val old = pageByUuidCache.get(pageUuid.value)
            if (old != null) pageByNameCache.remove(old.name.lowercase())
            queries.deletePageByUuid(pageUuid.value)
            pageByUuidCache.remove(pageUuid.value)
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override fun countPages(): Flow<Either<DomainError, Long>> = flow {
        try {
            val count = queries.countPages().asFlow().mapToOne(PlatformDispatcher.DB).first()
            emit(count.right())
        } catch (e: CancellationException) {
            throw e
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
            uuid = PageUuid(this.uuid),
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
            isContentLoaded = this.is_content_loaded == 1L,
            sectionId = SectionId.fromDbString(this.section_id),
        )
    }

    // selectJournalPages narrows journal_date to non-null via IS NOT NULL, so SQLDelight
    // generates SelectJournalPages with journal_date: String instead of String?.
    private fun dev.stapler.stelekit.db.SelectJournalPages.toModel(): Page {
        return Page(
            uuid = PageUuid(this.uuid),
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
            journalDate = kotlinx.datetime.LocalDate.parse(this.journal_date),
            isContentLoaded = this.is_content_loaded == 1L,
            sectionId = SectionId.fromDbString(this.section_id),
        )
    }
}

