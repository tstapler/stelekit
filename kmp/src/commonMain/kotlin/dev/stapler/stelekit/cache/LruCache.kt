// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.cache

import kotlinx.coroutines.sync.Mutex

/**
 * SAM interface for [LruCache] weighers.
 *
 * Workaround: Kotlin 2.3.10 K2 compiler bug — [LruCache] with a `(K, V) -> Long` function
 * type constructor parameter does not have its outer .class emitted for the JVM target.
 * Using a `fun interface` breaks the buggy compiler path while preserving SAM-conversion
 * call sites (callers can still pass a lambda).
 */
fun interface LruWeigher<K, V> {
    fun weigh(key: K, value: V): Long
}

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
 *
 * **Kotlin 2.3.10 K2 workaround**: when ALL public members of a class are `suspend fun`,
 * the outer `.class` file is not emitted even though inner coroutine continuation classes
 * are written. [capacity] is a non-suspend property added solely to anchor the outer class.
 * See: https://youtrack.jetbrains.com/issue/KT-XXXXX
 */
class LruCache<K : Any, V>(
    private val maxWeight: Long,
    private val weigher: LruWeigher<K, V>,
) {
    constructor(maxWeight: Long) : this(maxWeight, LruWeigher { _, _ -> 1L })
    private val mutex = Mutex()
    private val map = LinkedHashMap<K, V>()
    private var totalWeight = 0L

    /** The maximum weight this cache will hold (same as [maxWeight] ctor param). */
    // Workaround anchor: having at least one non-suspend public member forces K2 to emit
    // LruCache.class (Kotlin 2.3.10 bug — all-suspend public API suppresses outer class file).
    val capacity: Long get() = maxWeight

    override fun toString(): String = "LruCache(maxWeight=$maxWeight)"

    suspend fun get(key: K): V? {
        mutex.lock()
        try {
            val value = map.remove(key) ?: return null
            map[key] = value  // re-insert at tail = mark as most recently used
            return value
        } finally {
            mutex.unlock()
        }
    }

    suspend fun put(key: K, value: V) {
        mutex.lock()
        try {
            val old = map.remove(key)
            if (old != null) totalWeight -= weigher.weigh(key, old)
            map[key] = value
            totalWeight += weigher.weigh(key, value)
            evict()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun remove(key: K): V? {
        mutex.lock()
        try {
            val old = map.remove(key)
            if (old != null) totalWeight -= weigher.weigh(key, old)
            return old
        } finally {
            mutex.unlock()
        }
    }

    suspend fun invalidateAll() {
        mutex.lock()
        try {
            map.clear()
            totalWeight = 0L
        } finally {
            mutex.unlock()
        }
    }

    suspend fun size(): Int {
        mutex.lock()
        try {
            return map.size
        } finally {
            mutex.unlock()
        }
    }

    suspend fun weight(): Long {
        mutex.lock()
        try {
            return totalWeight
        } finally {
            mutex.unlock()
        }
    }

    private fun evict() {
        val iter = map.entries.iterator()
        while (totalWeight > maxWeight && iter.hasNext()) {
            val entry = iter.next()
            totalWeight -= weigher.weigh(entry.key, entry.value)
            iter.remove()
        }
    }
}
