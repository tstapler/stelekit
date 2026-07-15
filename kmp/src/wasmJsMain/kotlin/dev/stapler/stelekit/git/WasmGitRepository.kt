// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.WasmGitWriteService.Companion.installRetryPolicy
import dev.stapler.stelekit.git.model.GitCommitLogEntry
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.GitHostConfig
import dev.stapler.stelekit.git.model.GitHostType
import dev.stapler.stelekit.git.model.GitLabCommitLogEntry
import dev.stapler.stelekit.git.model.PendingCommit
import dev.stapler.stelekit.git.model.gitApiJson
import dev.stapler.stelekit.platform.PlatformFileSystem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

/**
 * WASM (web) implementation of [GitRepository] — Phase 4 of
 * `project_plans/web-git-writeback/implementation/plan.md`, Epic 4.1. Maps the working-tree-shaped
 * [GitRepository] contract onto the checkout-less REST model [WasmGitWriteService] already
 * implements, so `GitSyncService` (unmodified) can drive the entire sync/conflict flow on web
 * exactly like it does against `JvmGitRepository`/`AndroidGitRepository`.
 *
 * ### Design decisions (see the worker return notes for detail)
 * - **`baseSha`/`pendingCommit` tracking**: [WasmGitWriteService.fetch]/`push`/`merge` accept these
 *   as explicit parameters rather than tracking them internally (see that class's KDoc). This class
 *   resolves both, per call, from [PlatformFileSystem.getBaseSha]/[PlatformFileSystem.getPendingCommit]
 *   (new minimal getters added in this epic) — a single source of truth in `PlatformFileSystem`
 *   rather than a second, privately-duplicated copy here, matching this plan's "always re-derive"
 *   principle.
 * - **Resolving [GitHostConfig]**: [GitConfig] carries no raw git remote URL or token (see
 *   `GitConfigRepository`) — those live wherever this graph's git credentials are actually stored,
 *   which is Epic 4.2/4.3's wiring concern, not this epic's. [configResolver] is an injected
 *   `suspend (GitConfig) -> GitHostConfig?` so Epic 4.3's `Main.kt` can supply the real lookup
 *   (e.g. reading `PlatformFileSystem`'s `githubOwner`/`githubRepo`/`githubToken` companion fields,
 *   already used by the read path) without this class needing to know where credentials live. A
 *   `null` result (host unresolvable — e.g. no config saved, or credentials unavailable) maps to
 *   [DomainError.GitError.AuthFailed].
 * - **GitLab's push-time commit message**: [WasmGitRepository.push] has no `message` parameter (the
 *   [GitRepository] interface's `commit()` already consumed it), but GitLab's write-back model
 *   defers the actual commit to push time (see [WasmGitWriteService.GitLabPushContext]'s doc).
 *   [buildCommitMessage] is a pure function of [GitConfig] (day-granularity date), so re-deriving it
 *   at push time yields the identical string `commit()` was called with in the overwhelmingly
 *   common case (same calendar day) — this avoids this class caching a second piece of message
 *   state purely to thread it from `commit()` to `push()`.
 * - **`checkoutFile`'s `remoteRef`**: [WasmGitWriteService.checkoutFile] wants "the new remote head
 *   the conflict was detected against", but [GitRepository.checkoutFile] passes no such ref. Using
 *   [GitHostConfig.branch] (the branch name) instead of a captured sha is behaviorally identical for
 *   fetching *current* remote content (both GitHub's raw-content URL and GitLab's raw-file API
 *   accept a branch name as `ref`) and avoids this class needing to cache the sha `merge()` last
 *   fetched.
 * - **`merge()`'s conflict translation**: [WasmGitWriteService.merge] surfaces conflicts as
 *   `Either.Left(MergeConflict)` (never a `MergeResult` with a non-empty `conflicts` list), so this
 *   class translates that into `Either.Right(MergeResult(hasConflicts = true, ...))` to match
 *   [GitRepository.merge]'s existing contract (mirrors `JvmGitRepository.merge`, which always
 *   returns `Right`, encoding conflicts inside the value).
 * - **`log()`**: issues its own GET requests via [httpClient] rather than adding a new public method
 *   to [WasmGitWriteService] (out of scope for this epic — see the task briefing) — the small
 *   response models it decodes were added to `GitDataApiModels.kt`/`GitLabCommitModels.kt` by this
 *   epic (Task 4.1.1e).
 */
