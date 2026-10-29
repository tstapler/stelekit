// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.ml.DepthModelDownloadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Tags a WorkManager request must carry to show up in [WorkManagerJobRegistry]. */
object JobTags {
    const val ANY = "stelekit-job"
    private const val KIND_PREFIX = "stelekit-job-kind:"
    private const val TITLE_PREFIX = "stelekit-job-title:"

    fun kind(kind: JobKind) = KIND_PREFIX + kind.name
    fun title(title: String) = TITLE_PREFIX + title

    internal fun kindOf(tags: Set<String>): JobKind? =
        tags.firstOrNull { it.startsWith(KIND_PREFIX) }
            ?.removePrefix(KIND_PREFIX)
            ?.let { name -> JobKind.entries.firstOrNull { it.name == name } }

    internal fun titleOf(tags: Set<String>): String? =
        tags.firstOrNull { it.startsWith(TITLE_PREFIX) }?.removePrefix(TITLE_PREFIX)
}

/** Exposes every tagged WorkManager request as a [JobStatus]. Progress and failure copy come from the worker's data keys. */
class WorkManagerJobRegistry(
    private val context: Context,
    private val progressKey: (JobKind) -> String? = ::defaultProgressKey,
    private val errorKey: (JobKind) -> String? = ::defaultErrorKey,
) : BackgroundJobRegistry {
    private val logger = Logger("WorkManagerJobRegistry")

    override val jobs: Flow<List<JobStatus>> =
        workManagerOrNull()?.getWorkInfosByTagFlow(JobTags.ANY)
            ?.map { infos -> infos.mapNotNull { it.toJobStatus(progressKey, errorKey) }.activeFirst() }
            ?: emptyFlow()

    override fun cancel(id: String) {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return
        workManagerOrNull()?.cancelWorkById(uuid)
    }

    override fun clearFinished() {
        workManagerOrNull()?.pruneWork()
    }

    private fun workManagerOrNull(): WorkManager? = try {
        WorkManager.getInstance(context)
    } catch (e: IllegalStateException) {
        logger.warn("WorkManager is not initialised", e)
        null
    }
}

internal fun defaultProgressKey(kind: JobKind): String? = when (kind) {
    JobKind.ModelDownload -> DepthModelDownloadWorker.KEY_PROGRESS
    else -> null
}

internal fun defaultErrorKey(kind: JobKind): String? = when (kind) {
    JobKind.ModelDownload -> DepthModelDownloadWorker.KEY_ERROR
    else -> null
}

/** Null for work that carries [JobTags.ANY] but no recognisable kind. */
internal fun WorkInfo.toJobStatus(
    progressKey: (JobKind) -> String?,
    errorKey: (JobKind) -> String?,
): JobStatus? {
    val kind = JobTags.kindOf(tags) ?: return null
    val state = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> JobState.Queued
        WorkInfo.State.RUNNING -> JobState.Running(
            progressKey(kind)?.let { progress.getInt(it, -1) }?.takeIf { it >= 0 },
        )
        WorkInfo.State.SUCCEEDED -> JobState.Succeeded
        WorkInfo.State.FAILED -> JobState.Failed(errorKey(kind)?.let { outputData.getString(it) })
        WorkInfo.State.CANCELLED -> JobState.Cancelled
    }
    return JobStatus(id.toString(), kind, JobTags.titleOf(tags) ?: kind.label, state)
}
