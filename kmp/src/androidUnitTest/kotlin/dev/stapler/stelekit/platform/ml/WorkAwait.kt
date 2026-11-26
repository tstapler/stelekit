package dev.stapler.stelekit.platform.ml

import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Suspends until WorkManager reports an unfinished work item under [uniqueName]; observes the DB, no polling delay.
 * No `withTimeout`: under `runTest` it would expire on virtual time; the test's own timeout is the ceiling.
 */
internal suspend fun awaitActiveWorkId(work: WorkManager, uniqueName: String): UUID =
    work.getWorkInfosForUniqueWorkFlow(uniqueName).first { infos -> infos.any { !it.state.isFinished } }
        .first { !it.state.isFinished }.id

/**
 * Suspends until the downloader reports `Downloading`. Release a job's constraints only after this: a job that
 * ends first can have its terminal state replaced by downloadModel's post-enqueue `Downloading` write.
 */
internal suspend fun DepthModelDownloader.awaitDownloadingState() {
    modelState.first { it is DepthModelDownloader.ModelState.Downloading }
}
