// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.model

/**
 * Resolved, host-correct request metadata produced by `GitHostAdapter.resolve` — combines
 * [GitHostAdapter.detect]-derived [type], the owner/repo parsed from the configured remote URL,
 * and the API base URL ([GitHostAdapter.apiBase]) with the raw [token] into a single value for
 * `WasmGitWriteService` call sites. Call sites derive the auth header pair on demand via
 * `GitHostAdapter.authHeader(type, token)` — this matches the plan's Domain Glossary shape
 * `GitHostConfig(type, owner, repo, branch, token, apiBase)`, used directly by every Phase 3
 * acceptance criterion (e.g. `GitHostConfig(GITHUB, "tstapler", "steno-wiki", "main", token)`).
 */
data class GitHostConfig(
    val type: GitHostType,
    val owner: String,
    val repo: String,
    val branch: String,
    val token: String,
    val apiBase: String,
)
