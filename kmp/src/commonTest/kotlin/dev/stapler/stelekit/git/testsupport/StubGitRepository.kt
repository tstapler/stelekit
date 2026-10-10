// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.testsupport

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.CloneProgress
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.GitAuth
import dev.stapler.stelekit.git.GitCommit
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.GitStatus
import dev.stapler.stelekit.git.GitTransportRetryState
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
    override suspend fun clone(
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (CloneProgress) -> Unit,
        onStateChange: (GitTransportRetryState) -> Unit,
    ): Either<DomainError.GitError, Unit> = error("not implemented in stub")
    override suspend fun testRemote(url: String, auth: GitAuth): Either<DomainError.GitError, Unit> = error("not implemented in stub")
    override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> = error("not implemented in stub")
    override suspend fun unshallow(config: GitConfig): Either<DomainError.GitError, Unit> = error("not implemented in stub")
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

/**
 * A "fail N times, then succeed" counter — the reusable fault-injection primitive behind Story
 * 6.1.1 (git-sync-resilience's foundational fault-injection harness). Each call to [nextOrThrow]
 * throws the next entry of [failures] until they're exhausted, then delegates to [onSuccess] on
 * every call thereafter (so a test can call it more times than [failures] has entries without
 * special-casing the last call).
 *
 * Deliberately ignorant of *which* exceptions it throws: [failures] are plain [Throwable]s the
 * caller constructs. This keeps [FailureSequence] itself usable from `commonTest` — the real
 * JGit/JDK exception shapes a caller wants to inject (`TransportException(cause =
 * SocketException(...))`, `NoRemoteRepositoryException`, etc.) only compile in a JVM-attached
 * source set (`businessTest`/`jvmTest`/`androidUnitTest`), and `commonTest` is also compiled for
 * `wasmJsTest`/`iosTest` (see `MEMORY.md`'s wasmJs-compile-scope note and this repo's CI "Compile
 * wasmJs test sources" step) — pulling a JVM-only type into this class directly would break that.
 */
class FailureSequence<T>(
    private val failures: List<Throwable>,
    private val onSuccess: suspend () -> T,
) {
    /** Total number of times [nextOrThrow] has been called so far, including calls that threw. */
    var invocationCount: Int = 0
        private set

    suspend fun nextOrThrow(): T {
        val attemptIndex = invocationCount
        invocationCount++
        if (attemptIndex < failures.size) throw failures[attemptIndex]
        return onSuccess()
    }
}

/**
 * [StubGitRepository] whose [clone]/[fetch]/[push] each draw from an independently configured
 * [FailureSequence] — Task 6.1.1a's "configurable failure-sequence mode for clone/fetch/push".
 * Any operation left `null` falls back to [StubGitRepository]'s own `error("not implemented in
 * stub")` default. Construct the per-operation [FailureSequence] with real
 * `TransportException`-wrapped exceptions in a JVM-attached test (see
 * `StubGitRepositoryFailureSequenceTest.kt` for the pattern), then drive it through
 * `runGitTransportOpWithRetry`/`GitSyncService`/`GitCloneWorker` as needed — this is what Stories
 * 1.2.2, 3.1.2, 4.1.2, and 6.1.3 depend on.
 */
/**
 * Builds a [StubGitRepository] whose [GitRepository.clone] delegates to [onClone] — the common
 * "override `clone()` only" test-double shape repeated across `GitCloneWorker`/
 * `GitCloneWorkerLauncher` tests (git-sync-resilience Epic 3.1), extracted to cut that boilerplate
 * to one line per test.
 */
fun cloningStub(
    onClone: suspend (url: String, localPath: String, auth: GitAuth, onProgress: (CloneProgress) -> Unit) -> Either<DomainError.GitError, Unit>,
): GitRepository = object : StubGitRepository() {
    override suspend fun clone(
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (CloneProgress) -> Unit,
        onStateChange: (GitTransportRetryState) -> Unit,
    ) = onClone(url, localPath, auth, onProgress)
}

open class FailureSequenceGitRepository(
    private val cloneSequence: FailureSequence<Either<DomainError.GitError, Unit>>? = null,
    private val fetchSequence: FailureSequence<Either<DomainError.GitError, FetchResult>>? = null,
    private val pushSequence: FailureSequence<Either<DomainError.GitError, Unit>>? = null,
) : StubGitRepository() {
    override suspend fun clone(
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (CloneProgress) -> Unit,
        onStateChange: (GitTransportRetryState) -> Unit,
    ): Either<DomainError.GitError, Unit> =
        cloneSequence?.nextOrThrow() ?: super.clone(url, localPath, auth, onProgress, onStateChange)

    override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> =
        fetchSequence?.nextOrThrow() ?: super.fetch(config)

    override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> =
        pushSequence?.nextOrThrow() ?: super.push(config)
}
