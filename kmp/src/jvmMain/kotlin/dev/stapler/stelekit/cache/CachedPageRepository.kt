package dev.stapler.stelekit.cache

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.LocalDate

/**
 * Cached wrapper around PageRepository.
 * Provides progressive loading with LRU, TTL, and namespace indexing.
 * Updated to use UUID-native storage.
 */
@OptIn(DirectRepositoryWrite::class)
class CachedPageRepository(
    private val delegate: PageRepository,
    private val cache: PageCache
) : PageRepository {

    override fun getPageByUuid(uuid: String): Flow<Either<DomainError, Page?>> {
        return cache.getPageByUuid(uuid)
    }

    override fun getPageByName(name: String): Flow<Either<DomainError, Page?>> {
        return cache.getPageByName(name)
    }

    override fun getPagesInNamespace(namespace: String): Flow<Either<DomainError, List<Page>>> {
        return cache.getPagesInNamespace(namespace)
    }

    override fun getAllPages(): Flow<Either<DomainError, List<Page>>> {
        return cache.getAllPages()
    }

    override fun getRecentPages(limit: Int): Flow<Either<DomainError, List<Page>>> {
        return cache.getRecentPages(limit)
    }

    override fun getUnloadedPages(): Flow<Either<DomainError, List<Page>>> {
        return delegate.getUnloadedPages()
    }

    override fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
        return cache.getPages(limit, offset)
    }

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
        return cache.searchPages(query, limit, offset)
    }

    override fun getJournalPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
        return delegate.getJournalPages(limit, offset)
    }

    override fun getJournalPageByDate(date: LocalDate): Flow<Either<DomainError, Page?>> {
        return delegate.getJournalPageByDate(date)
    }

    override suspend fun savePage(page: Page): Either<DomainError, Unit> {
        return cache.savePage(page)
    }

    override suspend fun savePages(pages: List<Page>): Either<DomainError, Unit> {
        var lastFailure: Either<DomainError, Unit> = Unit.right()
        for (page in pages) {
            val result = cache.savePage(page)
            if (result.isLeft()) lastFailure = result
        }
        return lastFailure
    }

    override suspend fun toggleFavorite(pageUuid: String): Either<DomainError, Unit> {
        return delegate.toggleFavorite(pageUuid).also {
            cache.invalidatePage(pageUuid)
        }
    }

    override suspend fun renamePage(pageUuid: String, newName: String): Either<DomainError, Unit> {
        return cache.renamePage(pageUuid, newName)
    }

    override suspend fun deletePage(pageUuid: String): Either<DomainError, Unit> {
        return cache.deletePage(pageUuid)
    }

    override fun countPages(): Flow<Either<DomainError, Long>> {
        return delegate.countPages()
    }

    override suspend fun clear() {
        delegate.clear()
        cache.clear()
    }

    fun getCacheMetrics(): CacheMetrics = cache.getMetrics()

    fun invalidatePage(uuid: String) {
        cache.invalidatePage(uuid)
    }

    fun clearCache() {
        cache.clear()
    }

    fun start() {
        cache.start()
    }

    fun stop() {
        cache.stop()
    }
}
