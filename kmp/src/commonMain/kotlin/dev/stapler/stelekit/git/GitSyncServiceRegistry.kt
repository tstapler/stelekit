// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Maps graphId to its live [GitSyncService] so a platform background worker can reach the service
 * without a DI container. Registered and unregistered by `rememberGraphContentGitSyncStack`
 * beside `GraphManager.registerGitSyncService`, so entries are balanced across graph switches.
 *
 * Copy-on-write over a [MutableStateFlow]: safe from the main thread and WorkManager threads.
 */
object GitSyncServiceRegistry {
    private val services = MutableStateFlow<Map<String, GitSyncService>>(emptyMap())

    /** A replaced service's periodic loop is stopped: the new stack composes before the old one is disposed. */
    fun register(graphId: String, service: GitSyncService) {
        val replaced = services.value[graphId]
        services.update { it + (graphId to service) }
        if (replaced != null && replaced !== service) replaced.stopPeriodicSync()
    }

    /** Removes [graphId] only while it still maps to [service], so a late dispose cannot drop a newer registration. */
    fun unregister(graphId: String, service: GitSyncService) {
        services.update { if (it[graphId] === service) it - graphId else it }
    }

    fun unregister(graphId: String) {
        services.update { it - graphId }
    }

    fun getService(graphId: String): GitSyncService? = services.value[graphId]

    /** Graph ids currently registered, for tests and diagnostics. */
    fun registeredGraphIds(): Set<String> = services.value.keys
}
