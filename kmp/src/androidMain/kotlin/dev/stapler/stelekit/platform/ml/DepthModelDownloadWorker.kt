// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Legacy entry point for a download enqueued before the scheduling port existed (its class name is persisted in
 * WorkManager's database) and for tests that inject a URL. New requests run as `TaskWorker` over
 * [ModelDownloadTask]; this worker only delegates to the same body and maps its result, so behaviour is identical.
 */
class DepthModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val logger = Logger("DepthModelDownloadWorker")

    override suspend fun doWork(): Result {
        if (DepthModelStorage.isReady(DepthModelStorage.modelFile(applicationContext))) return Result.success()
        val url = inputData.getString(KEY_URL) ?: DepthModelDownloader.MODEL_URL
        val sha256 = inputData.getString(KEY_SHA256) ?: DepthModelDownloader.MODEL_SHA256
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Foreground promotion can be denied while backgrounded; the transfer itself is still fine.
            logger.warn("setForeground denied, continuing without it", e)
        }
        return when (val outcome = ModelDownloadTask(applicationContext, url, sha256).download(url) { setProgress(workDataOf(KEY_PROGRESS to it)) }) {
            is TaskRunResult.Failed ->
                if (outcome.retryable && runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() // the .part file stays, so the retry resumes
                else Result.failure(workDataOf(KEY_ERROR to outcome.message))
            else -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ModelDownloadForeground.info(applicationContext)

    companion object {
        const val KEY_URL = "url"
        const val KEY_SHA256 = "sha256"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val MAX_ATTEMPTS = 5
    }
}

/** Where the depth model lives; shared by the facade, the worker and the ONNX estimator. */
internal object DepthModelStorage {
    /** A valid model is far larger than this; it only rejects an error page saved as the model. */
    const val MIN_MODEL_BYTES = 10L * 1024 * 1024

    fun modelFile(context: Context) = File(context.filesDir, "models/depth_anything_v2_small.onnx")

    fun isReady(file: File) = file.exists() && file.length() > MIN_MODEL_BYTES
}
