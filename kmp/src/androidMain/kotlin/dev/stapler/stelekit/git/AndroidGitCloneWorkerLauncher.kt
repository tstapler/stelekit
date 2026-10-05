// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Android [GitCloneWorkerLauncher]: enqueues [GitCloneWorker] as a one-off request and suspends
 * until its [WorkInfo] reaches a terminal state (Story 3.1.3, Task 3.1.3b), forwarding the worker's
 * published [GitTransportRetryState]/[CloneProgress] stream to [onStateChange]/[onProgress] and
 * decoding a terminal failure back into its [DomainError.GitError] ([GitCloneWorkerData]).
 *
 * The clone is enqueued under [WorkManagerSyncScheduler.workNameFor]'s per-graph unique-work name —
 * the SAME name the periodic [GitSyncWorker] job uses (Story 5.1.2). Chaining behind a periodic job
 * (`APPEND_OR_REPLACE`) would leave the clone `BLOCKED` forever, since a periodic run's completion
 * never unblocks dependents (verified by `WorkManagerSyncSchedulerUniqueWorkTest`). So an existing
 * periodic job is paused first ([WorkManagerSyncScheduler.pauseFor]), the clone starts fresh under
 * the name with `REPLACE`, and the periodic job is restored ([WorkManagerSyncScheduler.resumeFor])
 * once the clone terminates. A clone still `BLOCKED` after [blockedTimeoutMs] fails with
 * [DomainError.GitError.CloneFailed] rather than hanging. [cancel] targets the same shared name.
 * [GitCloneWorkTracker] records the enqueued request's id/start time, giving Story 3.1.7's
 * stuck-clone watchdog a concrete, queryable identity per graph.
 */
