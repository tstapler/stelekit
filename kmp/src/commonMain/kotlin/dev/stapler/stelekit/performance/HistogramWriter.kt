package dev.stapler.stelekit.performance

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A sample to be recorded in the histogram store.
 */
data class HistogramSample(
    val operationName: String,
    val durationMs: Long,
    val recordedAt: Long
)

/**
 * Always-on, non-blocking histogram writer.
 *
 * Accepts latency samples from any coroutine context and batch-writes them to SQLDelight
 * via [Dispatchers.IO]. The internal [Channel] uses a buffered capacity; on overflow the
 * sample is silently dropped rather than blocking the caller.
 *
 * Bucket upper bounds (ms): 0, 16, 33, 50, 100, 500, 1000, 5000, 9999 (overflow).
 */
class HistogramWriter(
    private val database: SteleDatabase,
    scope: CoroutineScope,
    // Route writes through the actor so histogram inserts are serialized with all other
    // DB writes. Without this, concurrent transaction() calls on Dispatchers.IO race with
    // the actor's writes, causing SQLITE_BUSY when busy_timeout fires before a lock clears.
    private val writeActor: DatabaseWriteActor? = null,
) {
    private val channel = Channel<HistogramSample>(capacity = Channel.BUFFERED)
    private val restricted = RestrictedDatabaseQueries(database.steleDatabaseQueries)

    init {
        scope.launch(PlatformDispatcher.DB) {
            for (sample in channel) {
                processSample(sample)
            }
        }
    }

    @OptIn(DirectSqlWrite::class)
    private suspend fun processSample(sample: HistogramSample) {
        try {
            val bucket = classifyBucket(sample.durationMs)
            val writeOp: suspend () -> Either<DomainError, Unit> = {
                try {
                    restricted.transaction {
                        restricted.insertHistogramBucketIfAbsent(
                            operation_name = sample.operationName,
                            bucket_ms = bucket,
                            recorded_at = sample.recordedAt
                        )
                        restricted.incrementHistogramBucketCount(
                            recorded_at = sample.recordedAt,
                            operation_name = sample.operationName,
                            bucket_ms = bucket
                        )
                    }
                    Unit.right()
                } catch (e: Exception) {
                    DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
                }
            }
            if (writeActor != null) writeActor.execute(op = writeOp) else writeOp()
        } catch (e: Exception) {
            // A DB error must not kill the consumer coroutine — log and continue
        }
    }

    /**
     * Record a latency sample. Non-blocking: if the channel is full the sample is dropped silently.
     */
    fun record(operationName: String, durationMs: Long, recordedAt: Long = epochMs()) {
        channel.trySend(HistogramSample(operationName, durationMs, recordedAt))
    }

    /**
     * Query approximate percentiles for [operationName] from the stored buckets.
     * Returns null if no data is available for the operation.
     */
    fun queryPercentiles(operationName: String): PercentileSummary? {
        val rows = database.steleDatabaseQueries.selectHistogramForOperation(operationName).executeAsList()
        if (rows.isEmpty()) return null
        val totalCount = rows.sumOf { it.count }
        if (totalCount == 0L) return null

        fun percentileMs(pct: Double): Long {
            val target = (totalCount * pct).toLong().coerceAtLeast(1L)
            var cumulative = 0L
            for (row in rows) {
                cumulative += row.count
                if (cumulative >= target) return row.bucket_ms
            }
            return rows.last().bucket_ms
        }

        return PercentileSummary(
            operationName = operationName,
            p50Ms = percentileMs(0.50),
            p95Ms = percentileMs(0.95),
            p99Ms = percentileMs(0.99),
            sampleCount = totalCount
        )
    }

    companion object {
        /** Bucket upper bounds in ascending order (ms). */
        val BUCKETS = longArrayOf(0L, 16L, 33L, 50L, 100L, 500L, 1000L, 5000L, 9999L)

        /**
         * Maps a duration in ms to the smallest bucket upper bound that is >= [durationMs].
         * Values above 5000 ms land in the overflow bucket (9999).
         */
        fun classifyBucket(durationMs: Long): Long {
            for (bound in BUCKETS) {
                if (durationMs <= bound) return bound
            }
            return BUCKETS.last()
        }

        fun epochMs(): Long = Clock.System.now().toEpochMilliseconds()

        val KNOWN_OPERATIONS = listOf(
            "frame_duration", "graph_load", "navigation", "search",
            "editor_input", "sql.select", "sql.insert", "sql.update", "sql.delete"
        )
    }
}

@kotlinx.serialization.Serializable
data class PercentileSummary(
    val operationName: String,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val sampleCount: Long
)
