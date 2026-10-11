// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/**
 * Platform-agnostic seam for "run a clone as a survivable operation" (git-sync-resilience Story
 * 3.1.3), keeping [dev.stapler.stelekit.ui.screens.git.GitSetupScreenSaveLogic.performCloneAndSave]'s
 * call site platform-agnostic. Android's implementation ([dev.stapler.stelekit.git.AndroidGitCloneWorkerLauncher])
 * enqueues [GitCloneWorker] and survives backgrounding via its `dataSync` foreground promotion;
 * the JVM/Desktop implementation ([dev.stapler.stelekit.git.JvmGitCloneWorkerLauncher]) calls
 * [GitRepository.clone] directly — Desktop has no foreground-service equivalent (out of scope per
 * requirements).
 *
 * [graphId] is not part of plan.md's originally sketched signature
 * (`launchClone(url, localPath, auth, onProgress)`) — it's added here because the Android
 * implementation must enqueue `GitCloneWorker` with serializable `Data`, and [graphId] is the key
 * `GitCloneWorker` uses to resolve auth secrets back out of `CredentialStore` (see its kdoc),
 * since a suspend `GitAuth` provider lambda can't cross that process-independent boundary.
 *
 * [onProgress] widened to `(CloneProgress) -> Unit` and [onStateChange] added (git-sync-resilience
 * Story 4.1.1/4.1.2) — mirrors [GitRepository.clone]'s own widened signature, now that
 * `CloneProgress`/`GitTransportRetryState` are Phase 4 deliverables.
 */
interface GitCloneWorkerLauncher {
    suspend fun launchClone(
        graphId: String,
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (CloneProgress) -> Unit,
        onStateChange: (GitTransportRetryState) -> Unit = {},
        graphDisplayName: String? = null,
    ): Either<DomainError.GitError, Unit>

    /**
     * Cancels the in-flight clone this launcher is running for [graphId] (Story 4.1.4) — a no-op
     * if none is in flight. The cancellation surfaces back through [launchClone] as a thrown
     * `kotlinx.coroutines.CancellationException` (mirroring `GitRepository.clone`'s own contract
     * once `classifyGitFailure` sees the resulting `CanceledException`/`GitFailureClass.Cancelled`
     * — see `GitOperationSupport.runGitTransportOpWithRetry`'s kdoc), never an `Either.Left`, so
     * every caller handles cancellation uniformly regardless of platform.
     */
    fun cancel(graphId: String)
}
