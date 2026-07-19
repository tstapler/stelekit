// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.DirtyEntry
import dev.stapler.stelekit.git.model.DirtyOp
import dev.stapler.stelekit.git.model.GitBlobRequest
import dev.stapler.stelekit.git.model.GitBlobResponse
import dev.stapler.stelekit.git.model.GitCommitAuthor
import dev.stapler.stelekit.git.model.GitCommitRequest
import dev.stapler.stelekit.git.model.GitCommitResponse
import dev.stapler.stelekit.git.model.GitCompareResponse
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.GitHostConfig
import dev.stapler.stelekit.git.model.GitHostType
import dev.stapler.stelekit.git.model.GitLabCommitAction
import dev.stapler.stelekit.git.model.GitLabCommitRequest
import dev.stapler.stelekit.git.model.GitLabCommitResponse
import dev.stapler.stelekit.git.model.GitLabCompareResponse
import dev.stapler.stelekit.git.model.GitLabTreeEntry
import dev.stapler.stelekit.git.model.GitRefResponse
import dev.stapler.stelekit.git.model.GitRefUpdateRequest
import dev.stapler.stelekit.git.model.GitTreeEntry
import dev.stapler.stelekit.git.model.GitTreeRequest
import dev.stapler.stelekit.git.model.GitTreeResponse
import dev.stapler.stelekit.git.model.PendingCommit
import dev.stapler.stelekit.git.model.gitApiJson
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.PlatformFileSystem
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

/**
 * WASM-only write-back engine (Phase 3 of `project_plans/web-git-writeback/implementation/plan.md`):
 * creates GitHub/GitLab commit objects for the current dirty set and advances the remote branch
 * ref, entirely via each host's REST API — there is no local git checkout on web, so this class
 * replaces `JvmGitRepository`/`AndroidGitRepository`'s JGit calls with direct HTTP calls.
 *
 * This file is extended sequentially by later epics in the same plan — keep new host-specific
 * logic behind clearly labeled sections (`// ── GitHub ──`, `// ── GitLab ──`) and small private
 * helpers rather than growing the orchestrators in place:
 *  - Epic 3.2 adds the GitLab single-call push path (replace the `GitHostType.GITLAB` branches
 *    in [commit]/[fetch]/[push] below with real implementations).
 *  - Epic 3.3 adds conflict-detection/auto-merge helpers (`partitionConflicts`, `mergeAndCommit`).
 *  - Epic 3.4 wraps [httpClient] calls with retry/backoff + rate-limit handling.
 *
 * ### Design decisions carried forward from Epic 3.1 (see the worker return notes for detail)
 * - [GitHostConfig] (produced by `GitHostAdapter.resolve`) does not carry the raw git remote URL,
 *   so [GitWriteLock.lockNameFor] is derived from [GitHostConfig.apiBase] instead — that string
 *   is already unique per host+owner+repo and fully deterministic, which is all a lock name
 *   needs; it does not need to be the literal remote URL.
 * - [PlatformFileSystem] exposes no public getter for its private `baseSha`/`pendingCommit`
 *   fields (only mutators: `setPendingCommit`/`clearDirtySet`), so [fetch] and [push] accept
 *   `baseSha`/`pendingCommit` as explicit parameters — the caller (`WasmGitRepository`, Epic 4.1)
 *   is expected to track/read these itself (e.g. from the restored dirty-set marker).
 * - [commit] accepts [GitConfig] (for `config.repoRoot`) and reconstructs each dirty path's
 *   absolute OPFS cache key as `"${config.repoRoot}/$repoRelativePath"` to read its content via
 *   [PlatformFileSystem.getContentBytes] (raw bytes, covering both plain-text and paranoid-mode
 *   encrypted content — never [PlatformFileSystem.readFile], which is String-only and silently
 *   drops paranoid-mode dirty entries) — this assumes `GitConfig.repoRoot` is set to the same
 *   OPFS graph path passed to `PlatformFileSystem.preload()` on web (mirrors `recordDirty`'s own
 *   `"/stelekit/".removePrefix(...)`-relative convention).
 * - `message` is accepted as an explicit parameter (built by the caller via the existing
 *   `buildCommitMessage(config)`), matching how `GitSyncService` already calls
 *   `gitRepository.commit(config, buildCommitMessage(config))` for the JVM/Android paths.
 * - GitLab has no server-side "stage a commit without moving the branch" primitive, so its
 *   `commit()` is a genuine no-op (never calls `setPendingCommit`) and [push] instead accepts an
 *   optional [GitLabPushContext] (config/baseSha/message) so the GitLab branch can re-derive its
 *   commits-API request from the dirty set as it stands at push time, rather than caching a
 *   request built earlier at `commit()` time that could go stale.
 */
