// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitCredentialConnectionStore
import dev.stapler.stelekit.git.GitHostAdapter
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.GitHostType
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.security.CredentialStore
import dev.stapler.stelekit.util.ContentHasher
import kotlin.time.Clock

/**
 * True for text that could not possibly be a valid relative wiki-subdirectory path — i.e. it
 * carries a URI scheme. Guards against a picked `content://`/`saf://` URI landing in the Wiki
 * subdirectory field (which must always be a plain relative path under the repo root).
 */
internal fun looksLikeUri(value: String): Boolean = value.contains("://")

/** `owner/my-notes.git` → `my-notes`; null when [url] has no usable last segment. */
internal fun repoNameFromUrl(url: String): String? =
    url.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':')
        .removeSuffix(".git").takeIf { it.isNotBlank() && !it.contains("://") }

/** Null when [value] is a usable relative subfolder (or empty); otherwise a user-facing reason. */
internal fun wikiSubdirError(value: String): String? = when {
    value.startsWith("/") || value.startsWith("~") || value.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(value) ->
        "Use a path relative to the repository root, e.g. notes/pages"
    value.split('/').any { it == ".." } -> "\"..\" isn't allowed"
    else -> null
}

/**
 * Populates the `PlatformSettings` keys ("githubOwner"/"githubRepo"/"githubBranch"/"githubToken")
 * that `browser/Main.kt`'s `configResolver` reads once at wasmJs startup — the credential source
 * the write engine (`WasmGitRepository`) actually trusts. Without this, a web user's saved
 * `HTTPS_TOKEN` PAT (persisted via [CredentialStore], a no-op on wasmJs for a different, accepted
 * reason) is never visible to `configResolver`.
 *
 * [cloneUrl] is parsed via [GitHostAdapter.extractOwnerRepo] to derive `owner`/`repo`; no-ops
 * (does not overwrite any existing settings with blanks) when [authType] is not
 * [GitAuthType.HTTPS_TOKEN], [cloneUrl] is blank or unparseable, or [token] is blank.
 *
 * Only takes effect for the current session after a page reload on web, since `Main.kt` reads
 * `PlatformSettings` exactly once at startup — [GitAuthType.GITHUB_OAUTH]'s equivalent gap (the
 * device-flow token is never written here) is a known, separate follow-up.
 *
 * Safe no-op-equivalent on JVM/Android: those platforms never read these specific
 * `PlatformSettings` keys back, so writing them there is harmless, just slightly redundant.
 */
internal fun persistWebGitCredentials(
    cloneUrl: String,
    branch: String,
    authType: GitAuthType,
    token: String,
) {
    if (authType != GitAuthType.HTTPS_TOKEN) return
    if (cloneUrl.isBlank() || token.isBlank()) return
    val (owner, repo) = GitHostAdapter.extractOwnerRepo(cloneUrl) ?: return
    val settings = PlatformSettings()
    settings.putString("githubOwner", owner)
    settings.putString("githubRepo", repo)
    settings.putString("githubBranch", branch)
    settings.putString("githubToken", token)
}

/**
 * Persists [httpsToken] under [graphId]'s graph-scoped CredentialStore key (the key every
 * platform's git auth code actually reads at operation time — unchanged by connection reuse) and
 * returns that key, or [fallbackKey] when [httpsToken] is blank (nothing to persist — e.g. editing
 * a graph without touching the token field). When [selectedConnectionId] is null — the user typed
 * a brand-new token rather than picking a saved [dev.stapler.stelekit.git.model.GitCredentialConnection] —
 * also remembers it as a new connection so a future graph can reuse it without re-pasting.
 * [cloneUrl] (only ever non-blank on the "clone a new repo" path) is used best-effort to label the
 * new connection by host; a blank/unparseable URL falls back to a generic label rather than
 * skipping the save.
 */
