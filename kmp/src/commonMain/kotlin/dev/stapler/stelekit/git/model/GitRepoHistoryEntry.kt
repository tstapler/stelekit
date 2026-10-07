// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

import kotlinx.serialization.Serializable

/**
 * Which field a [GitRepoHistoryEntry] was captured from — kept distinct so "Use existing clone"
 * only ever suggests remembered local paths and "Clone a remote repository" only ever suggests
 * remembered URLs; the two are never valid in the other field.
 */
@Serializable
enum class GitRepoHistoryKind { LOCAL_PATH, CLONE_URL }

/**
 * A repository location the user has successfully configured git sync against before —
 * remembered so configuring a second graph against a repository used before offers it as a pick
 * instead of retyping a path or re-copying a clone URL. Mirrors [GitCredentialConnection]'s
 * shape, minus the secret-material split:
 * nothing in this model is secret, so it needs no [dev.stapler.stelekit.platform.security.CredentialStore]
 * counterpart.
 */
@Serializable
data class GitRepoHistoryEntry(
    val value: String,
    val kind: GitRepoHistoryKind,
    val wikiSubdir: String,
    val lastUsedAt: Long,
)

/** Persisted (via [dev.stapler.stelekit.platform.Settings], one JSON blob) list of saved entries. */
@Serializable
data class GitRepoHistoryRegistry(
    val entries: List<GitRepoHistoryEntry> = emptyList(),
)
