// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitRepoHistoryEntry
import dev.stapler.stelekit.git.model.GitRepoHistoryKind
import dev.stapler.stelekit.git.model.GitRepoHistoryRegistry
import dev.stapler.stelekit.platform.Settings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * App-wide (not per-graph) history of repository locations the user has successfully configured
 * git sync against — mirrors [GitCredentialConnectionStore]'s registry-in-[Settings] shape, minus
 * the secret-material split (nothing stored here is secret). Configuring a second graph against a
 * repository used before offers it as a pick in the Git Sync wizard instead of requiring the
 * path/URL to be retyped from scratch.
 */
class GitRepoHistoryStore(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun loadRegistry(): GitRepoHistoryRegistry {
        if (!settings.containsKey(REGISTRY_KEY)) return GitRepoHistoryRegistry()
        return try {
            json.decodeFromString<GitRepoHistoryRegistry>(settings.getString(REGISTRY_KEY, ""))
        } catch (e: Exception) {
            GitRepoHistoryRegistry()
        }
    }

    private fun saveRegistry(registry: GitRepoHistoryRegistry) {
        settings.putString(REGISTRY_KEY, json.encodeToString(registry))
    }

    /** Entries for [kind], most recently used first, capped at [MAX_ENTRIES]. */
    fun recentEntries(kind: GitRepoHistoryKind): List<GitRepoHistoryEntry> =
        loadRegistry().entries.filter { it.kind == kind }.sortedByDescending { it.lastUsedAt }.take(MAX_ENTRIES)

    /**
     * Records a successful use of [value] — upserted by `(value, kind)` so reusing the same
     * repository updates its [GitRepoHistoryEntry.wikiSubdir]/[GitRepoHistoryEntry.lastUsedAt] in
     * place rather than accumulating duplicates. No-ops on a blank [value] — nothing to remember
     * until a real path/URL was actually entered.
     */
    fun record(value: String, kind: GitRepoHistoryKind, wikiSubdir: String, now: Long) {
        if (value.isBlank()) return
        val registry = loadRegistry()
        val withoutExisting = registry.entries.filter { !(it.value == value && it.kind == kind) }
        val entry = GitRepoHistoryEntry(value = value, kind = kind, wikiSubdir = wikiSubdir, lastUsedAt = now)
        saveRegistry(GitRepoHistoryRegistry(entries = withoutExisting + entry))
    }

    companion object {
        private const val MAX_ENTRIES = 5
        private const val REGISTRY_KEY = "git_repo_history"
    }
}
