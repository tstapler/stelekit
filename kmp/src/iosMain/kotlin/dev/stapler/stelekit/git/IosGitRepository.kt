// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig

/**
 * iOS stub implementation of GitRepository.
 * All operations return DomainError.GitError.NotSupported until kgit2 integration is validated.
 * This unblocks Desktop and Android delivery while iOS git ships as a follow-up (Task 3.4).
 */
class IosGitRepository : GitRepository {

    private val notSupported: Either<DomainError.GitError, Nothing>
        get() = DomainError.GitError.NotSupported("iOS").left()

    override suspend fun isGitRepo(path: String): Boolean = false

    override suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit> = notSupported

    override suspend fun clone(
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (String) -> Unit,
    ): Either<DomainError.GitError, Unit> = notSupported

    override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> = notSupported

    override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> = notSupported

    override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = notSupported

    override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> =
        notSupported

    override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> = notSupported

    override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> = notSupported

    override suspend fun log(config: GitConfig, maxCount: Int): Either<DomainError.GitError, List<GitCommit>> =
        notSupported

    override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> = notSupported

    override suspend fun checkoutFile(
        config: GitConfig,
        filePath: String,
        side: MergeSide,
    ): Either<DomainError.GitError, Unit> = notSupported

    override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> =
        notSupported

    override suspend fun hasDetachedHead(config: GitConfig): Boolean = false

    override suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> = notSupported
}