internal fun resolveHttpsTokenKey(
    graphId: String,
    httpsToken: String,
    cloneUrl: String,
    selectedConnectionId: String?,
    connectionStore: GitCredentialConnectionStore,
    credentialStore: CredentialStore,
    fallbackKey: String?,
): String? {
    if (httpsToken.isBlank()) return fallbackKey
    val key = "git_https_token_$graphId"
    credentialStore.store(key, httpsToken)
    if (selectedConnectionId == null) {
        val hostType = cloneUrl.takeIf { it.isNotBlank() }?.let(GitHostAdapter::detect)
            ?.takeIf { it != GitHostType.UNSUPPORTED }
        val host = hostType?.let { if (it == GitHostType.GITHUB) "github.com" else "gitlab.com" } ?: "git"
        connectionStore.saveConnection(
            host = host,
            accountLabel = if (hostType != null) "$host token" else "Personal access token",
            authType = GitAuthType.HTTPS_TOKEN,
            secret = httpsToken,
            createdAt = Clock.System.now().toEpochMilliseconds(),
        )
    }
    return key
}

/**
 * Resolves the CredentialStore key to use for [GitConfig.oauthTokenKey]. When [selectedConnectionId]
 * names a saved connection (the user picked "Saved accounts" instead of running the device flow
 * again), copies that connection's secret into [graphId]'s graph-scoped key — the key every
 * platform's git auth code actually reads — since connections are otherwise invisible to that
 * code. A fresh device-flow completion already stores its token directly under that graph-scoped
 * key (see `startOAuthFlow`), so [fallbackKey] covers that case unchanged.
 */
internal fun resolveOauthTokenKey(
    graphId: String,
    selectedConnectionId: String?,
    connectionStore: GitCredentialConnectionStore,
    credentialStore: CredentialStore,
    fallbackKey: String?,
): String? {
    val key = "git_github_oauth_$graphId"
    val secret = selectedConnectionId
        ?.let { id -> connectionStore.listConnections(GitAuthType.GITHUB_OAUTH).firstOrNull { it.id == id } }
        ?.let { connectionStore.getSecret(it) }
    if (secret != null) {
        credentialStore.store(key, secret)
        return key
    }
    return fallbackKey
}

/**
 * Mirrors [dev.stapler.stelekit.db.GraphManager.graphIdFromPath] exactly (same hash, same
 * `take(16)`), so a [dev.stapler.stelekit.model.StorageLocation.AppOwned] resolved here for
 * [dev.stapler.stelekit.ui.components.UnifiedLocationPicker] — before the graph exists — carries
 * the same id `GraphManager.addGraph(path, location)` computes for that same [expandedPath] once
 * the clone completes (Story 2.2.2, Task 2.2.2c).
 */
internal fun graphIdFromPath(expandedPath: String): String =
    ContentHasher.sha256(expandedPath).take(16)

/** Builds the [GitAuth] the clone-a-new-repo flow (Step 1/3/5) passes to `clone()`/`testRemote()`. */
internal fun buildCloneAuth(
    authType: GitAuthType,
    httpsToken: String,
    sshKeyPath: String,
    sshPassphrase: String,
    graphId: String,
    credentialStore: CredentialStore,
): GitAuth = when (authType) {
    GitAuthType.HTTPS_TOKEN -> GitAuth.HttpsToken(
        username = "",
        tokenProvider = { httpsToken.takeIf { it.isNotBlank() } }
    )
    GitAuthType.SSH_KEY -> GitAuth.SshKey(
        keyPath = sshKeyPath,
        passphraseProvider = { sshPassphrase.takeIf { it.isNotBlank() } },
    )
    GitAuthType.GITHUB_OAUTH -> GitAuth.HttpsToken(
        username = "x-oauth-basic",
        tokenProvider = { credentialStore.retrieve("git_github_oauth_$graphId") }
    )
    GitAuthType.NONE -> GitAuth.None
}

internal fun buildConfig(
    graphId: String,
    repoRoot: String,
    wikiSubdir: String,
    authType: GitAuthType,
    sshKeyPath: String,
    remoteBranch: String,
    pollIntervalMinutes: Int,
    httpsTokenKey: String? = null,
    sshKeyPassphraseKey: String? = null,
    oauthTokenKey: String? = null,
): GitConfig = GitConfig(
    graphId = graphId,
    repoRoot = repoRoot,
    wikiSubdir = wikiSubdir,
    authType = authType,
    sshKeyPath = sshKeyPath.takeIf { it.isNotBlank() },
    remoteBranch = remoteBranch,
    pollIntervalMinutes = pollIntervalMinutes,
    httpsTokenKey = httpsTokenKey,
    sshKeyPassphraseKey = sshKeyPassphraseKey,
    oauthTokenKey = oauthTokenKey,
)
