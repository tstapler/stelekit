// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import dev.stapler.stelekit.jobs.schedule.NetworkPolicyCopy
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshot

/** Platform-neutral mirror of the WorkManager states this screen distinguishes. */
enum class ModelWorkState { None, Queued, Running, Succeeded, Failed, Cancelled }

enum class ModelDownloadAction { DownloadNowAnyway, Cancel, TryAgain, DownloadAgain }

/**
 * @property requiresUnmetered the queued request's network constraint is UNMETERED
 * @property attempt WorkManager `runAttemptCount` (0 for a first run)
 */
data class ModelDownloadInput(
    val work: ModelWorkState,
    val error: String? = null,
    val network: NetworkSnapshot? = null,
    val progress: Int = -1,
    val attempt: Int = 0,
    val requiresUnmetered: Boolean = true,
    val notificationsOff: Boolean = false,
)

/** [isWaiting] is a queued row (waiting on the network, offline or retrying) rather than live progress. */
data class ModelDownloadRow(
    val text: String,
    val actions: List<ModelDownloadAction>,
    val notice: String? = null,
    val isWaiting: Boolean = false,
)

/** Pure mapping from work state to the S6 copy and actions; every non-terminal-success state keeps an exit (UX-20). */
object ModelDownloadPresentation {
    const val WAITING = NetworkPolicyCopy.WAITING_FOR_UNMETERED
    const val OFFLINE = "Offline - will download when you are online"
    const val RETRYING = "Download failed. It will retry automatically."
    const val CANCELLED = "Download cancelled."
    const val NOTIFICATIONS_OFF =
        "Notifications are off, so you won't see download progress in the notification shade. " +
            "The download still runs; progress shows here."

    fun failedAfterTries(tries: Int = DepthModelDownloadWorker.MAX_ATTEMPTS) = "Download failed after $tries tries"

    /** Null when there is nothing to show (no work, or the download finished). */
    fun present(input: ModelDownloadInput): ModelDownloadRow? {
        val notice = if (input.notificationsOff) NOTIFICATIONS_OFF else null
        return when (input.work) {
            ModelWorkState.None, ModelWorkState.Succeeded -> null
            ModelWorkState.Running -> ModelDownloadRow(
                if (input.progress >= 0) "Downloading the depth model... ${input.progress}%" else "Downloading the depth model...",
                listOf(ModelDownloadAction.Cancel),
                notice,
            )
            ModelWorkState.Queued -> queued(input, notice)
            ModelWorkState.Failed -> ModelDownloadRow(
                failedAfterTries(), listOf(ModelDownloadAction.TryAgain),
            )
            ModelWorkState.Cancelled -> ModelDownloadRow(CANCELLED, listOf(ModelDownloadAction.DownloadAgain))
        }
    }

    private fun queued(input: ModelDownloadInput, notice: String?): ModelDownloadRow {
        val net = input.network
        return when {
            net != null && !net.online -> ModelDownloadRow(OFFLINE, listOf(ModelDownloadAction.Cancel), notice, isWaiting = true)
            input.attempt > 0 -> ModelDownloadRow(RETRYING, listOf(ModelDownloadAction.Cancel), notice, isWaiting = true)
            input.requiresUnmetered && (net == null || net.isMetered != false) ->
                ModelDownloadRow(WAITING, listOf(ModelDownloadAction.DownloadNowAnyway, ModelDownloadAction.Cancel), notice, isWaiting = true)
            else -> ModelDownloadRow("Starting the download...", listOf(ModelDownloadAction.Cancel), notice)
        }
    }

    /** Copy for a terminal failure's stored reason: skip reasons become the shared "after N tries" line. */
    fun failureReason(reason: String?): String? =
        if (reason != null && reason.startsWith("skipped:")) failedAfterTries() + "." else reason
}
