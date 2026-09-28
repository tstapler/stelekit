// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.testsupport

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitCommit
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitStatus
import dev.stapler.stelekit.git.MergeResult
import dev.stapler.stelekit.git.MergeSide
import dev.stapler.stelekit.git.model.GitConfig

/**
 * [GitRepository] stub that throws for any method not overridden by a test — the common shape
 * (`object : StubGitRepository() { override fun ... }`) repeated, byte-for-byte, across
 * `jvmTest`, `androidUnitTest`, and `businessTest` before this extraction. Lives in `commonTest`
 * because that's the only source set all three reach: `businessTest` and `androidUnitTest` both
 * `dependsOn(commonTest)`, and `jvmTest.dependsOn(businessTest)` (see `kmp/build.gradle.kts`).
 */
open class StubGitRepository : GitRepository {
    override suspend fun isGitRepo(path: String): Boolean = error("not implemented in stub")
    override suspend fun init(repoRoot: String): Either<DomainError.GitError, Unit> = error("not implemented in stub")
    override suspend fun clone(url: String, localPath: String, auth: GitAuth, onProgress: (String) -> Unit): Either<DomainError.GitError, Unit> = error("not implemented in stub")
    override suspend fun testRemote(url: String, auth: GitAuth): Either<DomainError.GitError, Unit> = error("not implemented in stub")
    override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> = error("not implemented in stub")
    override suspend fun status(config: GitConfig): Either<DomainError.GitError, GitStatus> = error("not implemented in stub")
    override suspend fun stageSubdir(config: GitConfig): Either<DomainError.GitError, Unit> = error("not implemented in stub")
    override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> = error("not implemented in stub")
    override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> = error("not implemented in stub")
    override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> = error("not implemented in stub")
    override suspend fun log(config: GitConfig, maxCount: Int): Either<DomainError.GitError, List<GitCommit>> = error("not implemented in stub")
    override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> = error("not implemented in stub")
    override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit> = error("not implemented in stub")
    override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> = error("not implemented in stub")
    override suspend fun hasDetachedHead(config: GitConfig): Boolean = false
    override suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> = Unit.right()
}
