package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
