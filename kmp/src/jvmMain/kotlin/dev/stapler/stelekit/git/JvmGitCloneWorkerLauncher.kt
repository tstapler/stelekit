// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/**
 * JVM/Desktop [GitCloneWorkerLauncher]: no foreground-service concept on Desktop (out of scope
 * per requirements) — calls [GitRepository.clone] directly, unchanged in spirit from the
 * pre-Epic-3.1 direct call site (Story 3.1.3, Task 3.1.3c). [graphId] is accepted for interface
 * parity with the Android implementation but unused here.
 */
class JvmGitCloneWorkerLauncher(private val gitRepository: GitRepository) : GitCloneWorkerLauncher {
    override suspend fun launchClone(
        graphId: String,
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (String) -> Unit,
    ): Either<DomainError.GitError, Unit> = gitRepository.clone(url, localPath, auth, onProgress)
}
