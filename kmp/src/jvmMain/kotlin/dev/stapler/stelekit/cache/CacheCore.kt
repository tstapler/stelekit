package dev.stapler.stelekit.cache

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockWithDepth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.random.Random

/**
 * Configuration for the cache system.
 * Controls sizes, TTLs, and prefetch behavior.
 */
data class CacheConfig(
    /** Maximum number of blocks to cache */
    val maxBlocks: Int = 10_000,

    /** Maximum number of pages to cache */
    val maxPages: Int = 1_000,

    /** TTL for block cache entries in milliseconds */
    val blockTtlMs: Long = 5 * 60 * 1000,  // 5 minutes

    /** TTL for page cache entries in milliseconds */
    val pageTtlMs: Long = 10 * 60 * 1000,  // 10 minutes

    /** TTL for hierarchy cache in milliseconds */
    val hierarchyTtlMs: Long = 2 * 60 * 1000,  // 2 minutes

    /** Prefetch depth for hierarchies (0 = disabled) */
    val prefetchDepth: Int = 2,

    /** Enable background prefetch */
    val enablePrefetch: Boolean = true,

    /** Cleanup interval in milliseconds */
    val cleanupIntervalMs: Long = 60_000,  // 1 minute

    /** Maximum hierarchy cache size */
    val maxHierarchies: Int = 100,

    /** Enable metrics collection */
    val enableMetrics: Boolean = true
)

/**
 * Cache metrics for monitoring and tuning.
 */
data class CacheMetrics(
    val blockHits: Long = 0,
    val blockMisses: Long = 0,
    val pageHits: Long = 0,
    val pageMisses: Long = 0,
    val hierarchyHits: Long = 0,
    val hierarchyMisses: Long = 0,
    val prefetchCount: Long = 0,
    val evictionCount: Long = 0,
    val expiredCount: Long = 0
) {
    val blockHitRate: Double
        get() = if (blockHits + blockMisses > 0) blockHits.toDouble() / (blockHits + blockMisses) else 0.0

    val pageHitRate: Double
        get() = if (pageHits + pageMisses > 0) pageHits.toDouble() / (pageHits + pageMisses) else 0.0

    val hierarchyHitRate: Double
        get() = if (hierarchyHits + hierarchyMisses > 0) hierarchyHits.toDouble() / (hierarchyHits + hierarchyMisses) else 0.0

    fun withBlockHit() = copy(blockHits = blockHits + 1)
    fun withBlockMiss() = copy(blockMisses = blockMisses + 1)
    fun withPageHit() = copy(pageHits = pageHits + 1)
    fun withPageMiss() = copy(pageMisses = pageMisses + 1)
    fun withHierarchyHit() = copy(hierarchyHits = hierarchyHits + 1)
    fun withHierarchyMiss() = copy(hierarchyMisses = hierarchyMisses + 1)
    fun withPrefetch() = copy(prefetchCount = prefetchCount + 1)
    fun withEviction() = copy(evictionCount = evictionCount + 1)
    fun withExpired() = copy(expiredCount = expiredCount + 1)
}

/**
 * Thread-safe LRU cache entry.
 */
sealed class CacheEntry<out T> {
    data class Valid<T>(val value: T, val timestamp: Long) : CacheEntry<T>()
    data object Invalid : CacheEntry<Nothing>()
}

/**
 * LRU Cache with TTL support.
 */
class LRUCache<K, V>(
    private val config: CacheConfig,
    private val name: String
) {
    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()
    private val lruOrder = ConcurrentLinkedQueue<K>()
    private val metrics = MutableStateFlow(CacheMetrics())
    private var cleanupJob: Job? = null

    private val maxSize: Int
        get() = when (name) {
            "blocks" -> config.maxBlocks
            "pages" -> config.maxPages
            "hierarchies" -> config.maxHierarchies
            else -> 1000
        }

    private val ttlMs: Long
        get() = when (name) {
            "blocks" -> config.blockTtlMs
            "pages" -> config.pageTtlMs
            "hierarchies" -> config.hierarchyTtlMs
            else -> config.blockTtlMs
        }

    fun start() {
        if (config.enableMetrics) {
            cleanupJob = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    delay(config.cleanupIntervalMs)
                    cleanupExpired()
                }
            }
        }
    }

    fun stop() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    fun get(key: K): V? {
        val entry = cache[key] ?: return null

        return when (entry) {
            is CacheEntry.Valid -> {
                if (isExpired(entry.timestamp)) {
                    cache.remove(key)
                    metrics.value = metrics.value.withExpired()
                    null
                } else {
                    updateLRU(key)
                    metrics.value = metrics.value.withBlockHit().let { metrics.value }
                    entry.value
                }
            }
            is CacheEntry.Invalid -> null
        }
    }

    fun put(key: K, value: V) {
        val timestamp = System.currentTimeMillis()
        cache[key] = CacheEntry.Valid(value, timestamp)
        updateLRU(key)
        evictIfNeeded()
    }

    fun remove(key: K) {
        cache.remove(key)
    }

    fun invalidate(key: K) {
        cache[key] = CacheEntry.Invalid
    }

    fun clear() {
        cache.clear()
        lruOrder.clear()
    }

    fun getOrLoad(key: K, loader: suspend () -> V?): V? {
        return get(key) ?: runBlocking {
            loader()?.let { loaded ->
                put(key, loaded)
                when (name) {
                    "blocks" -> metrics.value = metrics.value.withBlockMiss()
                    "pages" -> metrics.value = metrics.value.withPageMiss()
                    "hierarchies" -> metrics.value = metrics.value.withHierarchyMiss()
                }
                loaded
            }
        }
    }

    fun getMetrics(): CacheMetrics = metrics.value

    private fun isExpired(timestamp: Long): Boolean {
        return System.currentTimeMillis() - timestamp > ttlMs
    }

    private fun updateLRU(key: K) {
        lruOrder.remove(key)
        lruOrder.add(key)
    }

    private fun evictIfNeeded() {
        while (cache.size > maxSize && lruOrder.isNotEmpty()) {
            val oldestKey = lruOrder.poll() ?: break
            if (cache.remove(oldestKey) != null) {
                metrics.value = metrics.value.withEviction()
            }
        }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        cache.forEach { (key, entry) ->
            if (entry is CacheEntry.Valid && now - entry.timestamp > ttlMs) {
                if (cache.remove(key) != null) {
                    metrics.value = metrics.value.withExpired()
                    lruOrder.remove(key)
                }
            }
        }
    }

    fun size(): Int = cache.size
}

/**
 * Cached block with computed children reference for fast hierarchy lookups.
 * Updated to use UUID-native storage.
 */
data class CachedBlock(
    val block: Block,
    val childrenUuids: List<String> = emptyList(),
    val parentId: String? = block.parentUuid,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Cached page with computed namespace path.
 */
data class CachedPage(
    val page: Page,
    val namespacePath: String? = page.namespace,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Cached hierarchy tree for fast subtree retrieval.
 */
data class CachedHierarchy(
    val rootUuid: String,
    val blocks: List<BlockWithDepth>,
    val timestamp: Long = System.currentTimeMillis()
)