class WasmGitWriteService(
    private val httpClient: HttpClient,
    private val fileSystem: PlatformFileSystem,
) {

    /** Epic 3.5 (Story 3.5.1): tag matches the class name, per this repo's `Logger` convention. */
    private val logger = Logger("WasmGitWriteService")

    // ============================ Epic 3.5: outcome logging helpers ============================
    //
    // Shared formatters for the four terminal outcomes named by Story 3.5.1's acceptance
    // criteria (success / conflict-detected / auto-merged / failed-with-reason), called only from
    // push()/merge()'s dispatcher-level terminal branches (or their host-specific
    // pushGitHub/pushViaGitLab/mergeGitHub/mergeGitLab implementations) — never scattered into
    // every low-level HTTP helper. None of these ever log a raw HTTP response body (which could
    // echo request headers, including the Authorization/PRIVATE-TOKEN header) or the token itself
    // — only the step marker and the `DomainError.GitError` subtype name.

    /** Success: file count and new commit sha, never file content. */
    private fun logPushSuccess(fileCount: Int, commitSha: String) {
        logger.info("git write-back outcome=success $fileCount file(s) commit=$commitSha")
    }

    /** Auto-merged: the list of remote-only-changed paths folded in without conflict. */
    private fun logAutoMerged(paths: Collection<String>) {
        logger.info("git write-back outcome=auto-merged paths=$paths")
    }

    /**
     * Conflict-detected (a [DomainError.GitError.MergeConflict]) vs. failed-with-reason (anything
     * else) — both always carry [step] and [err]'s subtype name so a "my commit never showed up"
     * report is diagnosable from the step alone, never from the raw response body.
     */
    private fun logTerminalFailure(step: String, err: DomainError.GitError) {
        if (err is DomainError.GitError.MergeConflict) {
            logger.info(
                "git write-back outcome=conflict-detected step=$step error=${err::class.simpleName} " +
                    "conflictPaths=${err.conflictPaths}"
            )
        } else {
            logger.error(
                "git write-back outcome=failed-with-reason step=$step error=${err::class.simpleName}"
            )
        }
    }

    // ============================== Public orchestrators ==============================

    /**
     * Story 3.1.1: stages a commit object (blob → tree → commit) for the current dirty set
     * without moving the branch ref. Empty dirty set is a no-op success (desktop parity).
     */
    suspend fun commit(
        config: GitConfig,
        hostConfig: GitHostConfig,
        baseSha: String,
        message: String,
    ): Either<DomainError.GitError, Unit> {
        val dirty = fileSystem.getDirtySnapshot()
        if (dirty.isEmpty()) return Unit.right()

        return when (hostConfig.type) {
            GitHostType.GITHUB -> commitGitHub(config, hostConfig, baseSha, message, dirty)
            GitHostType.GITLAB -> commitGitLab()
            GitHostType.UNSUPPORTED -> DomainError.GitError.NotSupported(hostConfig.type.name).left()
        }
    }

    /**
     * Story 3.1.2: checks the remote ref against [baseSha] and reports how far it has diverged.
     */
    suspend fun fetch(
        hostConfig: GitHostConfig,
        baseSha: String,
    ): Either<DomainError.GitError, FetchResult> = when (hostConfig.type) {
        GitHostType.GITHUB -> fetchGitHub(hostConfig, baseSha)
        GitHostType.GITLAB -> fetchGitLab(hostConfig, baseSha)
        GitHostType.UNSUPPORTED -> DomainError.GitError.NotSupported(hostConfig.type.name).left()
    }

    /**
     * Story 3.1.2: advances the remote branch ref to the staged commit and clears the dirty set
     * on success. No-op success (and no lock acquisition) when nothing is staged.
     *
     * GitHub reads its staged commit/tree SHAs from [pendingCommit] (created by [commitGitHub]).
     * GitLab has no server-side "stage a commit object" primitive (Pattern Decision "Commit/push
     * split (GitLab)") — its `commit()` is a true no-op, so [pendingCommit] never carries anything
     * for GitLab. Instead [push] re-derives the GitLab commits-API request from the *current*
     * dirty set at push time via [gitLabContext], matching the plan's "always re-derive, never
     * resume partial state" principle rather than caching a request built at an earlier `commit()`
     * call that could go stale if more edits land before the push actually happens.
     */
    suspend fun push(
        hostConfig: GitHostConfig,
        pendingCommit: PendingCommit,
        gitLabContext: GitLabPushContext? = null,
    ): Either<DomainError.GitError, Unit> = when (hostConfig.type) {
        GitHostType.GITHUB -> {
            if (pendingCommit !is PendingCommit.Staged) {
                Unit.right()
            } else {
                GitWriteLock.withLock(GitWriteLock.lockNameFor(hostConfig.apiBase)) {
                    pushGitHub(hostConfig, pendingCommit)
                }
            }
        }
        GitHostType.GITLAB -> {
            val dirty = fileSystem.getDirtySnapshot()
            if (dirty.isEmpty()) {
                Unit.right()
            } else if (gitLabContext == null) {
                val err = DomainError.GitError.CommitFailed(
                    "push() for GitLab requires a non-null gitLabContext to rebuild the commits request"
                )
                logTerminalFailure(step = "gitlab-context-missing", err = err)
                err.left()
            } else {
                GitWriteLock.withLock(GitWriteLock.lockNameFor(hostConfig.apiBase)) {
                    pushViaGitLab(hostConfig, gitLabContext, dirty)
                }
            }
        }
        GitHostType.UNSUPPORTED -> {
            val err = DomainError.GitError.NotSupported(hostConfig.type.name)
            logTerminalFailure(step = "host-unsupported", err = err)
            err.left()
        }
    }

    /**
     * Story 3.3.1/3.3.2 (Epic 3.3): dispatches to the host-specific merge implementation, mirroring
     * [commit]/[fetch]/[push]'s host `when` dispatch. Called independently of [fetch] per
     * [GitRepository.merge]'s existing contract (no [FetchResult] is threaded through) — both host
     * branches re-derive the remote-changed-path set via their own compare call, matching
     * [mergeGitLab]'s existing precedent. `hasConflicts = true` results are surfaced as
     * `Either.Left(MergeConflict)` (never a `MergeResult` with a non-empty `conflicts` list) —
     * callers translate that into `MergeResult(hasConflicts = true, conflicts =
     * buildConflictFiles(err.conflictPaths.toSet()), ...)` per [GitRepository.merge]'s contract.
     */
    suspend fun merge(
        config: GitConfig,
        hostConfig: GitHostConfig,
        baseSha: String,
    ): Either<DomainError.GitError, MergeResult> = when (hostConfig.type) {
        GitHostType.GITHUB -> mergeGitHub(config, hostConfig, baseSha)
        GitHostType.GITLAB -> mergeGitLab(hostConfig, baseSha)
        GitHostType.UNSUPPORTED -> {
            val err = DomainError.GitError.NotSupported(hostConfig.type.name)
            logTerminalFailure(step = "host-unsupported", err = err)
            err.left()
        }
    }

    /**
     * Story 3.3.2/Task 3.3.2b-d (Epic 3.3): the [GitRepository.checkoutFile] equivalent this class
     * exposes for Epic 4.1's `WasmGitRepository` to delegate to — host-agnostic via
     * [fetchRemoteFileContent].
     *
     * [MergeSide.REMOTE] fetches the file's content at [remoteRef] (the new remote head the
     * conflict was detected against) and writes it via the **normal**, dirty-tracked [PlatformFileSystem.writeFile]
     * — this is the user's own resolution choice, so it must be included in the next `commit()`,
     * unlike [mergeAndCommit]'s auto-merge writes (which use `applyRemoteContent` instead).
     * [MergeSide.LOCAL] is a no-op — OPFS already holds the user's local content.
     *
     * This single code path also covers the deleted-locally-but-edited-remotely edge case (Task
     * 3.3.2d): [MergeSide.LOCAL] keeps a locally-deleted path deleted (still a no-op); [MergeSide.REMOTE]
     * resurrects it via the identical fetch+`writeFile` call, since `writeFile` re-adds the path to
     * the cache regardless of its prior deleted state.
     */
    suspend fun checkoutFile(
        config: GitConfig,
        hostConfig: GitHostConfig,
        filePath: String,
        remoteRef: String,
        side: MergeSide,
    ): Either<DomainError.GitError, Unit> = when (side) {
        MergeSide.LOCAL -> Unit.right()
        MergeSide.REMOTE -> {
            val content = fetchRemoteFileContent(hostConfig, filePath, remoteRef).getOrElse { return it.left() }
            fileSystem.writeFile("${config.repoRoot}/$filePath", content)
            Unit.right()
        }
    }

    /**
     * Story 3.3.2/Task 3.3.2c (Epic 3.3): [GitRepository.markResolved] equivalent — pure
     * bookkeeping no-op. Neither GitHub's nor GitLab's write-back model tracks a separate
     * "resolved" state server-side (unlike JGit's index-stage concept on desktop/Android) —
     * resolution is fully captured by the dirty-tracked content [checkoutFile] already wrote,
     * picked up automatically by the next [commit] call's dirty-set snapshot.
     */
    fun markResolved(): Either<DomainError.GitError, Unit> = Unit.right()

    /**
     * Story 3.3.2/Task 3.3.2c (Epic 3.3): [GitRepository.abortMerge] equivalent — resets any
     * staged [PendingCommit] (from a completed [commit] or an auto-merge [mergeAndCommit] rebuild)
     * back to [PendingCommit.None] via [PlatformFileSystem.resetPendingCommit], without touching
     * the dirty set, so an abandoned merge attempt's staged commit/tree SHAs are never later
     * pushed and the next sync attempt re-derives everything from scratch.
     */
    fun abortMerge(): Either<DomainError.GitError, Unit> {
        fileSystem.resetPendingCommit()
        return Unit.right()
    }

    /**
     * Story 3.3.1b/3.3.2b (Epic 3.3): fetches [path]'s raw content at [ref] (a commit sha or
     * branch name) — shared by GitHub's auto-merge tree rebuild ([mergeGitHub]/[mergeAndCommit])
     * and [checkoutFile]'s `REMOTE`-side resolution write. Deliberately does not write anywhere
     * itself — callers choose [PlatformFileSystem.applyRemoteContent] (auto-merge, not dirty-tracked)
     * or [PlatformFileSystem.writeFile] (user-driven resolution, dirty-tracked) once this returns.
     */
    suspend fun fetchRemoteFileContent(
        hostConfig: GitHostConfig,
        path: String,
        ref: String,
    ): Either<DomainError.GitError, String> = when (hostConfig.type) {
        GitHostType.GITHUB -> fetchRawUrl(
            hostConfig,
            url = "https://raw.githubusercontent.com/${hostConfig.owner}/${hostConfig.repo}/$ref/$path",
        )
        GitHostType.GITLAB -> fetchRawUrl(
            hostConfig,
            url = "${hostConfig.apiBase}/repository/files/${path.replace("/", "%2F")}/raw?ref=$ref",
        )
        GitHostType.UNSUPPORTED -> DomainError.GitError.NotSupported(hostConfig.type.name).left()
    }

    /** Shared plain-text GET used by both hosts' raw-content-fetch branches above. */
    private suspend fun fetchRawUrl(
        hostConfig: GitHostConfig,
        url: String,
    ): Either<DomainError.GitError, String> = try {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        val response = httpClient.get(url) { header(headerName, headerValue) }
        when {
            response.status.value == 404 ->
                DomainError.GitError.FetchFailed("Remote file not found: $url").left()
            response.status.value !in 200..299 -> mapHttpFailure(response.status.value, response.headers) {
                DomainError.GitError.FetchFailed("GET raw content failed: HTTP ${response.status.value}")
            }.left()
            else -> response.bodyAsText().right()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainError.GitError.NetworkFailure(e.message ?: "GET raw content failed").left()
    }

    // =================================== GitHub =========================================

    private suspend fun commitGitHub(
        config: GitConfig,
        hostConfig: GitHostConfig,
        baseSha: String,
        message: String,
        dirty: Map<String, DirtyEntry>,
    ): Either<DomainError.GitError, Unit> {
        val deletedPaths = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, ByteArray>>()

        for ((path, entry) in dirty) {
            when (entry.op) {
                DirtyOp.DELETE -> deletedPaths += path
                DirtyOp.WRITE -> {
                    // BLOCKER fix: read raw bytes uniformly (plain-text AND paranoid-mode content)
                    // via getContentBytes — readFile() alone never sees bytesCache-only entries.
                    val bytes = fileSystem.getContentBytes("${config.repoRoot}/$path")
                        ?: return DomainError.GitError.CommitFailed(
                            "No cached content for dirty path: $path"
                        ).left()
                    val sizeBytes = bytes.size.toLong()
                    if (sizeBytes > MAX_BLOB_BYTES) {
                        return DomainError.GitError.FileTooLarge(path, sizeBytes, MAX_BLOB_BYTES).left()
                    }
                    writes += path to bytes
                }
            }
        }

        val blobShas = createBlobsBounded(hostConfig, writes).getOrElse { return it.left() }

        val treeSha = buildTree(hostConfig, baseSha, blobShas, deletedPaths).getOrElse { return it.left() }
        val commitSha = createCommitObject(hostConfig, treeSha, baseSha, message).getOrElse { return it.left() }

        fileSystem.setPendingCommit(commitSha, treeSha)
        return Unit.right()
    }

    /**
     * Task 3.4.1c: bounded-concurrency blob creation — at most [BLOB_CREATE_CONCURRENCY]
     * `createBlob` calls in flight at once for [writes] (path, content pairs), replacing what
     * would otherwise be a naive `map { async { ... } }` with unlimited fan-out. A sequential
     * `for` loop would trivially (accidentally) satisfy "no more than N concurrent," so this uses
     * real concurrent dispatch gated by a [Semaphore] — the only way `IT-3.4.1-A`'s
     * concurrent-in-flight assertion is a meaningful test rather than a vacuous one.
     */
    private suspend fun createBlobsBounded(
        hostConfig: GitHostConfig,
        writes: List<Pair<String, ByteArray>>,
    ): Either<DomainError.GitError, Map<String, String>> = coroutineScope {
        val semaphore = Semaphore(BLOB_CREATE_CONCURRENCY)
        val results = writes.map { (path, content) ->
            async { path to semaphore.withPermit { createBlob(hostConfig, content) } }
        }.awaitAll()

        val blobShas = mutableMapOf<String, String>()
        for ((path, result) in results) {
            when (result) {
                is Either.Left -> return@coroutineScope result.value.left()
                is Either.Right -> blobShas[path] = result.value
            }
        }
        blobShas.right()
    }

    /** Task 3.1.1a: base64-encodes [content] and creates a GitHub blob object. */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun createBlob(
        hostConfig: GitHostConfig,
        content: ByteArray,
    ): Either<DomainError.GitError, String> {
        val encoded = Base64.Default.encode(content)
        return githubPost<GitBlobRequest, GitBlobResponse>(
            hostConfig,
            "git/blobs",
            GitBlobRequest(content = encoded),
        ).map { it.sha }
    }

    /** Task 3.1.1c: assembles write (blob sha) + delete (null sha) entries into a new tree. */
    private suspend fun buildTree(
        hostConfig: GitHostConfig,
        baseSha: String,
        blobShas: Map<String, String>,
        deletedPaths: List<String>,
    ): Either<DomainError.GitError, String> {
        val entries = buildList {
            blobShas.forEach { (path, sha) ->
                add(GitTreeEntry(path = path, mode = TREE_ENTRY_MODE, type = TREE_ENTRY_TYPE, sha = sha))
            }
            deletedPaths.forEach { path ->
                add(GitTreeEntry(path = path, mode = TREE_ENTRY_MODE, type = TREE_ENTRY_TYPE, sha = null))
            }
        }
        return githubPost<GitTreeRequest, GitTreeResponse>(
            hostConfig,
            "git/trees",
            GitTreeRequest(baseTree = baseSha, tree = entries),
        ).map { it.sha }
    }

    /** Task 3.1.1d: creates the commit object pointing at [treeSha] with a single [parentSha]. */
    private suspend fun createCommitObject(
        hostConfig: GitHostConfig,
        treeSha: String,
        parentSha: String,
        message: String,
    ): Either<DomainError.GitError, String> {
        val request = GitCommitRequest(
            message = message,
            tree = treeSha,
            parents = listOf(parentSha),
            author = GitCommitAuthor(
                name = COMMIT_AUTHOR_NAME,
                email = COMMIT_AUTHOR_EMAIL,
                date = Clock.System.now().toString(),
            ),
        )
        return githubPost<GitCommitRequest, GitCommitResponse>(hostConfig, "git/commits", request).map { it.sha }
    }

    /** Task 3.1.2a: fetches the remote branch ref's current commit sha. */
    private suspend fun fetchRefSha(hostConfig: GitHostConfig): Either<DomainError.GitError, String> =
        githubGet<GitRefResponse>(
            hostConfig,
            "git/ref/heads/${hostConfig.branch}",
            notFoundMessage = "Branch '${hostConfig.branch}' not found on remote",
        ).map { it.obj.sha }

    /** Task 3.1.2b: fetches the compare delta between [baseSha] and [headSha]. */
    private suspend fun fetchCompareDelta(
        hostConfig: GitHostConfig,
        baseSha: String,
        headSha: String,
    ): Either<DomainError.GitError, GitCompareResponse> =
        githubGet(hostConfig, "compare/$baseSha...$headSha")

    /** Task 3.1.2c: orchestrates 3.1.2a/b into a [FetchResult]. */
    private suspend fun fetchGitHub(
        hostConfig: GitHostConfig,
        baseSha: String,
    ): Either<DomainError.GitError, FetchResult> {
        val remoteSha = fetchRefSha(hostConfig).getOrElse { return it.left() }
        if (remoteSha == baseSha) {
            return FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()
        }
        val compare = fetchCompareDelta(hostConfig, baseSha, remoteSha).getOrElse { return it.left() }
        return FetchResult(hasRemoteChanges = true, remoteCommitCount = compare.aheadBy).right()
    }

    /**
     * Task 3.1.2d: advances the branch ref to [commitSha] with `force=false`. A `409`/`422`
     * indicates the ref moved between `fetch()`'s read and this write — a genuine merge conflict,
     * not a generic push failure — so it is mapped to [DomainError.GitError.MergeConflict]
     * distinctly from other non-2xx statuses (mapped to [DomainError.GitError.PushFailed]).
     */
    private suspend fun advanceRef(
        hostConfig: GitHostConfig,
        commitSha: String,
    ): Either<DomainError.GitError, Unit> = try {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        val response = httpClient.patch("${hostConfig.apiBase}/git/refs/heads/${hostConfig.branch}") {
            header(headerName, headerValue)
            contentType(ContentType.Application.Json)
            setBody(GitRefUpdateRequest(sha = commitSha, force = false))
        }
        when (response.status.value) {
            in 200..299 -> Unit.right()
            409, 422 -> DomainError.GitError.MergeConflict(
                conflictCount = 1,
                conflictPaths = listOf(UNKNOWN_CONFLICT_PATH),
            ).left()
            else -> mapHttpFailure(response.status.value, response.headers) {
                DomainError.GitError.PushFailed("PATCH ref failed: HTTP ${response.status.value}")
            }.left()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Task 3.4.1d: no HTTP response was ever received here (connection refused, DNS
        // failure, offline mid-request) — distinct from a *received* erroring response, which
        // mapHttpFailure above already handles. This previously fell back to PushFailed, making
        // a network-level failure indistinguishable from a real server-side rejection.
        DomainError.GitError.NetworkFailure(e.message ?: "PATCH ref failed").left()
    }

    /**
     * Task 3.1.2e: the write-critical-section [push] wraps in [GitWriteLock.withLock] — the ref
     * PATCH and the dirty-set clear that must not interleave with another tab's push.
     */
    private suspend fun pushGitHub(
        hostConfig: GitHostConfig,
        staged: PendingCommit.Staged,
    ): Either<DomainError.GitError, Unit> {
        val fileCount = fileSystem.getDirtySnapshot().size
        advanceRef(hostConfig, staged.commitSha).getOrElse { err ->
            logTerminalFailure(step = "ref-update", err = err)
            return err.left()
        }
        fileSystem.clearDirtySet(newBaseSha = staged.commitSha)
        logPushSuccess(fileCount = fileCount, commitSha = staged.commitSha)
        return Unit.right()
    }

    /**
     * Story 3.3.1/3.3.2: GitHub's merge orchestrator (mirrors [mergeGitLab]'s shape — re-derives
     * the remote-changed-path set from its own ref+compare call, independent of [fetch], per
     * [GitRepository]'s contract — but additionally needs [config] to read local dirty blob
     * content and to rebuild a fresh commit object: unlike GitLab's `start_sha`-based commit
     * creation, GitHub has no "preserve untouched files" primitive, so a new tree/commit is always
     * required to layer remote-only-changed content on top of the new remote head).
     */
    suspend fun mergeGitHub(
        config: GitConfig,
        hostConfig: GitHostConfig,
        baseSha: String,
    ): Either<DomainError.GitError, MergeResult> {
        val remoteSha = fetchRefSha(hostConfig).getOrElse { err ->
            logTerminalFailure(step = "merge-fetch-ref", err = err)
            return err.left()
        }
        if (remoteSha == baseSha) {
            return MergeResult(hasConflicts = false, conflicts = emptyList(), changedFiles = emptyList()).right()
        }

        val compare = fetchCompareDelta(hostConfig, baseSha, remoteSha).getOrElse { err ->
            logTerminalFailure(step = "merge-compare", err = err)
            return err.left()
        }
        val localPaths = fileSystem.getDirtySnapshot().keys
        val remotePaths = compare.files.map { it.filename }.toSet()
        val partition = partitionConflicts(localPaths, remotePaths)

        if (partition.conflicting.isNotEmpty()) {
            val err = DomainError.GitError.MergeConflict(
                conflictCount = partition.conflicting.size,
                conflictPaths = partition.conflicting.toList(),
            )
            logTerminalFailure(step = "merge-conflict-check", err = err)
            return err.left()
        }
        if (partition.nonOverlapping.isEmpty()) {
            return MergeResult(hasConflicts = false, conflicts = emptyList(), changedFiles = emptyList()).right()
        }

        mergeAndCommit(
            config = config,
            hostConfig = hostConfig,
            newBaseSha = remoteSha,
            remotePaths = partition.nonOverlapping,
            message = buildCommitMessage(config, isMerge = true),
        ).getOrElse { err ->
            logTerminalFailure(step = "merge-commit", err = err)
            return err.left()
        }

        logAutoMerged(partition.nonOverlapping)
        return MergeResult(
            hasConflicts = false,
            conflicts = emptyList(),
            changedFiles = partition.nonOverlapping.toList(),
        ).right()
    }

    /**
     * Task 3.3.1b/3.3.1c: rebuilds a fresh commit layering the current local dirty blobs (always
     * re-derived from [PlatformFileSystem.getDirtySnapshot] at call time, matching this file's
     * "always re-derive, never resume partial state" principle — see [GitLabPushContext]'s doc)
     * plus newly-fetched remote content for each of [remotePaths], on top of [newBaseSha] as the
     * new base tree. Remote content is written via [PlatformFileSystem.applyRemoteContent] — a
     * merge write, not a local edit, so it must not be marked dirty. Overwrites [pendingCommit]
     * with the resulting fresh [PendingCommit.Staged] value (the marker never holds two staged
     * commits — the new one supersedes whatever [commit] staged earlier for the old base).
     */
    private suspend fun mergeAndCommit(
        config: GitConfig,
        hostConfig: GitHostConfig,
        newBaseSha: String,
        remotePaths: Set<String>,
        message: String,
    ): Either<DomainError.GitError, Unit> {
        val dirty = fileSystem.getDirtySnapshot()
        val blobShas = mutableMapOf<String, String>()
        val deletedPaths = mutableListOf<String>()

        for ((path, entry) in dirty) {
            when (entry.op) {
                DirtyOp.DELETE -> deletedPaths += path
                DirtyOp.WRITE -> {
                    // BLOCKER fix: read raw bytes uniformly (plain-text AND paranoid-mode content)
                    // via getContentBytes — readFile() alone never sees bytesCache-only entries.
                    val bytes = fileSystem.getContentBytes("${config.repoRoot}/$path")
                        ?: return DomainError.GitError.CommitFailed(
                            "No cached content for dirty path: $path"
                        ).left()
                    val sizeBytes = bytes.size.toLong()
                    if (sizeBytes > MAX_BLOB_BYTES) {
                        return DomainError.GitError.FileTooLarge(path, sizeBytes, MAX_BLOB_BYTES).left()
                    }
                    blobShas[path] = createBlob(hostConfig, bytes).getOrElse { return it.left() }
                }
            }
        }

        for (path in remotePaths) {
            // Remote-fetched content is always text (never encrypted) — applyRemoteContent's own
            // doc comment confirms this write path needs no parallel bytesCache treatment.
            val content = fetchRemoteFileContent(hostConfig, path, newBaseSha).getOrElse { return it.left() }
            fileSystem.applyRemoteContent("${config.repoRoot}/$path", content)
            blobShas[path] = createBlob(hostConfig, content.encodeToByteArray()).getOrElse { return it.left() }
        }

        val treeSha = buildTree(hostConfig, newBaseSha, blobShas, deletedPaths).getOrElse { return it.left() }
        val commitSha = createCommitObject(hostConfig, treeSha, newBaseSha, message).getOrElse { return it.left() }

        fileSystem.setPendingCommit(commitSha, treeSha)
        return Unit.right()
    }

    // =================================== GitLab =========================================
    //
    // GitLab's write-back model is fundamentally different from GitHub's: there is no server-side
    // "create a commit object without moving the branch" primitive (Pattern Decision "Commit/push
    // split (GitLab)", plan.md line 66) — the single `POST .../repository/commits` call both
    // creates the commit *and* advances the branch, atomically. So `commit()` (Task 3.2.1d) is a
    // genuine local-only no-op — no network call, no `PlatformFileSystem.setPendingCommit` staging
    // at all, `pendingCommit` stays `PendingCommit.None` for GitLab forever. `push()` (via
    // [GitLabPushContext]) re-derives the full `GitLabCommitRequest` from the dirty set as it
    // stands *at push time*, matching this plan's "always re-derive, never resume partial state"
    // principle — this deliberately avoids caching a request built at an earlier `commit()` call,
    // which could go stale (miss further edits, or push already-abandoned deletes) by the time
    // `push()` actually runs.

    /** Carries the context [pushViaGitLab] needs to rebuild its commits-API request from scratch. */
    data class GitLabPushContext(val config: GitConfig, val baseSha: String, val message: String)

    /** Task 3.2.1a: "create" (no `last_commit_id`) for a path absent from the base tree snapshot. */
    private fun resolveActionType(existedAtBase: Boolean): String = if (existedAtBase) "update" else "create"

    /**
     * Task 3.2.1b: maps each dirty entry into a [GitLabCommitAction]. [existingPaths] is the set
     * of repo-relative paths known to exist in the base tree — it drives both
     * [resolveActionType] (create vs. update) and whether `last_commit_id` is set at all (a
     * brand-new path has nothing to be stale against). [readContent] mirrors [commitGitHub]'s
     * `PlatformFileSystem.getContentBytes` call for WRITE entries — raw bytes, so paranoid-mode
     * (encrypted) content round-trips correctly instead of being silently dropped by a lossy
     * `readFile(): String?` call; DELETE entries need no content.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun buildGitLabActions(
        dirty: Map<String, DirtyEntry>,
        baseSha: String,
        existingPaths: Set<String>,
        readContent: (String) -> ByteArray?,
    ): Either<DomainError.GitError, List<GitLabCommitAction>> {
        val actions = mutableListOf<GitLabCommitAction>()
        for ((path, entry) in dirty) {
            when (entry.op) {
                DirtyOp.DELETE -> actions += GitLabCommitAction(
                    action = "delete",
                    filePath = path,
                    lastCommitId = baseSha,
                )
                DirtyOp.WRITE -> {
                    val bytes = readContent(path)
                        ?: return DomainError.GitError.CommitFailed(
                            "No cached content for dirty path: $path"
                        ).left()
                    val sizeBytes = bytes.size.toLong()
                    if (sizeBytes > MAX_BLOB_BYTES) {
                        return DomainError.GitError.FileTooLarge(path, sizeBytes, MAX_BLOB_BYTES).left()
                    }
                    val existedAtBase = path in existingPaths
                    actions += GitLabCommitAction(
                        action = resolveActionType(existedAtBase),
                        filePath = path,
                        content = Base64.Default.encode(bytes),
                        lastCommitId = if (existedAtBase) baseSha else null,
                    )
                }
            }
        }
        return actions.right()
    }

    /**
     * Task 3.2.1d: true local-only no-op — GitLab has nothing to stage server-side (see the
     * section note above). The [commit] dispatcher's empty-dirty-set check already covers the
     * "nothing to commit" short-circuit, so by the time this is reached there is at least one
     * dirty entry; validation succeeds unconditionally and no state changes.
     */
    private fun commitGitLab(): Either<DomainError.GitError, Unit> = Unit.right()

    /** Task 3.2.2a: GitLab's compare endpoint — `GET .../repository/compare?from=..&to=..`. */
    private suspend fun fetchGitLabCompareDelta(
        hostConfig: GitHostConfig,
        baseSha: String,
        headBranch: String,
    ): Either<DomainError.GitError, GitLabCompareResponse> =
        githubGet(hostConfig, "repository/compare?from=$baseSha&to=$headBranch")

    /**
     * Task 3.2.2b: mirrors [fetchGitHub]'s structure using GitLab's compare API — unlike GitHub,
     * no separate ref-sha-equality shortcut is needed: an unchanged branch returns empty
     * `commits`/`diffs` directly.
     */
    private suspend fun fetchGitLab(
        hostConfig: GitHostConfig,
        baseSha: String,
    ): Either<DomainError.GitError, FetchResult> {
        val compare = fetchGitLabCompareDelta(hostConfig, baseSha, hostConfig.branch).getOrElse { return it.left() }
        return FetchResult(
            hasRemoteChanges = compare.commits.isNotEmpty(),
            remoteCommitCount = compare.commits.size,
        ).right()
    }

    /**
     * Task 3.2.2c: GitLab's merge orchestrator (uses the shared [partitionConflicts]/
     * [buildConflictFiles] primitives). Wired to the public [merge] dispatcher above alongside
     * GitHub's [mergeGitHub] auto-merge tree-rebuild (mirroring [pushGitHub]/[pushViaGitLab] under
     * [push]'s dispatcher). `merge()` is called independently of `fetch()` per [GitRepository]'s
     * existing contract (no `FetchResult` is threaded through), so this re-derives the
     * remote-changed-path set via its own compare call.
     */
    suspend fun mergeGitLab(
        hostConfig: GitHostConfig,
        baseSha: String,
    ): Either<DomainError.GitError, MergeResult> {
        val compare = fetchGitLabCompareDelta(hostConfig, baseSha, hostConfig.branch).getOrElse { err ->
            logTerminalFailure(step = "gitlab-merge-compare", err = err)
            return err.left()
        }
        val localPaths = fileSystem.getDirtySnapshot().keys
        val remotePaths = compare.diffs.map { it.newPath }.toSet()
        val partition = partitionConflicts(localPaths, remotePaths)

        if (partition.conflicting.isNotEmpty()) {
            val err = DomainError.GitError.MergeConflict(
                conflictCount = partition.conflicting.size,
                conflictPaths = partition.conflicting.toList(),
            )
            logTerminalFailure(step = "gitlab-merge-conflict-check", err = err)
            return err.left()
        }

        // No tree rebuild needed (unlike GitHub): GitLab's `start_sha`-based commit creation
        // preserves untouched files, so a remote-only-changed path needs no `action` entry at all.
        if (partition.nonOverlapping.isNotEmpty()) {
            logAutoMerged(partition.nonOverlapping)
        }
        return MergeResult(
            hasConflicts = false,
            conflicts = emptyList(),
            changedFiles = partition.nonOverlapping.toList(),
        ).right()
    }

    /** Task 3.3.1a (pulled forward): partitions remote-changed paths against the local dirty set. */
    private data class ConflictPartition(val conflicting: Set<String>, val nonOverlapping: Set<String>)

    private fun partitionConflicts(localPaths: Set<String>, remotePaths: Set<String>): ConflictPartition {
        val conflicting = localPaths intersect remotePaths
        return ConflictPartition(conflicting = conflicting, nonOverlapping = remotePaths - conflicting)
    }

    /**
     * Task 3.3.2a (pulled forward): maps each conflicting path to a [ConflictFile] with empty
     * hunks (Pattern Decision "Conflict representation" — no code path reads `hunks` for this
     * per-file, accept/reject conflict UX). Public — not yet called from within this class (the
     * `MergeConflict` this file returns only carries `conflictPaths: List<String>`); Epic 4.1's
     * `WasmGitRepository` is expected to call this when translating a `Left(MergeConflict)` into
     * `GitRepository.merge()`'s `MergeResult(hasConflicts = true, conflicts = ...)` contract.
     */
    fun buildConflictFiles(conflictingPaths: Set<String>): List<ConflictFile> =
        conflictingPaths.map { path -> ConflictFile(filePath = path, wikiRelativePath = path, hunks = emptyList()) }

    /**
     * BLOCKER fix (web-git-writeback architecture review): fetches the set of repo-relative
     * paths that actually exist in the remote tree at [ref], via GitLab's Repository Tree API
     * (`GET .../repository/tree?ref=..&recursive=true`). [pushViaGitLab] previously used
     * `dirty.keys` as a stand-in for "paths existing at base," which is trivially true for every
     * dirty entry (it's derived from the same map) — every WRITE was misclassified `"update"`
     * with a `last_commit_id` set, even for a path that never existed on the remote. GitLab
     * rejects `"update"` against a nonexistent path with a `400`, which
     * [classifyGitLabPushConflict]'s conservative "any unexplained 400 is a conflict" rule then
     * misreports as [DomainError.GitError.MergeConflict] for a brand-new page.
     *
     * Known limitation: does not paginate past GitLab's per_page cap — acceptable for typical
     * wiki-sized repos, revisit if a repo exceeds this.
     */
    private suspend fun fetchGitLabTreePaths(
        hostConfig: GitHostConfig,
        ref: String,
    ): Either<DomainError.GitError, Set<String>> =
        githubGet<List<GitLabTreeEntry>>(
            hostConfig,
            "repository/tree?ref=$ref&recursive=true&per_page=100",
        ).map { entries -> entries.map { it.path }.toSet() }

    /**
     * Task 3.2.1c: the GitLab write-critical-section — builds the commits-API request fresh from
     * the current [dirty] set (per [GitLabPushContext]'s "always re-derive" rationale above),
     * POSTs it, and clears the dirty set on success. Wrapped in the same [GitWriteLock.withLock]
     * as [pushGitHub] (same lock name, derived from [GitHostConfig.apiBase]) by [push]'s
     * dispatcher above.
     *
     * [existingPaths] is derived from a real base-tree membership check ([fetchGitLabTreePaths])
     * rather than `dirty.keys` — see that function's doc comment for why the latter was a
     * BLOCKER-severity bug (every dirty path was misclassified `"update"`, including brand-new
     * pages). If the tree fetch itself fails, that error propagates directly rather than falling
     * back to the broken `dirty.keys` behavior.
     */
    private suspend fun pushViaGitLab(
        hostConfig: GitHostConfig,
        context: GitLabPushContext,
        dirty: Map<String, DirtyEntry>,
    ): Either<DomainError.GitError, Unit> {
        val existingPaths = fetchGitLabTreePaths(hostConfig, context.baseSha).getOrElse { err ->
            logTerminalFailure(step = "gitlab-fetch-tree", err = err)
            return err.left()
        }
        val actions = buildGitLabActions(dirty, context.baseSha, existingPaths) { path ->
            fileSystem.getContentBytes("${context.config.repoRoot}/$path")
        }.getOrElse { err ->
            logTerminalFailure(step = "gitlab-build-actions", err = err)
            return err.left()
        }

        val request = GitLabCommitRequest(
            branch = hostConfig.branch,
            commitMessage = context.message,
            startSha = context.baseSha,
            actions = actions,
        )

        return try {
            val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
            val response = httpClient.post("${hostConfig.apiBase}/repository/commits") {
                header(headerName, headerValue)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            when (response.status.value) {
                in 200..299 -> {
                    val commitResponse = response.body<GitLabCommitResponse>()
                    fileSystem.clearDirtySet(newBaseSha = commitResponse.id)
                    logPushSuccess(fileCount = dirty.size, commitSha = commitResponse.id)
                    Unit.right()
                }
                400 -> {
                    val err = classifyGitLabPushConflict(response, request)
                    logTerminalFailure(step = "gitlab-commit", err = err)
                    err.left()
                }
                else -> {
                    val err = mapHttpFailure(response.status.value, response.headers) {
                        DomainError.GitError.PushFailed(
                            "POST repository/commits failed: HTTP ${response.status.value}"
                        )
                    }
                    logTerminalFailure(step = "gitlab-commit", err = err)
                    err.left()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = DomainError.GitError.NetworkFailure(e.message ?: "POST repository/commits failed")
            logTerminalFailure(step = "gitlab-commit", err = err)
            err.left()
        }
    }

    /**
     * Task 3.2.3a: conservative `400`-response classification. GitLab returns `400` (not `409`)
     * for both a stale `last_commit_id` race and other request-shape validation failures — since
     * a bare status code can't tell them apart, every `400` not otherwise handled is treated as a
     * conflict (fail toward prompting the user, never toward silently dropping the write). The
     * path is extracted from the `{"message": string}` body when it names one of the touched
     * paths; otherwise every touched path is reported as conflicting.
     */
    private suspend fun classifyGitLabPushConflict(
        response: HttpResponse,
        request: GitLabCommitRequest,
    ): DomainError.GitError.MergeConflict {
        val touchedPaths = request.actions.map { it.filePath }
        val message = try {
            response.body<GitLabCommitErrorResponse>().message
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val namedPath = message?.let { m -> touchedPaths.firstOrNull { m.contains(it) } }
        return if (namedPath != null) {
            DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf(namedPath))
        } else {
            DomainError.GitError.MergeConflict(conflictCount = touchedPaths.size, conflictPaths = touchedPaths)
        }
    }

    // ============================ Shared HTTP request helpers ============================

    private suspend inline fun <reified Req, reified Res> githubPost(
        hostConfig: GitHostConfig,
        path: String,
        body: Req,
    ): Either<DomainError.GitError, Res> = try {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        val response = httpClient.post("${hostConfig.apiBase}/$path") {
            header(headerName, headerValue)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        when {
            response.status.value !in 200..299 -> mapHttpFailure(response.status.value, response.headers) {
                DomainError.GitError.CommitFailed("POST $path failed: HTTP ${response.status.value}")
            }.left()
            else -> response.body<Res>().right()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainError.GitError.NetworkFailure(e.message ?: "POST $path failed").left()
    }

    private suspend inline fun <reified Res> githubGet(
        hostConfig: GitHostConfig,
        path: String,
        notFoundMessage: String? = null,
    ): Either<DomainError.GitError, Res> = try {
        val (headerName, headerValue) = GitHostAdapter.authHeader(hostConfig.type, hostConfig.token)
        val response = httpClient.get("${hostConfig.apiBase}/$path") {
            header(headerName, headerValue)
        }
        when {
            response.status.value == 404 && notFoundMessage != null ->
                DomainError.GitError.FetchFailed(notFoundMessage).left()
            response.status.value !in 200..299 -> mapHttpFailure(response.status.value, response.headers) {
                DomainError.GitError.FetchFailed("GET $path failed: HTTP ${response.status.value}")
            }.left()
            else -> response.body<Res>().right()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainError.GitError.NetworkFailure(e.message ?: "GET $path failed").left()
    }

    /**
     * Task 3.4.3a: shared classifier used by every non-2xx branch across this file (Epics 3.1/3.2's
     * per-call-site handlers) — replaces the previously scattered, inconsistent `429`-only checks.
     * `401`, or `403` **without** a `Retry-After` header, indicates a rejected/expired credential —
     * this is checked first so it is never misread as the secondary-rate-limit case below.
     * `429`, or `403` **with** `Retry-After`, is the retryable rate-limit case: by the time this is
     * reached, [installRetryPolicy]'s [HttpRequestRetry] plugin has already exhausted its retry
     * budget (Story 3.4.1), so this is genuine exhaustion, not a transient hiccup the plugin would
     * otherwise have absorbed. `409`/`422` (ref-update) and GitLab's `400` (commits POST) are
     * intentionally NOT handled here — those call sites branch on them *before* falling through to
     * this classifier, since they stay mapped to [DomainError.GitError.MergeConflict] unchanged.
     * Anything else falls back to [genericError], the step-appropriate error the caller supplies.
     */
    private fun mapHttpFailure(
        status: Int,
        headers: Headers,
        genericError: () -> DomainError.GitError,
    ): DomainError.GitError {
        val retryAfter = headers[RETRY_AFTER_HEADER]?.toIntOrNull()
        return when {
            status == 401 || (status == 403 && retryAfter == null) ->
                DomainError.GitError.CredentialExpired(CREDENTIAL_EXPIRED_MESSAGE)
            status == 429 || (status == 403 && retryAfter != null) ->
                DomainError.GitError.RateLimited(retryAfter)
            else -> genericError()
        }
    }

    companion object {
        /** GitHub's hard cap on a single blob create call is 100 MiB; stay well under it. */
        const val MAX_BLOB_BYTES = 75_000_000L

        /** Task 3.4.1c: max concurrent `createBlob` POSTs in flight per [commit] call. */
        private const val BLOB_CREATE_CONCURRENCY = 3

        private const val TREE_ENTRY_MODE = "100644"
        private const val TREE_ENTRY_TYPE = "blob"
        private const val RETRY_AFTER_HEADER = "Retry-After"

        /**
         * Task 3.4.3a: host-neutral copy for [mapHttpFailure]'s `401`/`403`-without-`Retry-After`
         * branch — shared by both GitHub and GitLab call sites, so it deliberately never says
         * "GitHub" (per Story 3.4.3's acceptance criteria).
         */
        private const val CREDENTIAL_EXPIRED_MESSAGE = "Your git host rejected the configured token"

        // Placeholder identity for web-originated commits — there is no local git config on web
        // to source a real author identity from (cf. JvmGitRepository's `git.commit()`, which
        // relies on the system git config). Revisit if per-user attribution is ever required.
        private const val COMMIT_AUTHOR_NAME = "SteleKit Web"
        private const val COMMIT_AUTHOR_EMAIL = "stelekit-web@users.noreply.github.com"

        /**
         * The literal placeholder [DomainError.GitError.MergeConflict.conflictPaths] value used
         * when a ref-PATCH race (409/422) is detected — the exact conflicting path(s) are not
         * knowable from this response alone (that requires the fetch/compare step Epic 3.3 wires
         * up); see Story 3.1.2's acceptance criteria for this literal string.
         */
        const val UNKNOWN_CONFLICT_PATH = "<unknown — remote advanced during push>"

        /**
         * Task 3.4.1a: the shared retry/rate-limit policy — extracted as a reusable
         * [HttpClientConfig] extension (rather than inlined only in [withDefaultClient]) so
         * `IT-3.4.1-A` (wasmJsTest) can apply the identical real plugin configuration to a
         * `MockEngine`-backed client instead of re-declaring the `retryIf` predicate.
         */
        fun HttpClientConfig<*>.installRetryPolicy() {
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 4)
                exponentialDelay()
                retryIf { _, response ->
                    response.status.value == 429 ||
                        (response.status.value == 403 && response.headers[RETRY_AFTER_HEADER] != null)
                }
            }
        }

        /** Builds a [WasmGitWriteService] with a Ktor client configured with Story 3.4.1's retry policy. */
        fun withDefaultClient(fileSystem: PlatformFileSystem): WasmGitWriteService {
            val client = HttpClient {
                install(ContentNegotiation) { json(gitApiJson) }
                installRetryPolicy()
            }
            return WasmGitWriteService(client, fileSystem)
        }
    }
}

/**
 * Task 3.2.3a: the `{"message": string}` error body GitLab's commits API returns on a `400`.
 * Not part of `GitLabCommitModels.kt` (a prior-epic file this epic reads but does not modify) —
 * scoped locally to this file since it is only ever decoded by [WasmGitWriteService].
 */
@Serializable
private data class GitLabCommitErrorResponse(val message: String? = null)
