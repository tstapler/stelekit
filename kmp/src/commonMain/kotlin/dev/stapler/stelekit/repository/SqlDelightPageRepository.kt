package dev.stapler.stelekit.repository

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

    override fun getPageByUuid(uuid: String): Flow<Result<Page?>> = 
        queries.selectPageByUuid(uuid)
            .asFlow()
            .mapToOneOrNull(PlatformDispatcher.IO)
            .map { success(it?.toModel()) }

    override fun getPageByName(name: String): Flow<Result<Page?>> = 
        queries.selectPageByName(name)
            .asFlow()
            .mapToOneOrNull(PlatformDispatcher.IO)
            .map { success(it?.toModel()) }

    override fun getPagesInNamespace(namespace: String): Flow<Result<List<Page>>> = 
        queries.selectPagesByNamespaceUnpaginated(namespace)
            .asFlow()
            .mapToList(PlatformDispatcher.IO)
            .map { list -> success(list.map { it.toModel() }) }

    override fun getPages(limit: Int, offset: Int): Flow<Result<List<Page>>> = 
        queries.selectAllPagesPaginated(limit.toLong(), offset.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.IO)
            .map { list -> success(list.map { it.toModel() }) }

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Result<List<Page>>> = 
        queries.selectPagesByNameLikePaginated("%$query%", limit.toLong(), offset.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.IO)
            .map { list -> success(list.map { it.toModel() }) }

    override fun getAllPages(): Flow<Result<List<Page>>> = 
        queries.selectAllPages()
            .asFlow()
            .mapToList(PlatformDispatcher.IO)
            .map { list -> success(list.map { it.toModel() }) }

    override fun getJournalPages(limit: Int, offset: Int): Flow<Result<List<Page>>> =
        queries.selectJournalPages(limit.toLong(), offset.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.IO)
            .map { list -> success(list.map { it.toModel() }) }

    override fun getJournalPageByDate(date: kotlinx.datetime.LocalDate): Flow<Result<Page?>> =
        queries.selectJournalPageByDate(date.toString())
            .asFlow()
            .mapToOneOrNull(PlatformDispatcher.IO)
            .map { success(it?.toModel()) }

    override fun getRecentPages(limit: Int): Flow<Result<List<Page>>> = 
        queries.selectRecentlyUpdatedPages(limit.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.IO)
            .map { list -> success(list.map { it.toModel() }) }

    override fun getUnloadedPages(): Flow<Result<List<Page>>> = 
        queries.selectUnloadedPages()
            .asFlow()
            .mapToList(PlatformDispatcher.IO)
            .map { list -> success(list.map { it.toModel() }) }

    override suspend fun savePage(page: Page): Result<Unit> = withContext(PlatformDispatcher.IO) {
        try {
            queries.transaction {
                // 1. Try to insert (will ignore if UUID conflict exists)
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

                // 2. Update all fields (in case it already exists)
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
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun toggleFavorite(pageUuid: String): Result<Unit> = withContext(PlatformDispatcher.IO) {
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

    override suspend fun renamePage(pageUuid: String, newName: String): Result<Unit> = withContext(PlatformDispatcher.IO) {
        try {
            queries.updatePageName(newName, pageUuid)
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deletePage(pageUuid: String): Result<Unit> = withContext(PlatformDispatcher.IO) {
        try {
            queries.deletePageByUuid(pageUuid)
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
    }.flowOn(PlatformDispatcher.IO)

    override suspend fun clear(): Unit = withContext(PlatformDispatcher.IO) {
        queries.deleteAllPages()
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
