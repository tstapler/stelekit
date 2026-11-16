// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig

/**
 * Delegating [GitRepository] that throws [AssertionError] when a mutating call arrives without the repo's gate
 * key held (`RepoOpGate.isHeld`), so a service path that skips single-flight fails the test instead of racing.
 * [keyFor] must produce the same key the service under test resolves for [GitConfig]; the default asks the wrapped
 * repository's `gateKeyFor`, as the service does.
 * [beginOwnedOperation] answers [beginVerdict] when set; otherwise it delegates, and a test stub that scripts no
 * verdict answers `NoLock` (the production default is fail-closed and would pause every fixture).
 */
class GateAssertingGitRepository(
    private val delegate: GitRepository,
    private val gate: RepoOpGate,
    private val beginVerdict: LockVerdict? = null,
    private val keyFor: (GitConfig) -> String = { config ->
        delegate.gateKeyFor(config) ?: RepoOpGate.keyFor("${config.repoRoot.value.trimEnd('/')}/.git") { it }
    },
) : GitRepository by delegate {

    private fun requireGate(method: String, config: GitConfig) {
        val key = keyFor(config)
        if (!gate.isHeld(key)) throw AssertionError("GitRepository.$method called without the repo gate held (key=$key)")
    }

    override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
        requireGate("fetch", config)
        return delegate.fetch(config)
    }

    override suspend fun unshallow(config: GitConfig): Either<DomainError.GitError, Unit> {
        requireGate("unshallow", config)
        return delegate.unshallow(config)
    }

    override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>): Either<DomainError.GitError, Unit> {
        requireGate("stageSubdir", config)
        return delegate.stageSubdir(config, excludePaths)
    }

    override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> {
        requireGate("commit", config)
        return delegate.commit(config, message)
    }

    override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> {
        requireGate("merge", config)
        return delegate.merge(config)
    }

    override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> {
        requireGate("push", config)
        return delegate.push(config)
    }

    override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> {
        requireGate("abortMerge", config)
        return delegate.abortMerge(config)
    }

    override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit> {
        requireGate("checkoutFile", config)
        return delegate.checkoutFile(config, filePath, side)
    }

    override suspend fun markResolved(config: GitConfig, filePath: String): Either<DomainError.GitError, Unit> {
        requireGate("markResolved", config)
        return delegate.markResolved(config, filePath)
    }

    override suspend fun removeStaleLockFile(config: GitConfig): Either<DomainError.GitError, Unit> {
        requireGate("removeStaleLockFile", config)
        return delegate.removeStaleLockFile(config)
    }

    override suspend fun writeWorkingBytes(config: GitConfig, path: String, bytes: ByteArray): Boolean {
        requireGate("writeWorkingBytes", config)
        return delegate.writeWorkingBytes(config, path, bytes)
    }

    override suspend fun beginOwnedOperation(config: GitConfig, info: OwnedOperationInfo): Either<DomainError.GitError, LockVerdict> {
        requireGate("beginOwnedOperation", config)
        return beginVerdict?.let { Either.Right(it) } ?: delegate.beginOwnedOperation(config, info)
    }

    override suspend fun forceRemoveLock(config: GitConfig): Either<DomainError.GitError, Unit> {
        requireGate("forceRemoveLock", config)
        return delegate.forceRemoveLock(config)
    }
}
