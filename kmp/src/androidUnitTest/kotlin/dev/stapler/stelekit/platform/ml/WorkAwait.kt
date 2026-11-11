package dev.stapler.stelekit.platform.ml

import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Suspends until WorkManager reports an unfinished work item under [uniqueName]; observes the DB, no polling delay. */
internal suspend fun awaitActiveWorkId(work: WorkManager, uniqueName: String, timeoutMs: Long = 5_000): UUID =
    withTimeout(timeoutMs) {
        work.getWorkInfosForUniqueWorkFlow(uniqueName).first { infos -> infos.any { !it.state.isFinished } }
            .first { !it.state.isFinished }.id
    }
