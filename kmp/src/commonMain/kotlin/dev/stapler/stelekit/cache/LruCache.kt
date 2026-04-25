// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.cache

/**
 * SAM interface for [SteleLruCache] weighers. Callers may pass a plain lambda via SAM conversion.
 */
fun interface SteleLruWeigher<K, V> {
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
 * val blockCache = SteleLruCache<String, Block>(
 *     maxWeight = 4_000_000L,  // ~4 MB
 *     weigher = { _, block -> 300L + block.content.length * 2 }
 * )
 * ```
 *
 * Thread-safety is achieved via `synchronized(this)`.
 * All operations are O(1) and hold the lock for microseconds.
 *
 * Named [SteleLruCache] (not LruCache) to avoid a Kotlin 2.3.10 K2 compiler bug where
 * the class named exactly "LruCache" has its `.class` file silently dropped in packaged JARs.
 * See: https://youtrack.jetbrains.com/issue/KT-XXXXX
 */
class SteleLruCache<K : Any, V>(
    val maxWeight: Long,
    private val weigher: SteleLruWeigher<K, V>,
) {
    constructor(maxWeight: Long) : this(maxWeight, SteleLruWeigher { _, _ -> 1L })

    private val map = LinkedHashMap<K, V>()
    private var totalWeight = 0L

    fun get(key: K): V? = synchronized(this) {
        val value = map.remove(key) ?: return null
        map[key] = value  // re-insert at tail = mark as most recently used
        value
    }

    fun put(key: K, value: V): Unit = synchronized(this) {
        val old = map.remove(key)
        if (old != null) totalWeight -= weigher.weigh(key, old)
        map[key] = value
        totalWeight += weigher.weigh(key, value)
        evict()
    }

    fun remove(key: K): V? = synchronized(this) {
        val old = map.remove(key)
        if (old != null) totalWeight -= weigher.weigh(key, old)
        old
    }

    fun invalidateAll(): Unit = synchronized(this) {
        map.clear()
        totalWeight = 0L
    }

    fun size(): Int = synchronized(this) { map.size }

    fun weight(): Long = synchronized(this) { totalWeight }

    override fun toString(): String = "SteleLruCache(maxWeight=$maxWeight)"

    private fun evict() {
        val iter = map.entries.iterator()
        while (totalWeight > maxWeight && iter.hasNext()) {
            val entry = iter.next()
            totalWeight -= weigher.weigh(entry.key, entry.value)
            iter.remove()
        }
    }
}

// Backwards-compatible type aliases so existing call sites need no changes.
typealias LruCache<K, V> = SteleLruCache<K, V>
typealias LruWeigher<K, V> = SteleLruWeigher<K, V>
