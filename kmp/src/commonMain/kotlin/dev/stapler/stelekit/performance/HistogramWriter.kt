package dev.stapler.stelekit.performance

import dev.stapler.stelekit.db.SteleDatabase
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
    scope: CoroutineScope
) {
    private val channel = Channel<HistogramSample>(capacity = Channel.BUFFERED)

    init {
        scope.launch(Dispatchers.IO) {
            for (sample in channel) {
                try {
                    val bucket = classifyBucket(sample.durationMs)
                    database.steleDatabaseQueries.transaction {
                        database.steleDatabaseQueries.insertHistogramBucketIfAbsent(
                            operation_name = sample.operationName,
                            bucket_ms = bucket,
                            recorded_at = sample.recordedAt
                        )
                        database.steleDatabaseQueries.incrementHistogramBucketCount(
                            recorded_at = sample.recordedAt,
                            operation_name = sample.operationName,
                            bucket_ms = bucket
                        )
                    }
                } catch (e: Exception) {
                    // A DB error must not kill the consumer coroutine — log and continue
                }
            }
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