class WasmGitRepository(
    private val gitWriteService: WasmGitWriteService,
    private val httpClient: HttpClient,
    private val fileSystem: PlatformFileSystem,
    private val configResolver: suspend (GitConfig) -> GitHostConfig?,
) : GitRepository {

    // ============================ Task 4.1.1a: trivial no-ops ============================

    override suspend fun isGitRepo(path: String): Boolean = true

    override suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit> = Unit.right()

    override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()

    override suspend fun hasDetachedHead(config: GitConfig): Boolean = false

    override suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()

    // ============================== Task 4.1.1b: status ==============================

    override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> {
        val snapshot = fileSystem.getDirtySnapshot()
        return GitStatus(
            hasLocalChanges = snapshot.isNotEmpty(),
            untrackedFiles = emptyList(),
            modifiedFiles = snapshot.keys.toList(),
        ).right()
    }

    // ==================== Task 4.1.1c: commit/fetch/merge/push ====================

    override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
        val hostConfig = resolveHostConfig(config).getOrElse { return it.left() }
        val baseSha = fileSystem.getBaseSha()
        gitWriteService.commit(config, hostConfig, baseSha, message).getOrElse { return it.left() }
        val sha = (fileSystem.getPendingCommit() as? PendingCommit.Staged)?.commitSha ?: baseSha
        return sha.right()
    }

    override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
        val hostConfig = resolveHostConfig(config).getOrElse { return it.left() }
        return gitWriteService.fetch(hostConfig, fileSystem.getBaseSha())
    }

    override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> {
        val hostConfig = resolveHostConfig(config).getOrElse { return it.left() }
        return when (val result = gitWriteService.merge(config, hostConfig, fileSystem.getBaseSha())) {
            is Either.Left -> {
                val err = result.value
                if (err is DomainError.GitError.MergeConflict) {
                    MergeResult(
                        hasConflicts = true,
                        conflicts = gitWriteService.buildConflictFiles(err.conflictPaths.toSet()),
                        changedFiles = emptyList(),
                    ).right()
                } else {
                    err.left()
                }
            }
            is Either.Right -> result
        }
    }

    override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> {
        val hostConfig = resolveHostConfig(config).getOrElse { return it.left() }
        val gitLabContext = if (hostConfig.type == GitHostType.GITLAB) {
            WasmGitWriteService.GitLabPushContext(
                config = config,
                baseSha = fileSystem.getBaseSha(),
                message = buildCommitMessage(config),
            )
        } else {
            null
        }
        return gitWriteService.push(hostConfig, fileSystem.getPendingCommit(), gitLabContext)
    }

    // ============ Task 4.1.1d: checkoutFile/markResolved/abortMerge delegates ============

    override suspend fun checkoutFile(
        config: GitConfig,
        filePath: String,
        side: MergeSide,
    ): Either<DomainError.GitError, Unit> {
        val hostConfig = resolveHostConfig(config).getOrElse { return it.left() }
        return gitWriteService.checkoutFile(config, hostConfig, filePath, remoteRef = hostConfig.branch, side)
    }

    override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> =
        gitWriteService.markResolved()

    override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> =
        gitWriteService.abortMerge()

    // ============================== Task 4.1.1e: log() ==============================

    override suspend fun log(config: GitConfig, maxCount: Int): Either<DomainError.GitError, List<GitCommit>> {
        val hostConfig = resolveHostConfig(config).getOrElse { return it.left() }
        return when (hostConfig.type) {
            GitHostType.GITHUB -> fetchGitHubLog(hostConfig, maxCount)
            GitHostType.GITLAB -> fetchGitLabLog(hostConfig, maxCount)
            GitHostType.UNSUPPORTED -> DomainError.GitError.NotSupported(hostConfig.type.name).left()
        }
    }

    private suspend fun fetchGitHubLog(
        hostConfig: GitHostConfig,
        maxCount: Int,
    ): Either<DomainError.GitError, List<GitCommit>> = try {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        val response = httpClient.get("${hostConfig.apiBase}/commits") {
            header(headerName, headerValue)
            parameter("sha", hostConfig.branch)
            parameter("per_page", maxCount)
        }
        if (response.status.value !in 200..299) {
            DomainError.GitError.FetchFailed("GET commits failed: HTTP ${response.status.value}").left()
        } else {
            response.body<List<GitCommitLogEntry>>().map { entry ->
                GitCommit(
                    sha = entry.sha,
                    shortMessage = entry.commit.message.substringBefore('\n'),
                    authorName = entry.commit.author.name,
                    timestamp = Instant.parse(entry.commit.author.date).toEpochMilliseconds(),
                )
            }.right()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainError.GitError.NetworkFailure(e.message ?: "GET commits failed").left()
    }

    private suspend fun fetchGitLabLog(
        hostConfig: GitHostConfig,
        maxCount: Int,
    ): Either<DomainError.GitError, List<GitCommit>> = try {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        val response = httpClient.get("${hostConfig.apiBase}/repository/commits") {
            header(headerName, headerValue)
            parameter("ref_name", hostConfig.branch)
            parameter("per_page", maxCount)
        }
        if (response.status.value !in 200..299) {
            DomainError.GitError.FetchFailed("GET repository/commits failed: HTTP ${response.status.value}").left()
        } else {
            response.body<List<GitLabCommitLogEntry>>().map { entry ->
                GitCommit(
                    sha = entry.id,
                    shortMessage = entry.title,
                    authorName = entry.authorName,
                    timestamp = Instant.parse(entry.authoredDate).toEpochMilliseconds(),
                )
            }.right()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainError.GitError.NetworkFailure(e.message ?: "GET repository/commits failed").left()
    }

    // ============================== Task 4.1.1f: clone ==============================

    override suspend fun clone(
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (String) -> Unit,
    ): Either<DomainError.GitError, Unit> = DomainError.GitError.NotSupported("web").left()

    // ================================ Shared helpers ================================

    private suspend fun resolveHostConfig(config: GitConfig): Either<DomainError.GitError, GitHostConfig> =
        configResolver(config)?.right()
            ?: DomainError.GitError.AuthFailed("Unable to resolve git host configuration for this graph").left()

    companion object {
        /**
         * Builds a [WasmGitRepository] sharing one [HttpClient] (Story 3.4.1's retry policy
         * installed) between its own `log()` GET calls and the [WasmGitWriteService] it wraps,
         * mirroring [WasmGitWriteService.withDefaultClient].
         */
        fun withDefaultClient(
            fileSystem: PlatformFileSystem,
            configResolver: suspend (GitConfig) -> GitHostConfig?,
        ): WasmGitRepository {
            val client = HttpClient {
                install(ContentNegotiation) { json(gitApiJson) }
                installRetryPolicy()
            }
            return WasmGitRepository(
                gitWriteService = WasmGitWriteService(client, fileSystem),
                httpClient = client,
                fileSystem = fileSystem,
                configResolver = configResolver,
            )
        }
    }
}
