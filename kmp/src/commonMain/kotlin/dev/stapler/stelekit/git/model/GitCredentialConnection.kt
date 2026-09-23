// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import kotlinx.serialization.Serializable

/**
 * A reusable git credential the user has previously entered — an HTTPS PAT or a completed GitHub
 * OAuth device-flow connection — kept independent of any single graph, so configuring git sync on
 * a second graph against the same account doesn't require re-pasting a token or redoing the OAuth
 * flow. The secret itself lives in the platform
 * [dev.stapler.stelekit.platform.security.CredentialStore] under [secretKey] — this model carries
 * only non-secret metadata, mirroring how [dev.stapler.stelekit.model.GraphInfo] separates
 * metadata (in the graph registry) from secret material (in CredentialStore).
 *
 * Scoped to [GitAuthType.HTTPS_TOKEN] and [GitAuthType.GITHUB_OAUTH] — SSH_KEY credentials are a
 * local key file path plus an optional passphrase, not a portable "connection" in the same sense.
 */
@Serializable
data class GitCredentialConnection(
    val id: String,
    val host: String,
    val accountLabel: String,
    val authType: GitAuthType,
    val createdAt: Long,
) {
    val secretKey: String get() = secretKeyFor(id)

    companion object {
        fun secretKeyFor(id: String): String = "git_connection_$id"
    }
}

/** Persisted (via [dev.stapler.stelekit.platform.Settings], one JSON blob) list of saved connections. */
@Serializable
data class GitCredentialConnectionRegistry(
    val connections: List<GitCredentialConnection> = emptyList(),
)
