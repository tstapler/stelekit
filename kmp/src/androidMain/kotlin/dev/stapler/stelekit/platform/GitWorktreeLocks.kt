// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * Shared per-`shadowKey` mutex holder for the SAF shadow git worktree.
 *
 * Both the write-back actor and the SAF-to-shadow sync path acquire the same mutex for a given
 * `shadowKey` to serialize concurrent access to that graph's shadow worktree, without either
 * side needing to depend on the other.
 */
internal object GitWorktreeLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun lockFor(shadowKey: String): Mutex =
        locks.getOrPut(shadowKey) { Mutex() }
}
