// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.security.CredentialAccess
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.net.URI
import java.util.Base64

/**
 * Glue between a JGit repository and the LFS transfer pieces, shared by the JVM and Android adapters.
 * Downloads are fail-soft (a pull still lands its text; the pointer text stays in the working tree and a
 * `pending` marker makes the next sync retry), uploads are fail-closed (a pointer commit never reaches the
 * remote without its object).
 */
class LfsRepositorySupport(
    private val credentialAccess: () -> CredentialAccess,
    private val httpClientFactory: () -> HttpClient,
    /** Effective mode for a graph (stored mode resolved against platform capability); null graph = clone time. */
    @Volatile var modeFor: (graphId: String?) -> LfsMode = { LfsMode.AUTO },
) {
    private val logger = Logger("LfsRepositorySupport")

    /** True once a converting filter was written and read back for the last repository touched. */
    @Volatile var filterActive: Boolean = false
        private set

    /** Writes the filter config for an AUTO graph and records [filterActive]; returns whether LFS transfer is on. */
    fun prepare(repo: Repository, graphId: String?): Boolean {
        if (modeFor(graphId) != LfsMode.AUTO) {
            filterActive = false
            return false
        }
        LfsFilters.ensureRegistered()
        val config = repo.config
        if (config.getString("filter", "lfs", "clean") != LfsFilters.CLEAN ||
            config.getString("filter", "lfs", "smudge") != LfsFilters.SMUDGE
        ) {
            config.setString("filter", "lfs", "clean", LfsFilters.CLEAN)
            config.setString("filter", "lfs", "smudge", LfsFilters.SMUDGE)
            config.setBoolean("filter", "lfs", "required", false)
            config.save()
        }
        filterActive = repo.config.getString("filter", "lfs", "clean") == LfsFilters.CLEAN
        return filterActive
    }

    /**
     * Finishes a `setNoCheckout(true)` clone: writes the repo-local filter config first (so a user's global
     * `git-lfs` filter never runs), checks the tree out, then fetches every LFS object in HEAD and replaces the
     * pointer text that checkout left behind.
     */
    suspend fun afterClone(git: Git, authHeader: String?) {
        val active = prepare(git.repository, null)
        if (git.repository.resolve("HEAD") != null) {
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("HEAD").call()
        }
        if (!active) return
        val tree = git.repository.resolve("HEAD^{tree}") ?: return
        val pointers = LfsPointerScanner(git.repository).pointersIn(tree)
        pull(git.repository, pointers, authHeader)
    }

    /** Before a merge: download what the incoming commits change, plus anything an earlier failed attempt left behind. */
    suspend fun beforeMerge(git: Git, config: GitConfig, remoteTip: ObjectId): Map<String, LfsPointer> {
        val repo = git.repository
        if (!prepare(repo, config.graphId)) return emptyMap()
        val scanner = LfsPointerScanner(repo)
        val remoteTree = repo.parseCommit(remoteTip).tree
        val headTree = repo.resolve("HEAD^{tree}")
        val wanted = LinkedHashMap(scanner.pointersChanged(headTree, remoteTree))
        if (pendingMarker(repo).exists() && headTree != null) wanted.putAll(scanner.pointersIn(headTree))
        pull(repo, wanted, authHeaderFor(config))
        return wanted
    }

    /**
     * Background catch-up: when an earlier download failed (offline, quota, auth), the periodic fetch job calls
     * this so the images arrive without the user having to merge by hand.
     */
    suspend fun retryPending(git: Git, config: GitConfig) {
        val repo = git.repository
        if (!pendingMarker(repo).exists() || !prepare(repo, config.graphId)) return
        val tree = repo.resolve("HEAD^{tree}") ?: return
        pull(repo, LfsPointerScanner(repo).pointersIn(tree), authHeaderFor(config))
    }

    /** After a merge: make sure the working files of [pointers] hold the real bytes. */
    fun afterMerge(git: Git, pointers: Map<String, LfsPointer>) {
        if (pointers.isEmpty() || !filterActive) return
        val repo = git.repository
        val work = repo.workTree ?: return
        val written = LfsMaterializer.materialize(LfsObjectStore(repo.directory), work, pointers)
        if (written > 0) logger.info("lfs: materialised $written file(s) after merge")
    }

    /** Before a push: upload every object the new commits reference. Left means the push must not happen. */
    suspend fun beforePush(git: Git, config: GitConfig): Either<DomainError.GitError, Unit> {
        val repo = git.repository
        if (!prepare(repo, config.graphId)) return Unit.right()
        val tip = repo.resolve("HEAD") ?: return Unit.right()
        val pointers = LfsPointerScanner(repo).pointersToPush(tip, config.remoteName)
        if (pointers.isEmpty()) return Unit.right()
        val endpoint = endpointFor(repo, config.remoteName)
            ?: return DomainError.GitError.LfsNotSupportedHere("this remote has no Git LFS endpoint").left()
        return withService(repo, endpoint, authHeaderFor(config)) { it.upload(pointers) }.map { }
    }

    private suspend fun pull(repo: Repository, pointers: Map<String, LfsPointer>, authHeader: String?) {
        if (pointers.isEmpty()) {
            pendingMarker(repo).delete()
            return
        }
        val endpoint = endpointFor(repo, remoteName(repo))
        if (endpoint == null) {
            logger.warn("lfs: remote has no LFS endpoint; leaving ${pointers.size} pointer file(s) undownloaded")
            return
        }
        val result = try {
            withService(repo, endpoint, authHeader) { it.download(pointers.values) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.GitError.FetchFailed("Git LFS download failed: ${e::class.simpleName}").left()
        }
        result.fold(
            { error ->
                logger.warn("lfs: download failed, will retry next sync: ${error.message}")
                pendingMarker(repo).apply { parentFile.mkdirs(); createNewFile() }
            },
            {
                pendingMarker(repo).delete()
                val work = repo.workTree
                if (work != null) {
                    LfsMaterializer.materialize(LfsObjectStore(repo.directory), work, pointers)
                }
            },
        )
    }

    private suspend fun <T> withService(
        repo: Repository,
        endpoint: String,
        authHeader: String?,
        block: suspend (LfsTransferService) -> Either<DomainError.GitError, T>,
    ): Either<DomainError.GitError, T> {
        val http = httpClientFactory()
        try {
            val store = LfsObjectStore(repo.directory)
            return block(LfsTransferService(LfsBatchClient(http, endpoint, authHeader), store, repo.directory))
        } finally {
            http.close()
        }
    }

    private fun authHeaderFor(config: GitConfig): String? {
        val (username, key) = when (config.authType) {
            GitAuthType.HTTPS_TOKEN -> "" to config.httpsTokenKey
            GitAuthType.GITHUB_OAUTH -> "x-oauth-basic" to config.oauthTokenKey
            else -> return null // SSH-key and anonymous remotes: no HTTPS credential to send
        }
        val token = key?.let { credentialAccess().retrieve(it) } ?: return null
        return basicAuth(username, token)
    }

    private fun pendingMarker(repo: Repository) = File(repo.directory, "lfs/pending")

    private fun remoteName(repo: Repository): String =
        repo.remoteNames.let { if ("origin" in it) "origin" else it.firstOrNull() ?: "origin" }

    private fun Repository.parseCommit(id: ObjectId) = org.eclipse.jgit.revwalk.RevWalk(this).use { it.parseCommit(id) }

    companion object {
        fun basicAuth(username: String, token: String): String =
            "Basic " + Base64.getEncoder().encodeToString("$username:$token".toByteArray())

        /** `lfs.url` wins; otherwise `<remote https url>.git/info/lfs`. SSH remotes map to their https twin. Never carries credentials. */
        fun endpointFor(repo: Repository, remoteName: String): String? {
            repo.config.getString("lfs", null, "url")?.let { return it.trimEnd('/') }
            val remote = repo.config.getString("remote", remoteName, "url") ?: return null
            return endpointFromRemoteUrl(remote)
        }

        fun endpointFromRemoteUrl(remote: String): String? {
            val https = when {
                remote.startsWith("https://") || remote.startsWith("http://") -> stripUserInfo(remote)
                remote.startsWith("ssh://") -> runCatching { URI(remote) }.getOrNull()
                    ?.takeIf { it.host != null }?.let { "https://${it.host}${it.path}" }
                else -> SCP_LIKE.matchEntire(remote)?.let { "https://${it.groupValues[1]}/${it.groupValues[2]}" }
            } ?: return null
            return https.trimEnd('/').removeSuffix(".git") + ".git/info/lfs"
        }

        private fun stripUserInfo(url: String): String? = runCatching {
            val uri = URI(url)
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        }.getOrNull()

        private val SCP_LIKE = Regex("^[^@/\\s]+@([^:/\\s]+):(.+)$")
    }
}
