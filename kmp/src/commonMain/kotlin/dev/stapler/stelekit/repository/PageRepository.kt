package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Lightweight (name, isJournal) projection of a page row. Used by consumers that need
 * every page name (e.g. the suggestion index) without materializing full [Page] objects —
 * properties maps, file paths and timestamps stay in the database.
 */
data class PageNameEntry(val name: String, val isJournal: Boolean)

interface PageRepository {
    fun getPageByUuid(uuid: PageUuid): Flow<Either<DomainError, Page?>>
    fun getPageByName(name: String): Flow<Either<DomainError, Page?>>
    fun getPagesInNamespace(namespace: String): Flow<Either<DomainError, List<Page>>>
    fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>>
    fun searchPages(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>>
    fun getAllPages(): Flow<Either<DomainError, List<Page>>>
    fun getJournalPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>>

    /**
     * Find a journal page by its date. Format-agnostic — always use this instead of
     * getPageByName for journal pages to avoid duplicate creation from name format differences
     * (e.g. "2026_04_11" on disk vs "2026-04-11" created in-app).
     */
    fun getJournalPageByDate(date: kotlinx.datetime.LocalDate): Flow<Either<DomainError, Page?>>

    fun getRecentPages(limit: Int = 50): Flow<Either<DomainError, List<Page>>>

    /**
     * Favorite pages only — the bounded query standing UI observers (sidebar) must use.
     *
     * The default implementation derives from [getAllPages] for in-memory/test backends.
     * SQL-backed repositories MUST override it with a dedicated `WHERE is_favorite = 1`
     * query: a standing collector on [getAllPages] re-materializes the entire pages table
     * on every write, which on 8 000+ page graphs causes GC thrash and OutOfMemoryError
     * on Android during graph import/reconcile.
     */
    fun getFavoritePages(): Flow<Either<DomainError, List<Page>>> =
        getAllPages().map { either -> either.map { pages -> pages.filter { it.isFavorite } } }

    fun getUnloadedPages(): Flow<Either<DomainError, List<Page>>>

    /**
     * Bounded batch of not-yet-content-loaded pages in stable (uuid) order — the
     * backpressure-friendly variant of [getUnloadedPages] for background indexing.
     * Callers drain by re-fetching at a fixed limit and advancing [offset] only past
     * rows that permanently fail, so peak memory is O(limit) rather than O(graph).
     *
     * Default derives from [getUnloadedPages] for in-memory/test backends; SQL-backed
     * repositories must override with a dedicated LIMIT/OFFSET query
     * (`selectUnloadedPagesPaginated`) — the in-memory fallback here exists only so fakes
     * keep compiling.
     */
    @Suppress("InMemoryPagination")
    fun getUnloadedPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> =
        getUnloadedPages().map { either ->
            either.map { pages -> pages.sortedBy { it.uuid.value }.drop(offset).take(limit) }
        }

    /** Count of not-yet-content-loaded pages — O(1) progress denominator for indexing. */
    fun countUnloadedPages(): Flow<Either<DomainError, Long>> =
        getUnloadedPages().map { either -> either.map { it.size.toLong() } }

    /**
     * Names-only projection of all pages for the suggestion index. Unlike [getAllPages],
     * a standing observer of this flow materializes only (name, isJournal) pairs —
     * a few hundred KB on an 8 000-page graph instead of tens of MB of full Page objects.
     *
     * SQL-backed repositories must override with a dedicated two-column query.
     */
    fun getPageNameEntries(): Flow<Either<DomainError, List<PageNameEntry>>> =
        getAllPages().map { either ->
            either.map { pages -> pages.map { PageNameEntry(it.name, it.isJournal) } }
        }

    /**
     * Pages whose names match [names] (case-insensitive). Bounded existence lookup used
     * by graph reconcile: one query per file chunk instead of preloading the whole table.
     * Implementations must chunk the IN list to ≤500 to respect Android's
     * SQLITE_MAX_VARIABLE_NUMBER=999 on API < 30.
     */
    suspend fun getPagesByNames(names: Collection<String>): Either<DomainError, List<Page>> {
        if (names.isEmpty()) return Either.Right(emptyList())
        val lower = names.mapTo(HashSet()) { it.lowercase() }
        return getAllPages().first().map { pages -> pages.filter { it.name.lowercase() in lower } }
    }

    /** Journal pages whose dates match [dates]. Bounded chunk lookup for reconcile. */
    suspend fun getJournalPagesByDates(
        dates: Collection<kotlinx.datetime.LocalDate>,
    ): Either<DomainError, List<Page>> {
        if (dates.isEmpty()) return Either.Right(emptyList())
        val dateSet = dates.toHashSet()
        return getAllPages().first().map { pages -> pages.filter { it.journalDate in dateSet } }
    }

    @DirectRepositoryWrite
    suspend fun savePage(page: Page): Either<DomainError, Unit>

    /**
     * Save multiple pages in a single transaction.
     * Significantly faster than calling [savePage] in a loop during bulk loads.
     */
    @DirectRepositoryWrite
    suspend fun savePages(pages: List<Page>): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun toggleFavorite(pageUuid: PageUuid): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun renamePage(pageUuid: PageUuid, newName: String): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deletePage(pageUuid: PageUuid): Either<DomainError, Unit>

    fun countPages(): Flow<Either<DomainError, Long>>

    @DirectRepositoryWrite
    suspend fun clear()

    suspend fun cacheEvictAll() {}
}
