// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * JVM/Desktop [GitCloneWorkerLauncher]: no foreground-service concept on Desktop (out of scope
 * per requirements) — calls [GitRepository.clone] directly, unchanged in spirit from the
 * pre-Epic-3.1 direct call site (Story 3.1.3, Task 3.1.3c). [graphId] is accepted for interface
 * parity with the Android implementation but unused for dispatch here — Desktop's Step 5 only ever
 * has one clone in flight at a time, so [cancel] cancels whichever one is current regardless of
 * [graphId].
 *
 * Owns its own [CoroutineScope] (`SupervisorJob` + `Dispatchers.Default`) — never
 * `rememberCoroutineScope()` (CLAUDE.md's coroutine-scope-ownership rule) — so [launchClone] runs
 * the actual clone in a child [Deferred] this launcher can [cancel] independently of the caller's
 * own coroutine (Task 4.1.4c): cancelling that child `Job` makes JGit's `ProgressMonitor.isCancelled()`
 * (already wired to `job?.isCancelled` inside `GitRepository.clone`) return `true`, which surfaces
 * as a JGit `CanceledException` → `GitFailureClass.Cancelled` → a rethrown
 * `kotlinx.coroutines.CancellationException` out of `runGitTransportOpWithRetry` — `await()` on the
 * cancelled `Deferred` then throws that same `CancellationException` here, propagating to the
 * caller exactly as [GitCloneWorkerLauncher.cancel]'s kdoc promises, without cancelling the
 * caller's own ambient coroutine.
 */
class JvmGitCloneWorkerLauncher(private val gitRepository: GitRepository) : GitCloneWorkerLauncher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var currentClone: Deferred<Either<DomainError.GitError, Unit>>? = null

    override suspend fun launchClone(
        graphId: String,
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (CloneProgress) -> Unit,
        onStateChange: (GitTransportRetryState) -> Unit,
        graphDisplayName: String?,
    ): Either<DomainError.GitError, Unit> {
        val deferred = scope.async { gitRepository.clone(url, localPath, auth, onProgress, onStateChange) }
        currentClone = deferred
        try {
            return deferred.await()
        } catch (e: CancellationException) {
            // The clone runs in this launcher's own scope, so a cancelled caller would otherwise
            // leave it running detached.
            deferred.cancel()
            throw e
        } finally {
            currentClone = null
        }
    }

    override fun cancel(graphId: String) {
        currentClone?.cancel(CancellationException("Clone cancelled by user"))
    }
}
