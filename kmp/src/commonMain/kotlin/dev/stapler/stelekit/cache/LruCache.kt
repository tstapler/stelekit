// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe LRU cache sized by weight rather than entry count.
 *
 * Backed by a [LinkedHashMap] used in insertion-order mode. On each [get] the accessed
 * entry is removed and re-inserted, moving it to the tail (most-recently-used position).
 * Eviction always removes from the head (least-recently-used).
 *
 * The [weigher] function controls how many "weight units" each entry occupies.
 * The default weigher returns 1 per entry, giving classic entry-count semantics.
 * Pass a custom weigher to bound the cache by estimated byte footprint instead:
 *
 * ```kotlin
 * val blockCache = LruCache<String, Block>(
 *     maxWeight = 4_000_000L,  // ~4 MB
 *     weigher = { _, block -> 300L + block.content.length * 2 }
 * )
 * ```
 *
 * All public methods are suspend and protect the internal map with a [Mutex],
 * making this safe to call from multiple coroutines concurrently.
 */
class LruCache<K, V>(
    private val maxWeight: Long,
    private val weigher: (K, V) -> Long = { _, _ -> 1L },
) {
    private val mutex = Mutex()
    private val map = LinkedHashMap<K, V>()
    private var totalWeight = 0L

    suspend fun get(key: K): V? = mutex.withLock {
        val value = map.remove(key) ?: return@withLock null
        map[key] = value  // re-insert at tail = mark as most recently used
        value
    }

    suspend fun put(key: K, value: V) = mutex.withLock {
        val old = map.remove(key)
        if (old != null) totalWeight -= weigher(key, old)
        map[key] = value
        totalWeight += weigher(key, value)
        evict()
    }

    suspend fun remove(key: K): V? = mutex.withLock {
        val old = map.remove(key)
        if (old != null) totalWeight -= weigher(key, old)
        old
    }

    suspend fun invalidateAll() = mutex.withLock {
        map.clear()
        totalWeight = 0L
    }

    suspend fun size(): Int = mutex.withLock { map.size }

    suspend fun weight(): Long = mutex.withLock { totalWeight }

    private fun evict() {
        val iter = map.entries.iterator()
        while (totalWeight > maxWeight && iter.hasNext()) {
            val entry = iter.next()
            totalWeight -= weigher(entry.key, entry.value)
            iter.remove()
        }
    }
}
