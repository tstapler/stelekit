package dev.stapler.stelekit.performance


import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedTelemetryQueries
import dev.stapler.stelekit.db.TelemetryDatabase
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

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
    private val database: TelemetryDatabase,
    scope: CoroutineScope,
    // writeActor unused — telemetry DB uses poolSize=1 so no WAL snapshot contention.
    // Kept to avoid breaking callers that pass it; ignored internally.
    @Suppress("UNUSED_PARAMETER") writeActor: DatabaseWriteActor? = null,
    writeMutex: Mutex = Mutex(),
) {
    private val channel = Channel<HistogramSample>(capacity = Channel.BUFFERED)
    private val restricted = RestrictedTelemetryQueries(database.telemetryQueries, writeMutex)

    init {
        scope.launch(PlatformDispatcher.DB) {
            for (first in channel) {
                // Drain all buffered samples before touching the DB — single transaction per burst.
                val batch = mutableListOf(first)
                var next = channel.tryReceive()
                while (next.isSuccess) {
                    batch.add(next.getOrThrow())
                    next = channel.tryReceive()
                }
                processBatch(batch)
            }
        }
    }

    @OptIn(DirectSqlWrite::class)
    private suspend fun processBatch(samples: List<HistogramSample>) {
        // Coalesce: accumulate counts per (operationName, bucket) before writing.
        // Key: operationName to bucket_ms. Value: count to earliest recordedAt.
        val coalesced = LinkedHashMap<Pair<String, Long>, Pair<Long, Long>>()
        for (sample in samples) {
            val bucket = classifyBucket(sample.durationMs)
            val key = sample.operationName to bucket
            val existing = coalesced[key]
            if (existing == null) {
                coalesced[key] = 1L to sample.recordedAt
            } else {
                coalesced[key] = (existing.first + 1L) to minOf(existing.second, sample.recordedAt)
            }
        }
        try {
            restricted.withWriteLock {
                restricted.transaction {
                    for ((key, accum) in coalesced) {
                        val (operationName, bucket) = key
                        val (count, recordedAt) = accum
                        restricted.insertHistogramBucketIfAbsent(
                            operation_name = operationName,
                            bucket_ms = bucket,
                            recorded_at = recordedAt
                        )
                        restricted.incrementHistogramBucketCountBy(
                            delta = count,
                            recorded_at = recordedAt,
                            operation_name = operationName,
                            bucket_ms = bucket
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A DB error must not kill the consumer coroutine — log and continue
        }
    }

    /**
     * Record a latency sample. Non-blocking: if the channel is full the sample is dropped silently.
     */
    fun record(operationName: String, durationMs: Long, recordedAt: Long = epochMs()) {
        channel.trySend(HistogramSample(operationName, durationMs, recordedAt))
    }

    /** Returns all operation names that have at least one recorded sample. */
    fun queryAllOperations(): List<String> =
        database.telemetryQueries.selectAllHistogramOperations().executeAsList()

    /**
     * Returns all histogram percentiles in a single DB round-trip (vs. N+1 in
     * [queryAllOperations] + [queryPercentiles]).  Used by [PerfExporter] to avoid
     * O(operations) sequential queries during export.
     */
    fun queryAllPercentilesForExport(): Map<String, PercentileSummary> {
        val rows = database.telemetryQueries.selectAllHistogramBuckets().executeAsList()
        if (rows.isEmpty()) return emptyMap()
        return rows
            .groupBy { it.operation_name }
            .mapNotNull { (opName, opRows) ->
                val totalCount = opRows.sumOf { it.count }
                if (totalCount == 0L) return@mapNotNull null
                fun percentileMs(pct: Double): Long {
                    val target = (totalCount * pct).toLong().coerceAtLeast(1L)
                    var cumulative = 0L
                    for (row in opRows) {
                        cumulative += row.count
                        if (cumulative >= target) return row.bucket_ms
                    }
                    return opRows.last().bucket_ms
                }
                opName to PercentileSummary(
                    operationName = opName,
                    p50Ms = percentileMs(0.50),
                    p95Ms = percentileMs(0.95),
                    p99Ms = percentileMs(0.99),
                    sampleCount = totalCount,
                )
            }
            .toMap()
    }

    /**
     * Query approximate percentiles for [operationName] from the stored buckets.
     * Returns null if no data is available for the operation.
     */
    fun queryPercentiles(operationName: String): PercentileSummary? {
        val rows = database.telemetryQueries.selectHistogramForOperation(operationName).executeAsList()
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
            // UI responsiveness
            "frame_duration", "navigation", "search", "editor_input",
            // Graph loading pipeline
            "graph_load", "graph_load.phase1_journals", "graph_load.progressive",
            "indexRemainingPages", "loadDirectory", "loadFullPage",
            // Per-file parsing (the hot path for page navigation)
            "parseAndSavePage", "parse.markdown", "parse.processBlocks", "diff",
            // File I/O
            "file.read",
            // Database reads
            "db.lookupPage", "db.getBlocks",
            // Database writes
            "db.savePage", "db.saveBlocks", "db.queue_wait",
            // Database read queue instrumentation
            "db.read_queue_wait", "db.read_queue_depth", "db.write_queue_depth",
            // SQL driver (always-on via TimingDriverWrapper)
            "sql.select", "sql.insert", "sql.update", "sql.delete",
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
