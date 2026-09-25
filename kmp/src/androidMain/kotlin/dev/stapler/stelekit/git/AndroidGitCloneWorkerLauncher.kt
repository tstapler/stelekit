// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.flow.takeWhile

/**
 * Android [GitCloneWorkerLauncher]: enqueues [GitCloneWorker] as a one-off request and suspends
 * until its [WorkInfo] reaches a terminal state (Story 3.1.3, Task 3.1.3b). A plain one-off
 * enqueue, not yet the unique-work name [WorkManagerSyncScheduler]'s periodic job shares (Story
 * 5.1.2, Phase 5 — out of scope here) — independently correct in the meantime. [GitCloneWorkTracker]
 * records the enqueued request's id/start time regardless, giving Story 3.1.7's stuck-clone
 * watchdog a concrete, queryable identity per graph even before 5.1.2's convergence.
 */
class AndroidGitCloneWorkerLauncher(private val context: Context) : GitCloneWorkerLauncher {

    override suspend fun launchClone(
        graphId: String,
        url: String,
        localPath: String,
        auth: GitAuth,
        onProgress: (String) -> Unit,
    ): Either<DomainError.GitError, Unit> {
        val inputData = Data.Builder()
            .putAll(
                workDataOf(
                    GitCloneWorker.KEY_URL to url,
                    GitCloneWorker.KEY_LOCAL_PATH to localPath,
                    GitCloneWorker.KEY_GRAPH_ID to graphId,
                )
            )
            .putAll(persistAuthAndBuildAuthData(graphId, auth))
            .build()

        val request = OneTimeWorkRequestBuilder<GitCloneWorker>()
            .setInputData(inputData)
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueue(request)
        GitCloneWorkTracker.recordStart(context, graphId, request.id, System.currentTimeMillis())

        var terminalState: WorkInfo.State? = null
        workManager.getWorkInfoByIdFlow(request.id)
            .takeWhile { info ->
                if (info == null || info.state.isFinished) {
                    terminalState = info?.state
                    false
                } else {
                    info.progress.getString(GitCloneWorker.KEY_PROGRESS_PHASE)?.let(onProgress)
                    true
                }
            }
            .collect { }

        GitCloneWorkTracker.clear(context, graphId)
        return when (terminalState) {
            WorkInfo.State.SUCCEEDED -> Unit.right()
            else -> DomainError.GitError.CloneFailed(
                "Clone worker did not complete successfully (state=$terminalState)"
            ).left()
        }
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
