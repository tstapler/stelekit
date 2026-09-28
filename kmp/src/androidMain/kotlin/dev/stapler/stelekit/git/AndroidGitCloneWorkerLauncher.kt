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
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.takeWhile

/**
 * Android [GitCloneWorkerLauncher]: enqueues [GitCloneWorker] as a one-off request and suspends
 * until its [WorkInfo] reaches a terminal state (Story 3.1.3, Task 3.1.3b). Enqueued via
 * [WorkManagerSyncScheduler.workNameFor]'s per-graph unique-work name — the SAME name the periodic
 * [GitSyncWorker] job uses (Story 5.1.2) — through `beginUniqueWork`/`APPEND_OR_REPLACE`, so
 * WorkManager's own uniqueness guarantee serializes a foreground clone/fetch/push against the
 * periodic background fetch instead of letting them race at the JGit level
 * (`research/architecture.md` §4, `research/pitfalls.md` §3.4). [cancel] targets the same shared
 * name with `cancelUniqueWork`. [GitCloneWorkTracker] records the enqueued request's id/start
 * time regardless, giving Story 3.1.7's stuck-clone watchdog a concrete, queryable identity per
 * graph.
 */
class AndroidGitCloneWorkerLauncher(private val context: Context) : GitCloneWorkerLauncher {

    override suspend fun launchClone(
        graphId: String,
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (CloneProgress) -> Unit,
        onStateChange: (GitTransportRetryState) -> Unit,
        graphDisplayName: String?,
    ): Either<DomainError.GitError, Unit> {
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

        val workManager = WorkManager.getInstance(context)
        val workName = WorkManagerSyncScheduler.workNameFor(graphId)
        workManager.beginUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue()
        GitCloneWorkTracker.recordStart(context, graphId, request.id, System.currentTimeMillis())

        var terminalState: WorkInfo.State? = null
        workManager.getWorkInfoByIdFlow(request.id)
            .takeWhile { info ->
                if (info == null || info.state.isFinished) {
                    terminalState = info?.state
                    false
                } else {
                    info.progress.getString(GitCloneWorker.KEY_PROGRESS_PHASE)?.let { phase ->
                        onProgress(CloneProgress(phase, 0, 0))
                    }
                    true
                }
            }
            .collect { }

        GitCloneWorkTracker.clear(context, graphId)
        return when (terminalState) {
            WorkInfo.State.SUCCEEDED -> Unit.right()
            // Task 4.1.4b: a manual cancel() below stops the unique work, which WorkManager
            // surfaces here as WorkInfo.State.CANCELLED — mirror GitRepository.clone()'s own
            // Cancelled contract (a thrown CancellationException, never an Either.Left) so every
            // caller (GitSetupScreenSaveLogic.performCloneAndSave) handles cancellation the same
            // way regardless of platform.
            WorkInfo.State.CANCELLED -> throw CancellationException("Clone cancelled")
            else -> DomainError.GitError.CloneFailed(
                "Clone worker did not complete successfully (state=$terminalState)"
            ).left()
        }
    }

    override fun cancel(graphId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(WorkManagerSyncScheduler.workNameFor(graphId))
    }

    /**
     * [GitCloneWorker] resolves credentials from `CredentialStore` by [graphId] (see its kdoc) —
     * a suspend auth-provider lambda can't survive WorkManager's process-independent execution,
     * so any secret [auth] holds must be persisted before enqueueing.
     */
    private suspend fun persistAuthAndBuildAuthData(graphId: String, auth: GitAuth): Data {
        CredentialStore.init(context)
        val store = CredentialStore()
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
}
