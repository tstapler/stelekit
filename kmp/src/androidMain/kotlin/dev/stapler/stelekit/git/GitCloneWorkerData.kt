// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import androidx.work.Data
import androidx.work.workDataOf
import dev.stapler.stelekit.error.DomainError

/**
 * WorkManager `Data` wire format between [GitCloneWorker] (publisher, via `setProgressAsync` and
 * the terminal `Result.failure(data)`) and [AndroidGitCloneWorkerLauncher] (decoder). Keeps the
 * [GitTransportRetryState] stream and the terminal [DomainError] kind alive across WorkManager's
 * process-independent boundary so Step 5 renders the same states as on Desktop.
 */
internal object GitCloneWorkerData {
    private const val KEY_STATE = "state_tag"
    private const val KEY_ATTEMPT = "state_attempt"
    private const val KEY_MAX = "state_max"
    private const val KEY_PHASE = GitCloneWorker.KEY_PROGRESS_PHASE
    private const val KEY_COMPLETED = "progress_completed"
    private const val KEY_TOTAL = "progress_total"
    private const val KEY_HAS_PROGRESS = "progress_present"
    private const val KEY_PERCENT = "state_percent"
    private const val KEY_REASON = "state_reason"
    private const val KEY_FG_PROMOTED = "state_fg_promoted"

    const val KEY_ERROR_KIND = "error_kind"
    private const val KEY_ERROR_MESSAGE = "error_message"
    private const val KEY_ERROR_ATTEMPTS = "error_attempts"
    private const val KEY_ERROR_REASON = "error_reason"

    const val ERROR_AUTH = "auth_failed"
    const val ERROR_RETRY_EXHAUSTED = "retry_exhausted"
    const val ERROR_CLONE_FAILED = "clone_failed"

    private const val TAG_IDLE = "idle"
    private const val TAG_ATTEMPTING = "attempting"
    private const val TAG_RETRYING = "retrying"
    private const val TAG_RESUMING = "resuming"
    private const val TAG_EXHAUSTED = "exhausted"
    private const val TAG_NON_RETRYABLE = "non_retryable"

    fun encodeState(state: GitTransportRetryState): Data = when (state) {
        is GitTransportRetryState.Idle -> workDataOf(KEY_STATE to TAG_IDLE)
        is GitTransportRetryState.Attempting -> Data.Builder()
            .putString(KEY_STATE, TAG_ATTEMPTING)
            .putBoolean(KEY_FG_PROMOTED, state.foregroundPromoted)
            .putProgress(state.progress)
            .build()
        is GitTransportRetryState.Retrying -> Data.Builder()
            .putString(KEY_STATE, TAG_RETRYING)
            .putInt(KEY_ATTEMPT, state.attempt)
            .putInt(KEY_MAX, state.max)
            .apply { state.progress?.let { putProgress(it) } }
            .build()
        is GitTransportRetryState.ResumingDeepen -> workDataOf(
            KEY_STATE to TAG_RESUMING,
            KEY_PERCENT to (state.percent ?: -1),
        )
        is GitTransportRetryState.Exhausted -> workDataOf(
            KEY_STATE to TAG_EXHAUSTED,
            KEY_REASON to state.reason,
            KEY_MAX to state.maxAttempts,
        )
        is GitTransportRetryState.NonRetryableFailure -> workDataOf(
            KEY_STATE to TAG_NON_RETRYABLE,
            KEY_REASON to state.reason,
        )
    }

    /** Inverse of [encodeState]; `null` for `Data` that carries no state (e.g. empty progress). */
    fun decodeState(data: Data): GitTransportRetryState? = when (data.getString(KEY_STATE)) {
        TAG_IDLE -> GitTransportRetryState.Idle
        TAG_ATTEMPTING -> GitTransportRetryState.Attempting(
            decodeProgress(data) ?: CloneProgress("", 0, 0),
            data.getBoolean(KEY_FG_PROMOTED, true),
        )
        TAG_RETRYING -> GitTransportRetryState.Retrying(
            data.getInt(KEY_ATTEMPT, 0),
            data.getInt(KEY_MAX, 0),
            decodeProgress(data),
        )
        TAG_RESUMING -> GitTransportRetryState.ResumingDeepen(data.getInt(KEY_PERCENT, -1).takeIf { it >= 0 })
        TAG_EXHAUSTED -> GitTransportRetryState.Exhausted(
            data.getString(KEY_REASON) ?: "",
            data.getInt(KEY_MAX, 5),
        )
        TAG_NON_RETRYABLE -> GitTransportRetryState.NonRetryableFailure(
            data.getString(KEY_REASON) ?: NonRetryableReason.OTHER,
        )
        else -> null
    }

