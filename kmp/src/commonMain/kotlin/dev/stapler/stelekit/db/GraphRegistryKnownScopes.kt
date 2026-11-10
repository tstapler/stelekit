// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.jobs.schedule.KnownScopes
import dev.stapler.stelekit.jobs.schedule.KnownScopesResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.model.RegistryRootPath
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Reads the persisted `graph_registry` string. [KnownScopesResult.Known] only when it parses and holds
 * at least one non-demo graph; anything else (blank, corrupt, demo-only, read failure) is
 * [KnownScopesResult.Unknown], so a worker never cancels itself on a registry it could not read.
 */
@OptIn(RegistryRootPath::class) // graph identity only: the same demo-entry check GraphManager.loadRegistry applies
class GraphRegistryKnownScopes(private val settings: Settings) : KnownScopes {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(): KnownScopesResult = try {
        val raw = settings.getString(REGISTRY_KEY, "")
        if (raw.isEmpty()) {
            KnownScopesResult.Unknown
        } else {
            val ids = json.decodeFromString<GraphRegistry>(raw).graphs
                .filter { !it.isDemo && it.path != "/demo" }
                .map { TaskScope(it.id.value) }
                .toSet()
            if (ids.isEmpty()) KnownScopesResult.Unknown else KnownScopesResult.Known(ids)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        KnownScopesResult.Unknown
    }

    private companion object {
        const val REGISTRY_KEY = "graph_registry"
    }
}
