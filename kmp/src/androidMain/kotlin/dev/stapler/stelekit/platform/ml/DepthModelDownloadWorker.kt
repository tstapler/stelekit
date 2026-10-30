// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.download.ResumableDownload
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * Downloads the depth model as a WorkManager job: it survives process death and app backgrounding, waits for a
 * network, resumes a partial file after a dropped connection, and shows a dataSync foreground notification while
 * it runs. [DepthModelDownloader] is the app-facing facade that enqueues it and mirrors its state.
 */
class DepthModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val logger = Logger("DepthModelDownloadWorker")

    override suspend fun doWork(): Result {
        val target = DepthModelStorage.modelFile(applicationContext)
        if (DepthModelStorage.isReady(target)) return Result.success()
        val url = inputData.getString(KEY_URL) ?: DepthModelDownloader.MODEL_URL
        val part = File(target.parentFile, "${target.name}.part")

        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Foreground promotion can be denied while backgrounded; the transfer itself is still fine.
            logger.warn("setForeground denied, continuing without it", e)
        }

        return try {
            var lastPercent = -1
            ResumableDownload.download(url, part) { done, total ->
                val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                if (percent != lastPercent) {
                    lastPercent = percent
                    setProgress(workDataOf(KEY_PROGRESS to percent))
                }
            }
            if (part.length() <= DepthModelStorage.MIN_MODEL_BYTES) {
                part.delete()
                return Result.failure(workDataOf(KEY_ERROR to "The downloaded model was too small to be valid."))
            }
            target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.warn("download attempt ${runAttemptCount + 1} failed: ${e.message}")
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                Result.failure(workDataOf(KEY_ERROR to "The download kept failing. Check your connection and try again."))
            } else {
                Result.retry() // the .part file stays, so the retry resumes
            }
        } catch (e: Throwable) {
            // OutOfMemoryError and friends must fail the job, not the process (see CLAUDE.md on uncaught Throwables).
            logger.error("download failed unexpectedly", e)
            Result.failure(workDataOf(KEY_ERROR to "The download failed unexpectedly."))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading depth model")
            .setContentText("About 100 MB")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 5
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 0x4D44 // "MD"
    }
}

/** Where the depth model lives; shared by the facade, the worker and the ONNX estimator. */
internal object DepthModelStorage {
    /** A valid model is far larger than this; it only rejects an error page saved as the model. */
    const val MIN_MODEL_BYTES = 10L * 1024 * 1024

    fun modelFile(context: Context) = File(context.filesDir, "models/depth_anything_v2_small.onnx")

    fun isReady(file: File) = file.exists() && file.length() > MIN_MODEL_BYTES
}