    /** The in-flight transfer progress a decoded [state] carries, if any. */
    fun progressOf(state: GitTransportRetryState): CloneProgress? = when (state) {
        is GitTransportRetryState.Attempting -> state.progress
        is GitTransportRetryState.Retrying -> state.progress
        else -> null
    }

    /** Terminal `Result.failure` payload for a failed clone. [reasonTag] is the last
     * [GitTransportRetryState.NonRetryableFailure.reason] the worker observed, if any. */
    fun encodeFailure(error: DomainError.GitError, reasonTag: String?): Data = when (error) {
        is DomainError.GitError.AuthFailed -> Data.Builder()
            .putString(KEY_ERROR_KIND, ERROR_AUTH)
            .putString(KEY_ERROR_MESSAGE, error.message)
            .putString(KEY_ERROR_REASON, reasonTag ?: NonRetryableReason.AUTH)
            .build()
        is DomainError.GitError.RetryExhausted -> Data.Builder()
            .putString(KEY_ERROR_KIND, ERROR_RETRY_EXHAUSTED)
            .putString(KEY_ERROR_MESSAGE, error.lastError.message)
            .putInt(KEY_ERROR_ATTEMPTS, error.attempts)
            .build()
        else -> Data.Builder()
            .putString(KEY_ERROR_KIND, ERROR_CLONE_FAILED)
            .putString(KEY_ERROR_MESSAGE, error.message)
            .putString(KEY_ERROR_REASON, reasonTag ?: NonRetryableReason.OTHER)
            .build()
    }

    /** Decodes a terminal failure into the [DomainError] plus the terminal state Step 5 should
     * show; `null` when [output] carries no [KEY_ERROR_KIND] (e.g. an uncaught-Throwable failure). */
    fun decodeFailure(output: Data): Pair<DomainError.GitError, GitTransportRetryState>? {
        val message = output.getString(KEY_ERROR_MESSAGE) ?: "Clone failed"
        return when (output.getString(KEY_ERROR_KIND)) {
            ERROR_AUTH -> DomainError.GitError.AuthFailed(message) to
                GitTransportRetryState.NonRetryableFailure(output.getString(KEY_ERROR_REASON) ?: NonRetryableReason.AUTH)
            ERROR_RETRY_EXHAUSTED -> {
                val attempts = output.getInt(KEY_ERROR_ATTEMPTS, 0)
                DomainError.GitError.RetryExhausted(attempts, DomainError.GitError.CloneFailed(message)) to
                    GitTransportRetryState.Exhausted(message, attempts)
            }
            ERROR_CLONE_FAILED -> DomainError.GitError.CloneFailed(message) to
                GitTransportRetryState.NonRetryableFailure(output.getString(KEY_ERROR_REASON) ?: NonRetryableReason.OTHER)
            else -> null
        }
    }

    private fun Data.Builder.putProgress(progress: CloneProgress): Data.Builder = this
        .putBoolean(KEY_HAS_PROGRESS, true)
        .putString(KEY_PHASE, progress.phase)
        .putInt(KEY_COMPLETED, progress.completed)
        .putInt(KEY_TOTAL, progress.totalWork)

    private fun decodeProgress(data: Data): CloneProgress? =
        if (data.getBoolean(KEY_HAS_PROGRESS, false)) {
            CloneProgress(data.getString(KEY_PHASE) ?: "", data.getInt(KEY_COMPLETED, 0), data.getInt(KEY_TOTAL, 0))
        } else null
}

/**
 * Emits a progress tick only when the integer percent changed or [minIntervalMs] elapsed since the
 * last emit — JGit's `ProgressMonitor.update` fires per object, which would otherwise flood both
 * `NotificationManager` and WorkManager's DB-backed `setProgressAsync`.
 */
internal class ProgressThrottle(
    private val minIntervalMs: Long = 500L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastEmitMs = Long.MIN_VALUE
    private var lastPercent: Int? = null
    private var lastPhase: String? = null

    fun shouldEmit(progress: CloneProgress): Boolean {
        val now = clock()
        val percent = if (progress.totalWork > 0) progress.completed * 100 / progress.totalWork else null
        val changed = lastEmitMs == Long.MIN_VALUE || percent != lastPercent || progress.phase != lastPhase
        val elapsed = lastEmitMs != Long.MIN_VALUE && now - lastEmitMs >= minIntervalMs
        if (!changed && !elapsed) return false
        lastEmitMs = now
        lastPercent = percent
        lastPhase = progress.phase
        return true
    }
}
