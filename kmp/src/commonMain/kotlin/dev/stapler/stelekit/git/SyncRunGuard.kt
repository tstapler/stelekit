// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A sync step that dies mid-pipeline must not leave a running state behind: the status sheet disables
 * Sync now while one is shown.
 */
internal class SyncRunGuard(private val state: MutableStateFlow<SyncState>, private val logger: Logger) {

    /** Cancellation is not a failure: a still-running state goes back to Idle. */
    fun cancelled() = state.update { if (it.isRunning()) SyncState.Idle else it }

    /** An exception from the git backend (not an [Either.Left]) becomes a visible [SyncState.Error]. */
    fun crashed(step: String, e: Exception): Either<DomainError.GitError, Nothing> {
        val err = DomainError.GitError.FetchFailed("$step crashed: ${e::class.simpleName}: ${e.message}")
        logger.error("$step threw unexpectedly: ${e::class.simpleName}: ${e.message}")
        state.value = SyncState.Error(err)
        return err.left()
    }

    /** Publishes the state for a failed git step: rate limit (with auto-retry), expired credentials, or the plain error. */
    fun publishFailure(
        rateLimitRetry: RateLimitRetry,
        graphId: String,
        err: DomainError.GitError,
        authFailureMeansExpired: Boolean,
        retry: suspend (String) -> Unit,
    ) {
        state.value = when {
            err is DomainError.GitError.RateLimited -> {
                rateLimitRetry.schedule(graphId, err.retryAfterSeconds, retry)
                SyncState.RateLimited(err.retryAfterSeconds)
            }
            err is DomainError.GitError.CredentialExpired ||
                (authFailureMeansExpired && err is DomainError.GitError.AuthFailed) -> SyncState.CredentialExpired(graphId)
            else -> SyncState.Error(err)
        }
    }

    private fun SyncState.isRunning() =
        this is SyncState.Fetching || this is SyncState.Merging || this is SyncState.Pushing || this is SyncState.Committing
}
