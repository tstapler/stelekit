package dev.stapler.stelekit.cache

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

    override fun getPageByUuid(uuid: String): Flow<Result<Page?>> {
        return cache.getPageByUuid(uuid)
    }

    override fun getPageByName(name: String): Flow<Result<Page?>> {
        return cache.getPageByName(name)
    }

    override fun getPagesInNamespace(namespace: String): Flow<Result<List<Page>>> {
        return cache.getPagesInNamespace(namespace)
    }

    override fun getAllPages(): Flow<Result<List<Page>>> {
        return cache.getAllPages()
    }

    override fun getRecentPages(limit: Int): Flow<Result<List<Page>>> {
        return cache.getRecentPages(limit)
    }

    override fun getUnloadedPages(): Flow<Result<List<Page>>> {
        return delegate.getUnloadedPages()
    }

    override fun getPages(limit: Int, offset: Int): Flow<Result<List<Page>>> {
        return cache.getPages(limit, offset)
    }

    override fun searchPages(query: String, limit: Int, offset: Int): Flow<Result<List<Page>>> {
        return cache.searchPages(query, limit, offset)
    }

    override fun getJournalPages(limit: Int, offset: Int): Flow<Result<List<Page>>> {
        return delegate.getJournalPages(limit, offset)
    }

    override fun getJournalPageByDate(date: LocalDate): Flow<Result<Page?>> {
        return delegate.getJournalPageByDate(date)
    }

    override suspend fun savePage(page: Page): Result<Unit> {
        return cache.savePage(page)
    }

    override suspend fun savePages(pages: List<Page>): Result<Unit> {
        var lastFailure: Result<Unit> = Result.success(Unit)
        for (page in pages) {
            val result = cache.savePage(page)
            if (result.isFailure) lastFailure = result
        }
        return lastFailure
    }

    override suspend fun toggleFavorite(pageUuid: String): Result<Unit> {
        return delegate.toggleFavorite(pageUuid).also {
            cache.invalidatePage(pageUuid)
        }
    }

    override suspend fun renamePage(pageUuid: String, newName: String): Result<Unit> {
        return cache.renamePage(pageUuid, newName)
    }

    override suspend fun deletePage(pageUuid: String): Result<Unit> {
        return cache.deletePage(pageUuid)
    }

    override fun countPages(): Flow<Result<Long>> {
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
