// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.GitConfig

/**
 * Platform-agnostic git operations interface.
 * Platform implementations: JvmGitRepository (Desktop), AndroidGitRepository (Android),
 * IosGitRepository (iOS stub).
 */
@Suppress("MissingDirectRepositoryWrite") // git network/filesystem ops — not DB writes, not actor-routed
interface GitRepository {
    suspend fun isGitRepo(path: String): Boolean
    suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit>
    suspend fun clone(
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (String) -> Unit,
    ): Either<DomainError.GitError, Unit>
    suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult>
    suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus>
    suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit>
    suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String>
    suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult>
    suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit>
    suspend fun log(config: GitConfig, maxCount: Int = 50): Either<DomainError.GitError, List<GitCommit>>
    suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit>
    suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit>
    suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit>
    suspend fun hasDetachedHead(config: GitConfig): Boolean
    suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit>
}

data class FetchResult(val hasRemoteChanges: Boolean, val remoteCommitCount: Int)

data class GitStatus(
    val hasLocalChanges: Boolean,
    val untrackedFiles: List<String>,
    val modifiedFiles: List<String>,
)

data class MergeResult(
    val hasConflicts: Boolean,
    val conflicts: List<ConflictFile>,
    val changedFiles: List<String>,
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