class AndroidGitCloneWorkerLauncher(
    private val context: Context,
    private val blockedTimeoutMs: Long = DEFAULT_BLOCKED_TIMEOUT_MS,
    private val credentialAccessOverride: CredentialAccess? = null,
) : GitCloneWorkerLauncher {

    override suspend fun launchClone(
        graphId: String,
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (CloneProgress) -> Unit,
        onStateChange: (GitTransportRetryState) -> Unit,
        graphDisplayName: String?,
    ): Either<DomainError.GitError, Unit> {
        val workManager = WorkManager.getInstance(context)
        val workName = WorkManagerSyncScheduler.workNameFor(graphId)
        var resumePeriodic = false
        try {
            val inputData = Data.Builder()
                .putAll(
                    workDataOf(
                        GitCloneWorker.KEY_URL to url,
                        GitCloneWorker.KEY_LOCAL_PATH to localPath,
                        GitCloneWorker.KEY_GRAPH_ID to graphId,
                        GitCloneWorker.KEY_GRAPH_DISPLAY_NAME to graphDisplayName,
                    )
                )
                .putAll(persistAuthAndBuildAuthData(graphId, auth))
                .build()

            val request = OneTimeWorkRequestBuilder<GitCloneWorker>()
                .setInputData(inputData)
                .build()

            resumePeriodic = withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(workName).get().any { !it.state.isFinished }
            }
            if (resumePeriodic) WorkManagerSyncScheduler.pauseFor(context, graphId)

            GitCloneWorkTracker.recordStart(context, graphId, request.id, System.currentTimeMillis())
            workManager.beginUniqueWork(workName, ExistingWorkPolicy.REPLACE, request).enqueue()

            return awaitTerminal(workManager, request.id, onProgress, onStateChange)
        } finally {
            GitCloneWorker.clearTransientCredentials(context, graphId, credentialAccessOverride)
            GitCloneWorkTracker.clear(context, graphId)
            if (resumePeriodic) WorkManagerSyncScheduler.resumeFor(context, graphId)
        }
    }

    private suspend fun awaitTerminal(
        workManager: WorkManager,
        workId: UUID,
        onProgress: (CloneProgress) -> Unit,
        onStateChange: (GitTransportRetryState) -> Unit,
    ): Either<DomainError.GitError, Unit> {
        var terminalInfo: WorkInfo? = null
        val lastState = AtomicReference<WorkInfo.State?>(null)
        val blockedOut = AtomicBoolean(false)
        var lastProgress: Data? = null

        coroutineScope {
            val collector = launch {
                workManager.getWorkInfoByIdFlow(workId)
                    .takeWhile { info ->
                        lastState.set(info?.state)
                        if (info == null || info.state.isFinished) {
                            terminalInfo = info
                            false
                        } else {
                            if (info.progress != lastProgress) {
                                lastProgress = info.progress
                                GitCloneWorkerData.decodeState(info.progress)?.let { state ->
                                    onStateChange(state)
                                    GitCloneWorkerData.progressOf(state)?.let(onProgress)
                                }
                            }
                            true
                        }
                    }
                    .collect { }
            }
            val watchdog = launch {
                delay(blockedTimeoutMs)
                if (lastState.get() == WorkInfo.State.BLOCKED) {
                    blockedOut.set(true)
                    workManager.cancelWorkById(workId)
                    collector.cancel()
                }
            }
            collector.join()
            watchdog.cancel()
        }

        if (blockedOut.get()) {
            return DomainError.GitError.CloneFailed(
                "Clone was still blocked behind other work for the same graph after ${blockedTimeoutMs}ms"
            ).left()
        }
        val info = terminalInfo
        return when (info?.state) {
            WorkInfo.State.SUCCEEDED -> Unit.right()
            // Task 4.1.4b: a manual cancel() below stops the unique work, which WorkManager
            // surfaces here as WorkInfo.State.CANCELLED — mirror GitRepository.clone()'s own
            // Cancelled contract (a thrown CancellationException, never an Either.Left) so every
            // caller (GitSetupScreenSaveLogic.performCloneAndSave) handles cancellation the same
            // way regardless of platform.
            WorkInfo.State.CANCELLED -> throw CancellationException("Clone cancelled")
            else -> {
                val decoded = info?.outputData?.let { GitCloneWorkerData.decodeFailure(it) }
                if (decoded != null) {
                    onStateChange(decoded.second)
                    decoded.first.left()
                } else {
                    DomainError.GitError.CloneFailed(
                        "Clone worker did not complete successfully (state=${info?.state})"
                    ).left()
                }
            }
        }
    }

    override fun cancel(graphId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(WorkManagerSyncScheduler.workNameFor(graphId))
        GitCloneWorker.clearTransientCredentials(context, graphId, credentialAccessOverride)
    }

    /**
     * [GitCloneWorker] resolves credentials from `CredentialStore` by [graphId] (see its kdoc) —
     * a suspend auth-provider lambda can't survive WorkManager's process-independent execution,
     * so any secret [auth] holds must be persisted before enqueueing.
     */
    private suspend fun persistAuthAndBuildAuthData(graphId: String, auth: GitAuth): Data {
        val store = credentialAccessOverride ?: run {
            CredentialStore.init(context)
            CredentialStore()
        }
        return when (auth) {
            is GitAuth.HttpsToken -> {
                auth.tokenProvider()?.let { store.store(GitCloneWorker.httpsTokenCredentialKey(graphId), it) }
                workDataOf(GitCloneWorker.KEY_AUTH_TYPE to GitCloneWorker.AUTH_HTTPS_TOKEN)
            }
            is GitAuth.SshKey -> {
                auth.passphraseProvider()?.let { store.store(GitCloneWorker.sshPassphraseCredentialKey(graphId), it) }
                workDataOf(
                    GitCloneWorker.KEY_AUTH_TYPE to GitCloneWorker.AUTH_SSH_KEY,
                    GitCloneWorker.KEY_SSH_KEY_PATH to auth.keyPath,
                )
            }
            GitAuth.None -> workDataOf(GitCloneWorker.KEY_AUTH_TYPE to GitCloneWorker.AUTH_NONE)
        }
    }

    companion object {
        /** How long a clone may sit `BLOCKED` behind other same-name work before failing. */
        const val DEFAULT_BLOCKED_TIMEOUT_MS = 30_000L
    }
}
