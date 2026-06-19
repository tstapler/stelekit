package dev.stapler.stelekit.performance

import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedTelemetryQueries
import dev.stapler.stelekit.db.TelemetryDatabase
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** 7 days in milliseconds. */
private const val RETENTION_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/** How often to re-run cleanup after the initial run (24 hours). */
private const val CLEANUP_INTERVAL_MS = 24L * 60 * 60 * 1000

/**
 * Periodic cleanup job that deletes histogram rows older than [retentionWindowMs] from
 * [TelemetryDatabase]. Runs once at startup and then every [cleanupIntervalMs] thereafter.
 *
 * Must be launched using the graph's [CoroutineScope] so it is cancelled when the graph
 * is closed or switched.
 */
class HistogramRetentionJob(
    private val database: TelemetryDatabase,
    private val retentionWindowMs: Long = RETENTION_WINDOW_MS,
    private val cleanupIntervalMs: Long = CLEANUP_INTERVAL_MS,
    writeMutex: Mutex = Mutex(),
) {
    private val restricted = RestrictedTelemetryQueries(database.telemetryQueries, writeMutex)

    fun start(scope: CoroutineScope) {
        scope.launch(PlatformDispatcher.IO) {
            while (true) {
                val cutoff = HistogramWriter.epochMs() - retentionWindowMs
                deleteOldRows(cutoff)
                delay(cleanupIntervalMs)
            }
        }
    }

    @OptIn(DirectSqlWrite::class)
    private suspend fun deleteOldRows(cutoff: Long) {
        withContext(PlatformDispatcher.DB) {
            restricted.withWriteLock {
                restricted.deleteOldHistogramRows(cutoff)
            }
        }
    }
}
