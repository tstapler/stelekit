package dev.stapler.stelekit.cache

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.Result.Companion.success

/**
 * Page cache with LRU, TTL, and namespace indexing.
 * Wraps a PageRepository to provide progressive loading.
 * Updated to use UUID-native storage.
 */
@OptIn(DirectRepositoryWrite::class)
class PageCache(
    private val config: CacheConfig,
    private val delegate: PageRepository
) {
    private val cache = LRUCache<PageCache.Key, CachedPage>(config, "pages")
    private val namespaceIndex = ConcurrentHashMap<String, MutableList<String>>()
    private val nameIndex = ConcurrentHashMap<String, String>()
    private val metrics = MutableStateFlow(CacheMetrics())

    sealed class Key {
        data class ByUuid(val uuid: String) : Key()
        data class ByName(val name: String) : Key()
    }

    fun start() {
        cache.start()
    }

    fun stop() {
        cache.stop()
    }

    /**
     * Get page by UUID with cache.
     */
    fun getPageByUuid(uuid: String): Flow<Result<Page?>> = flow {
        try {
            val cached = cache.get(Key.ByUuid(uuid))
            if (cached != null) {
                metrics.value = metrics.value.withPageHit()
                emit(success(cached.page))
            } else {
                metrics.value = metrics.value.withPageMiss()
                delegate.getPageByUuid(uuid).collect { result ->
                    result.getOrNull()?.let { page ->
                        cachePage(page)
                    }
                    emit(result)
                }
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get page by name with cache.
     */
    fun getPageByName(name: String): Flow<Result<Page?>> = flow {
        try {
            val cachedUuid = nameIndex[name]
            if (cachedUuid != null) {
                val cached = cache.get(Key.ByUuid(cachedUuid))
                if (cached != null) {
                    metrics.value = metrics.value.withPageHit()
                    emit(success(cached.page))
                    return@flow
                }
            }

            metrics.value = metrics.value.withPageMiss()
            delegate.getPageByName(name).collect { result ->
                result.getOrNull()?.let { page ->
                    cachePage(page)
                }
                emit(result)
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get pages in namespace with cache.
     */
    fun getPagesInNamespace(namespace: String): Flow<Result<List<Page>>> = flow {
        try {
            val cachedUuids: List<String>? = namespaceIndex[namespace]
            if (cachedUuids != null && cachedUuids.isNotEmpty()) {
                val cachedPages = mutableListOf<Page>()
                var allFound = true
                for (uuid in cachedUuids) {
                    val cached = cache.get(Key.ByUuid(uuid))
                    if (cached != null) {
                        cachedPages.add(cached.page)
                    } else {
                        allFound = false
                        break
                    }
                }
                if (allFound && cachedPages.size == cachedUuids.size) {
                    metrics.value = metrics.value.withPageHit()
                    emit(success(cachedPages))
                    return@flow
                }
            }

            metrics.value = metrics.value.withPageMiss()
            delegate.getPagesInNamespace(namespace).collect { result ->
                result.getOrNull()?.let { pages ->
                    pages.forEach { cachePage(it) }
                    emit(success(pages))
                } ?: emit(result)
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get all pages with cache.
     */
    fun getAllPages(): Flow<Result<List<Page>>> = flow {
        try {
            metrics.value = metrics.value.withPageMiss()
            delegate.getAllPages().collect { result ->
                result.getOrNull()?.let { pages ->
                    pages.forEach { cachePage(it) }
                    emit(success(pages))
                } ?: emit(result)
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get recent pages with cache.
     */
    fun getRecentPages(limit: Int): Flow<Result<List<Page>>> = flow {
        try {
            metrics.value = metrics.value.withPageMiss()
            delegate.getRecentPages(limit).collect { result ->
                result.getOrNull()?.let { pages ->
                    pages.forEach { cachePage(it) }
                    emit(success(pages))
                } ?: emit(result)
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get pages with pagination.
     */
    fun getPages(limit: Int, offset: Int): Flow<Result<List<Page>>> = flow {
        try {
            metrics.value = metrics.value.withPageMiss()
            delegate.getPages(limit, offset).collect { result ->
                result.getOrNull()?.let { pages ->
                    pages.forEach { cachePage(it) }
                    emit(success(pages))
                } ?: emit(result)
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Search pages with pagination.
     */
    fun searchPages(query: String, limit: Int, offset: Int): Flow<Result<List<Page>>> = flow {
        try {
            metrics.value = metrics.value.withPageMiss()
            delegate.searchPages(query, limit, offset).collect { result ->
                result.getOrNull()?.let { pages ->
                    pages.forEach { cachePage(it) }
                    emit(success(pages))
                } ?: emit(result)
            }
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Save page - invalidates cache.
     */
    suspend fun savePage(page: Page): Result<Unit> {
        invalidatePage(page.uuid)
        return delegate.savePage(page)
    }

    /**
     * Rename page - invalidates cache.
     */
    suspend fun renamePage(pageUuid: String, newName: String): Result<Unit> {
        invalidatePage(pageUuid)
        return delegate.renamePage(pageUuid, newName)
    }

    /**
     * Delete page - invalidates cache.
     */
    suspend fun deletePage(pageUuid: String): Result<Unit> {
        invalidatePage(pageUuid)
        return delegate.deletePage(pageUuid)
    }

    /**
     * Invalidate a specific page from cache.
     */
    fun invalidatePage(uuid: String) {
        val cached = cache.get(Key.ByUuid(uuid))
        if (cached != null) {
            cache.remove(Key.ByUuid(uuid))
            cache.remove(Key.ByName(cached.page.name))
            nameIndex.remove(cached.page.name)
            cached.namespacePath?.let { ns ->
                val list: MutableList<String>? = namespaceIndex[ns]
                if (list != null) {
                    list.remove(uuid)
                }
            }
        }
    }

    /**
     * Clear all caches.
     */
    fun clear() {
        cache.clear()
        namespaceIndex.clear()
        nameIndex.clear()
    }

    /**
     * Get cache metrics.
     */
    fun getMetrics(): CacheMetrics = metrics.value

    private fun cachePage(page: Page) {
        val cached = CachedPage(
            page = page,
            namespacePath = page.namespace
        )
        cache.put(Key.ByUuid(page.uuid), cached)
        cache.put(Key.ByName(page.name), cached)

        nameIndex[page.name] = page.uuid
        page.namespace?.let { ns ->
            val list = namespaceIndex.getOrPut(ns) { mutableListOf<String>() }
            if (!list.contains(page.uuid)) {
                list.add(page.uuid)
            }
        }
    }
}
