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
 * [onProgress] stays `(String) -> Unit`, matching [GitRepository.clone]'s current signature —
 * plan.md's `CloneProgress` type (Task 4.1.1a) is a Phase 4 deliverable not yet implemented;
 * widening this signature to it is Phase 4's job.
 */
interface GitCloneWorkerLauncher {
    suspend fun launchClone(
        graphId: String,
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (String) -> Unit,
    ): Either<DomainError.GitError, Unit>
}
