// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.lfs.LfsViolation
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.security.CredentialAccess

/**
 * Platform-agnostic git operations interface.
 * Platform implementations: JvmGitRepository (Desktop), AndroidGitRepository (Android),
 * IosGitRepository (iOS stub).
 */
@Suppress("MissingDirectRepositoryWrite") // git network/filesystem ops — not DB writes, not actor-routed
interface GitRepository {
    suspend fun isGitRepo(path: String): Boolean
    suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit>

    /**
     * [onProgress] widened from `(String) -> Unit` to `(CloneProgress) -> Unit` (git-sync-resilience
     * Story 4.1.1) — forwards JGit's `ProgressMonitor.update(completed)`, previously a no-op, so a
     * caller sees real completed/totalWork counts, not just a phase title.
     *
     * [onStateChange] (Story 4.1.2) surfaces [GitTransportRetryState] transitions (attempt/retry/
     * terminal outcome) from the internal `runGitTransportOpWithRetry` loop — the single source of
     * truth Step 5 and the Android foreground notification both render from. Defaults to a no-op so
     * every pre-Phase-4 call site keeps compiling unchanged. Added only to [clone] — [fetch]/[push]
     * don't surface it in this epic (Step 5's UI is clone-only per `design/ux.md`); see
     * `GitOperationSupport.runGitTransportOpWithRetry`'s kdoc for the full rationale.
     */
    suspend fun clone(
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (CloneProgress) -> Unit,
        onStateChange: (GitTransportRetryState) -> Unit = {},
    ): Either<DomainError.GitError, Unit>

    /**
     * Checks that [url] is reachable and [auth] is valid, without requiring a local clone
     * (`git ls-remote` semantics). Used by the "clone a new repo" setup flow's "Test connection"
     * step, where [fetch] can't be used yet — it opens an existing local repo at `config.repoRoot`,
     * which doesn't exist until the clone (on Save) actually runs.
     */
    suspend fun testRemote(url: String, auth: GitAuth): Either<DomainError.GitError, Unit>
    suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult>

    /** Asks the remote at [url] for its default branch (`ls-remote`); never throws. */
    suspend fun detectDefaultBranch(url: String, auth: GitAuth): DefaultBranchDetection =
        DefaultBranchDetection.Unreachable("not supported on this platform")

    /**
     * Widens a shallow clone to full history (`FetchCommand.setUnshallow(true)`) — the deepen
     * capability backing [dev.stapler.stelekit.git.model.CloneDepthState.Shallow] →
     * [dev.stapler.stelekit.git.model.CloneDepthState.FullHistory] (git-sync-resilience Story
     * 2.1.4). Guards against widening onto a diverged remote (returns
     * [DomainError.GitError.FetchFailed] instead of attempting a widen JGit might mishandle) but
     * does **not** itself persist the resulting [dev.stapler.stelekit.git.model.CloneDepthState] —
     * callers that hold a [GitConfigRepository] (see [dev.stapler.stelekit.git.GitSyncService.deepen])
     * are responsible for saving the updated config on success. Backend capability only in this
     * plan — no settings-screen UI entry point calls it yet.
     */
    suspend fun unshallow(config: GitConfig): Either<DomainError.GitError, Unit>
    suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus>
    /**
     * Stages the wiki subdirectory. [excludePaths] (repository-relative) are staged by the pattern add and then
     * un-staged again, so a caller can hold individual files back (Git LFS guard) while the rest commits.
     */
    suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String> = emptySet()): Either<DomainError.GitError, Unit>
    suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String>
    suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult>
    suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit>
    suspend fun log(config: GitConfig, maxCount: Int = 50): Either<DomainError.GitError, List<GitCommit>>
    suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit>
    suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit>
    suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit>
    suspend fun hasDetachedHead(config: GitConfig): Boolean
    suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit>

    /**
     * Repository-level LFS backstop: every staged blob at an LFS-tracked path that is not a pointer. Implementations
     * that report anything also un-stage those paths, so the next [commit] cannot include them. Default empty
     * (platforms without a staging index of their own, such as web, guard in their own write path).
     */
    suspend fun stagedLfsViolations(config: GitConfig): List<LfsViolation> = emptyList()

    /**
     * True only after a converting clean filter (not the pass-through one) was verified for this repository.
     * Always false in Phase 1: nothing converts raw files to pointers yet.
     */
    val cleanFilterActive: Boolean get() = false

    /**
     * Raw binaries at LFS-tracked paths found in the commits the last [push] sent (detection only: they are
     * already remote). Empty when nothing was found, nothing was pushed, or the platform cannot audit.
     */
    val lastPushLfsViolations: List<LfsViolation> get() = emptyList()

    /**
     * True when this platform's own write path is the sole LFS guard (web: it never stages through a local index),
     * so the service-level guard must not second-guess it.
     */
    val guardsLfsInWritePath: Boolean get() = false

    /**
     * Whether the index currently differs from HEAD. Consulted only after the LFS guard held files back, to avoid
     * an empty commit when nothing else was staged. Default true: "assume there is something to commit".
     */
    suspend fun hasStagedChanges(config: GitConfig): Boolean = true

    /** Read-only ref report for the diagnostics export (see `describeGitRefs`); never throws. */
    suspend fun describeRefs(config: GitConfig): String = "(not supported on this platform)"

    /**
     * Replaces the active credential store. Called from App.kt to swap in a
     * [VaultCredentialStore] on vault unlock, or back to [CredentialStore] on lock.
     * Default no-op for platform stubs (iOS).
     */
    fun setCredentialAccess(access: CredentialAccess) {}
}

