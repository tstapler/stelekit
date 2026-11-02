// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One scheduled tick: gathers the [ScheduledSyncPolicy] inputs, asks the policy, then runs a full sync, a
 * fetch, or nothing. Lives beside [GitSyncService] (which owns the sync pipeline and hands it in as
 * [fullSync] and [fetch]) so that scheduling decisions never grow `sync()` itself (ADR-004).
 *
 * The repository is read only after every cheap gate has passed, so a skipped tick makes no git call. A
 * skipped tick that still fetches ([ScheduledSkipReason.fetchesAnyway]) reports a fetch error as a failure
 * rather than hiding it, and a successful fetch as a success (only the merge was withheld). [fullSync] must use [SyncTrigger.Automatic], which can never lift the mass-change
 * guard. Logs one `ScheduledSync decision=...` line per tick.
 */
class ScheduledSyncRunner(
    private val gitRepository: GitRepository,
    private val configRepository: GitConfigRepository,
    private val editLock: EditLock,
    private val busyCounter: GitSyncBusyCounter,
    private val firstSyncConfirmation: FirstSyncConfirmation?,
    private val credentialAccessProvider: (() -> CredentialAccess)?,
    private val isOnline: () -> Boolean,
    private val clock: () -> Long,
    private val isAppOwnedPath: (RepoRoot) -> Boolean,
    private val syncState: MutableStateFlow<SyncState>,
    private val fullSync: suspend (String) -> Either<DomainError.GitError, SyncState.Success>,
    private val fetch: suspend (String) -> Either<DomainError.GitError, FetchResult>,
) {
    private val logger = Logger("ScheduledSyncRunner")

    @kotlin.concurrent.Volatile private var consecutiveFailures = 0
    @kotlin.concurrent.Volatile private var backoffUntilMs = 0L

    suspend fun run(graphId: String): TickOutcome {
        val config = configRepository.getConfig(graphId).getOrNull() ?: return fetch(graphId).toTickOutcome()
        val cheap = ScheduledSyncPolicy.decide(inputs(graphId, config, repositorySafe = true))
        val needsRepoCheck = cheap == ScheduledSyncDecision.Run || cheap == ScheduledSyncDecision.Skip(ScheduledSkipReason.FirstSyncUnconfirmed)
        val health = if (needsRepoCheck) gitRepository.repositoryHealth(config) else RepositoryHealth.Clean
        val decision = if (needsRepoCheck) ScheduledSyncPolicy.decide(inputs(graphId, config, health.safe)) else cheap
        logger.info("ScheduledSync graph=$graphId decision=$decision")
        return when (decision) {
            is ScheduledSyncDecision.Skip -> skip(graphId, decision.reason, health)
            is ScheduledSyncDecision.FetchOnly -> fetch(graphId).toTickOutcome()
            ScheduledSyncDecision.Run -> runFull(graphId, config)
        }
    }

    private fun inputs(graphId: String, config: GitConfig, repositorySafe: Boolean): ScheduledSyncInputs {
        val vault = try { credentialAccessProvider?.invoke() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val state = syncState.value
        return ScheduledSyncInputs(
            online = isOnline(),
            vaultUnlocked = vault?.isAvailable() ?: true,
            editing = editLock.isEditing.value,
            busy = busyCounter.isBusy.value,
            backoffActive = clock() < backoffUntilMs,
            conflictPending = state is SyncState.ConflictPending || state is SyncState.JournalMergeReady,
            repositorySafe = repositorySafe,
            appOwnedPath = try { isAppOwnedPath(config.repoRoot) } catch (e: CancellationException) { throw e } catch (_: Exception) { false },
            firstSyncConfirmed = firstSyncConfirmation?.let {
                it.isConfirmed(graphId, config.remoteName, config.remoteBranch) && !it.isReviewPending(graphId)
            } ?: false,
        )
    }

    private suspend fun skip(graphId: String, reason: ScheduledSkipReason, health: RepositoryHealth): TickOutcome {
        if (reason.fetchesAnyway) {
            val fetched = fetch(graphId)
            if (reason == ScheduledSkipReason.RepoState) syncState.value = SyncState.Error(DomainError.GitError.RepairNeeded(health.state))
            fetched.onLeft { err -> return TickOutcome.Failure(err.message, err) }
            // The tick did its job (a fetch); the policy only withheld the merge, so it must not read as skipped.
            return TickOutcome.Success
        }
        return TickOutcome.Skip(
            when (reason) {
                ScheduledSkipReason.Busy -> SkipReason.Busy
                ScheduledSkipReason.Offline -> SkipReason.Offline
                else -> SkipReason.Policy(reason)
            },
        )
    }

    private suspend fun runFull(graphId: String, config: GitConfig): TickOutcome {
        // With autoCommit off a dirty tree only gets a fetch: the user's edits are never committed for them.
        if (!config.autoCommit && gitRepository.status(config).getOrNull()?.hasLocalChanges != false) {
            logger.info("ScheduledSync graph=$graphId autoCommit off with local changes: fetch only")
            return fetch(graphId).toTickOutcome()
        }
        return fullSync(graphId).fold(
            { err ->
                when (err) {
                    is DomainError.GitError.Offline -> TickOutcome.Skip(SkipReason.Offline)
                    is DomainError.GitError.FirstSyncReviewPending ->
                        TickOutcome.Skip(SkipReason.Policy(ScheduledSkipReason.FirstSyncUnconfirmed))
                    else -> {
                        consecutiveFailures++
                        backoffUntilMs = clock() + ScheduledSyncPolicy.backoffMs(consecutiveFailures)
                        TickOutcome.Failure(err.message, err)
                    }
                }
            },
            {
                consecutiveFailures = 0
                backoffUntilMs = 0L
                TickOutcome.Success
            },
        )
    }
}
