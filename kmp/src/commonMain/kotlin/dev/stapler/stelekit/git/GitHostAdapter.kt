// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.GitHostConfig
import dev.stapler.stelekit.git.model.GitHostType

/**
 * Pure, I/O-free host-detection and request-metadata helper shared by the write path
 * (`WasmGitWriteService`) and the read path (`WasmSectionSyncService`). Every function here is
 * a total function over its inputs with no network/file access — see
 * `project_plans/web-git-writeback/implementation/plan.md` Epic 1.1.
 */
object GitHostAdapter {

    /** Classifies [remoteUrl] as GitHub, GitLab, or an unsupported host, by matching its host. */
    fun detect(remoteUrl: String): GitHostType {
        val host = extractHost(remoteUrl)
        return when {
            host == "github.com" || host.endsWith(".github.com") -> GitHostType.GITHUB
            host == "gitlab.com" || host.endsWith(".gitlab.com") -> GitHostType.GITLAB
            else -> GitHostType.UNSUPPORTED
        }
    }

    /**
     * Returns the `(headerName, headerValue)` pair to send [token] as auth for [type]. GitHub
     * and GitLab use different header *names*, not just different value prefixes.
     */
    fun authHeader(type: GitHostType, token: String): Pair<String, String> = when (type) {
        GitHostType.GITHUB -> "Authorization" to "Bearer $token"
        GitHostType.GITLAB -> "PRIVATE-TOKEN" to token
        GitHostType.UNSUPPORTED -> error("Cannot build an auth header for an unsupported git host")
    }

    /** Returns the API root for [type] scoped to [owner]/[repo]. */
    fun apiBase(type: GitHostType, owner: String, repo: String): String = when (type) {
        GitHostType.GITHUB -> "https://api.github.com/repos/$owner/$repo"
        GitHostType.GITLAB ->
            "https://gitlab.com/api/v4/projects/${percentEncodeSlashes("$owner/$repo")}"
        GitHostType.UNSUPPORTED -> error("Cannot build an API base for an unsupported git host")
    }

    /** Percent-encodes `/` as `%2F` without double-encoding characters that are already encoded. */
    private fun percentEncodeSlashes(value: String): String = value.replace("/", "%2F")

    /** Extracts the bare host from an HTTPS or SCP-style (`git@host:owner/repo`) remote URL. */
    private fun extractHost(remoteUrl: String): String {
        val stripped = remoteUrl.removeSuffix(".git")
        val scpMatch = SCP_HOST_REGEX.find(stripped)
        if (scpMatch != null) return scpMatch.groupValues[1]
        val afterScheme = stripped.substringAfter("://", stripped)
        return afterScheme.substringBefore("/").substringAfter("@")
    }

    /**
     * Extracts `(owner, repo)` from an HTTPS or SCP-style remote URL, or null if unparseable.
     * Internal (not private) so the top-level [resolve] extension function in this file can
     * reuse it without duplicating the parsing logic.
     */
    internal fun extractOwnerRepo(remoteUrl: String): Pair<String, String>? {
        val stripped = remoteUrl.removeSuffix(".git")
        val scpMatch = SCP_PATH_REGEX.find(stripped)
        val path = if (scpMatch != null) {
            scpMatch.groupValues[1]
        } else {
            stripped.substringAfter("://", stripped).substringAfter("/", "")
        }
        val segments = path.trim('/').split("/").filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val repo = segments.last()
        val owner = segments.dropLast(1).joinToString("/")
        return owner to repo
    }

    private val SCP_HOST_REGEX = Regex("^[^/@\\s]+@([^:/]+):")
    private val SCP_PATH_REGEX = Regex("^[^/@\\s]+@[^:/]+:(.+)$")
}

/**
 * Combines [GitHostAdapter.detect] + [GitHostAdapter.apiBase] into one call site for
 * `WasmGitWriteService`: derives host type and owner/repo from [remoteUrl], branch from
 * [config], and carries the raw [token] forward — call sites derive the auth header pair on
 * demand via `GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)`.
 */
fun GitHostAdapter.resolve(config: GitConfig, remoteUrl: String, token: String): GitHostConfig {
    val type = detect(remoteUrl)
    val (owner, repo) = extractOwnerRepo(remoteUrl) ?: ("" to "")
    val resolvedApiBase = if (type == GitHostType.UNSUPPORTED) "" else apiBase(type, owner, repo)
    return GitHostConfig(
        type = type,
        owner = owner,
        repo = repo,
        branch = config.remoteBranch,
        token = token,
        apiBase = resolvedApiBase,
    )
}
