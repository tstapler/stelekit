// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-flight request coalescer (Go's `singleflight` ported to Kotlin coroutines).
 *
 * When multiple coroutines call [execute] with the same [key] concurrently, only the
 * first one runs [loader]; the rest suspend and share its result. Once the in-flight
 * call completes (success or exception), all waiters receive the same outcome and the
 * key is removed so the next call starts a fresh load.
 *
 * This eliminates redundant database round-trips for hot keys such as:
 * - `getPageByName("Today's journal")` called from N concurrent UI collectors
 * - `getBlockHierarchy(pageUuid)` triggered by multiple composables on the same page
 *
 * Thread-safety: all state is protected by a [Mutex]; callers never need external locking.
 *
 * Example:
 * ```kotlin
 * private val coalescer = RequestCoalescer<String, Page?>()
 *
 * override fun getPageByName(name: String): Flow<Result<Page?>> = flow {
 *     val page = coalescer.execute(name) {
 *         queries.selectPageByName(name).executeAsOneOrNull()?.toModel()
 *     }
 *     emit(Result.success(page))
 * }.flowOn(PlatformDispatcher.DB)
 * ```
 */
class RequestCoalescer<K : Any, V> {
    private val mutex = Mutex()
    private val inflight = HashMap<K, CompletableDeferred<V>>()

    /**
     * Execute [loader] for [key], or join an in-flight execution if one exists.
     *
     * Exceptions thrown by [loader] propagate to all waiters.
     */
    suspend fun execute(key: K, loader: suspend () -> V): V {
        val (deferred, isOwner) = mutex.withLock {
            val existing = inflight[key]
            if (existing != null) {
                existing to false
            } else {
                val new = CompletableDeferred<V>()
                inflight[key] = new
                new to true
            }
        }

        if (isOwner) {
            try {
                val result = loader()
                deferred.complete(result)
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            } finally {
                mutex.withLock { inflight.remove(key) }
            }
        }

        return deferred.await()
    }

    /** Number of keys currently in-flight. */
    suspend fun inflightCount(): Int = mutex.withLock { inflight.size }
}
