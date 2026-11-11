// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.schedule.BackgroundTask
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.Schedule
import dev.stapler.stelekit.jobs.schedule.TaskConfig
import dev.stapler.stelekit.jobs.schedule.TaskConstraints
import dev.stapler.stelekit.jobs.schedule.TaskForegroundProvider
import dev.stapler.stelekit.jobs.schedule.TaskKey
import dev.stapler.stelekit.jobs.schedule.TaskKind
import dev.stapler.stelekit.jobs.schedule.TaskRegistry
import dev.stapler.stelekit.jobs.schedule.TaskRunContext
import dev.stapler.stelekit.jobs.schedule.TaskRunResult
import dev.stapler.stelekit.jobs.schedule.TaskScope
import dev.stapler.stelekit.jobs.schedule.TaskShape
import dev.stapler.stelekit.jobs.schedule.TaskSpec
import dev.stapler.stelekit.jobs.schedule.TaskWorkerRuntime
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.download.ResumableDownload
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * The depth-model download as a one-shot [BackgroundTask]: resumes a partial `.part` file, validates the size and
 * moves it into place. A transport failure is `Failed(retryable = true)`, so the adapter retries with backoff within
 * the shared attempt budget ([MAX_ATTEMPTS]). It never touches `DepthModelDownloader.ModelState`; that mirrors the
 * WorkManager state ([DepthModelDownloader]).
 */
class ModelDownloadTask(
    context: Context,
    private val url: String = DepthModelDownloader.MODEL_URL,
) : BackgroundTask {
    private val appContext: Context = context.applicationContext ?: context
    private val logger = Logger("ModelDownloadTask")

    override suspend fun run(key: TaskKey, ctx: TaskRunContext): TaskRunResult = download(url, ctx.reportProgress)

    /** Shared with the legacy [DepthModelDownloadWorker], which passes its own URL and progress channel. */
    internal suspend fun download(url: String, onProgress: suspend (Int) -> Unit): TaskRunResult {
        val target = DepthModelStorage.modelFile(appContext)
        if (DepthModelStorage.isReady(target)) return TaskRunResult.Success
        val part = File(target.parentFile, "${target.name}.part")
        return try {
            var lastPercent = -1
            ResumableDownload.download(url, part) { done, total ->
                val percent = computeProgressPercent(done, total)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
            if (part.length() <= DepthModelStorage.MIN_MODEL_BYTES) {
                part.delete()
                return TaskRunResult.Failed("The downloaded model was too small to be valid.", retryable = false)
            }
            target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            TaskRunResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.warn("download attempt failed: ${e.message}")
            // The .part file stays, so the retry resumes.
            TaskRunResult.Failed("The download kept failing. Check your connection and try again.", retryable = true)
        } catch (e: Throwable) {
            // OutOfMemoryError and friends must fail the job, not the process (see CLAUDE.md on uncaught Throwables).
            logger.error("download failed unexpectedly", e)
            TaskRunResult.Failed("The download failed unexpectedly.", retryable = false)
        }
    }

    companion object {
        val KIND = TaskKind("model-download")
        val KEY = TaskKey(KIND, TaskScope(""))

        /** Shared one-shot attempt budget: skips and real failures together. */
        const val MAX_ATTEMPTS = DepthModelDownloadWorker.MAX_ATTEMPTS

        /**
         * One-shot: only `constraints` is read (`enabled` and the schedule are placeholders, see `TaskSpec`).
         * Network default is [NetworkPolicy.Any] here; flipping to `Unmetered` is the one-line owner-confirm change.
         */
        val defaultConfig = TaskConfig(
            enabled = false,
            schedule = Schedule.of(60, 0),
            constraints = TaskConstraints(network = NetworkPolicy.Any, requiresStorageNotLow = true),
        )

        val spec = TaskSpec(
            jobKind = JobKind.ModelDownload,
            title = "Depth model download",
            shape = TaskShape.OneShot,
            defaultConfig = defaultConfig,
            allowedInColdPath = true,
            oneShotRetryCap = MAX_ATTEMPTS,
        )
    }
}

/**
 * Registers `model-download` and its dataSync foreground notification. Called only from
 * `SteleKitApplication.onCreate`; idempotent for an equal spec (a second process start builds a new task).
 */
fun registerModelDownloadTask(
    registry: TaskRegistry,
    context: Context,
    foreground: TaskForegroundProvider = TaskWorkerRuntime.foreground,
    url: String = DepthModelDownloader.MODEL_URL,
) {
    registry.register(ModelDownloadTask.KIND, ModelDownloadTask.spec, ModelDownloadTask(context, url))
    foreground.register(ModelDownloadTask.KIND) { ctx, _ -> ModelDownloadForeground.info(ctx) }
}

/** The pre-existing "Downloading depth model" notification, preserved verbatim (no new notification). */
internal object ModelDownloadForeground {
    private const val CHANNEL_ID = "model_downloads"
    private const val NOTIFICATION_ID = 0x4D44 // "MD"

    fun info(context: Context): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading depth model")
            .setContentText("About 100 MB")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
