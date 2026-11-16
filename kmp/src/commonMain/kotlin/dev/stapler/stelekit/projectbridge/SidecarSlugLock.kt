// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One mutex per sketch project slug, held by a sidecar writer across its whole read-plan-write (reconciler, sheet
 * saver, converter, restore), not just inside one store call. Not reentrant. Lock order: the slug lock first, then
 * the store's own mutex; never acquire a slug lock while holding another.
 */
class SidecarSlugLock {
    private val guard = Mutex()
    private val locks = HashMap<String, Mutex>()

    suspend fun <T> withSlug(slug: String, block: suspend () -> T): T {
        val mutex = guard.withLock { locks.getOrPut(slug) { Mutex() } }
        return mutex.withLock { block() }
    }
}
