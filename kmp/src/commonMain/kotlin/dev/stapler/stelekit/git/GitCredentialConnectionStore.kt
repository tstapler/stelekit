// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitCredentialConnection
import dev.stapler.stelekit.git.model.GitCredentialConnectionRegistry
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * App-wide (not per-graph) store of reusable git credentials, mirroring `GraphManager`'s
 * `GraphRegistry` pattern: non-secret metadata as one JSON blob in [settings] (key
 * [REGISTRY_KEY]), secret material in [credentialStore] keyed by each connection's
 * [GitCredentialConnection.secretKey]. Configuring git sync on a second graph against the same
 * account reuses a saved connection instead of re-entering a PAT or redoing the GitHub OAuth
 * device flow — previously every graph's credential lived only under a `graphId`-scoped
 * `CredentialStore` key with no way to discover or reuse it from another graph.
 */
class GitCredentialConnectionStore(
    private val settings: Settings,
    private val credentialStore: CredentialAccess,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun loadRegistry(): GitCredentialConnectionRegistry {
        if (!settings.containsKey(REGISTRY_KEY)) return GitCredentialConnectionRegistry()
        return try {
            json.decodeFromString<GitCredentialConnectionRegistry>(settings.getString(REGISTRY_KEY, ""))
        } catch (e: Exception) {
            GitCredentialConnectionRegistry()
        }
    }

    private fun saveRegistry(registry: GitCredentialConnectionRegistry) {
        settings.putString(REGISTRY_KEY, json.encodeToString(registry))
    }

    /** All saved connections usable for [authType] (only HTTPS_TOKEN/GITHUB_OAUTH are supported). */
    fun listConnections(authType: GitAuthType): List<GitCredentialConnection> =
        loadRegistry().connections.filter { it.authType == authType }

    fun getSecret(connection: GitCredentialConnection): String? = credentialStore.retrieve(connection.secretKey)

    /**
     * Saves [secret] as a new connection (or overwrites an existing one's secret + metadata in
     * place, when [existingId] names one already in the registry — re-connecting rather than
     * duplicating). Returns the saved [GitCredentialConnection] so the caller can immediately
     * reference its [GitCredentialConnection.secretKey].
     */
    fun saveConnection(
        host: String,
        accountLabel: String,
        authType: GitAuthType,
        secret: String,
        createdAt: Long,
        existingId: String? = null,
    ): GitCredentialConnection {
        val registry = loadRegistry()
        val id = existingId ?: ContentHasher.sha256("$host|$accountLabel|$authType|$createdAt").take(16)
        val connection = GitCredentialConnection(
            id = id,
            host = host,
            accountLabel = accountLabel,
            authType = authType,
            createdAt = createdAt,
        )
        credentialStore.store(connection.secretKey, secret)
        val withoutExisting = registry.connections.filter { it.id != id }
        saveRegistry(GitCredentialConnectionRegistry(connections = withoutExisting + connection))
        return connection
    }

    fun deleteConnection(id: String) {
        val registry = loadRegistry()
        credentialStore.delete(GitCredentialConnection.secretKeyFor(id))
        saveRegistry(GitCredentialConnectionRegistry(connections = registry.connections.filter { it.id != id }))
    }

    companion object {
        private const val REGISTRY_KEY = "git_credential_connections"
    }
}