data class FetchResult(val hasRemoteChanges: Boolean, val remoteCommitCount: Int)

/**
 * A single JGit `ProgressMonitor` callback, widened from a bare phase-title `String`
 * (git-sync-resilience Story 4.1.1): [phase] is the current `beginTask` title (e.g. "Receiving
 * objects"), [completed]/[totalWork] are JGit's own per-phase units — `totalWork == 0` means
 * indeterminate (either no `beginTask` has fired yet, or JGit itself doesn't know the total for
 * this phase). Never a whole-operation percentage — JGit reports progress per phase, not overall.
 */
data class CloneProgress(val phase: String, val completed: Int, val totalWork: Int)

/**
 * Pure, platform-independent state machine behind JGit's `ProgressMonitor` callbacks
 * (`beginTask`/`update`) — shared by [dev.stapler.stelekit.git.AndroidGitRepository] and
 * [dev.stapler.stelekit.git.JvmGitRepository]'s `clone()` (git-sync-resilience Task 4.1.1b/c) so
 * the completed/totalWork-tracking logic isn't duplicated byte-for-byte on both platforms, and so
 * it's unit-testable without a real JGit clone (see `CloneProgressTrackerTest`). [current] starts
 * at `CloneProgress("", 0, 0)` — indeterminate — matching a callback fired before any `beginTask()`
 * call.
 */
class CloneProgressTracker {
    var current: CloneProgress = CloneProgress("", 0, 0)
        private set

    fun onBeginTask(title: String, totalWork: Int): CloneProgress {
        current = CloneProgress(title, 0, totalWork)
        return current
    }

    fun onUpdate(completed: Int): CloneProgress {
        current = current.copy(completed = completed)
        return current
    }
}

data class GitStatus(
    val hasLocalChanges: Boolean,
    val untrackedFiles: List<String>,
    val modifiedFiles: List<String>,
)

data class MergeResult(
    val hasConflicts: Boolean,
    val conflicts: List<ConflictFile>,
    val changedFiles: List<String>,
    /** Remote commits brought in, from HEAD before/after the merge; null when the backend can't say. */
    val mergedCommitCount: Int? = null,
)

data class GitCommit(
    val sha: String,
    val shortMessage: String,
    val authorName: String,
    val timestamp: Long,
)

enum class MergeSide { LOCAL, REMOTE }

sealed class GitAuth {
    data class SshKey(
        val keyPath: String,
        val passphraseProvider: suspend () -> String?,
    ) : GitAuth()

    data class HttpsToken(
        val username: String,
        val tokenProvider: suspend () -> String?,
    ) : GitAuth()

    data object None : GitAuth()
}
