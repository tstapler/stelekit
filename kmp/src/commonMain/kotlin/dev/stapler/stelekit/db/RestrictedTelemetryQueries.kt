package dev.stapler.stelekit.db

import app.cash.sqldelight.SuspendingTransactionWithoutReturn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps [TelemetryQueries] and gates every mutating method behind [DirectSqlWrite].
 *
 * Mirrors [RestrictedDatabaseQueries] for the telemetry database.
 * Callers that only read may use [TelemetryQueries] directly via [TelemetryDatabase.telemetryQueries].
 *
 * ## Write serialization
 *
 * The telemetry DB uses a single connection ([poolSize]=1 on JVM). Multiple coroutines
 * ([HistogramWriter], drain loop, [QueryStatsCollector]) write concurrently — without serialization
 * SQLite issues [SQLITE_BUSY] which silently drops samples. All write call sites must wrap their
 * operations in [withWriteLock] to serialize at the Kotlin level before touching SQLite.
 */
class RestrictedTelemetryQueries(
    private val queries: TelemetryQueries,
    private val mutex: Mutex = Mutex(),
) {
    val rawQueries: TelemetryQueries get() = queries

    /** Serializes all writes to the telemetry DB. All write call sites must use this. */
    suspend fun withWriteLock(block: suspend () -> Unit) = mutex.withLock { block() }

    @DirectSqlWrite
    suspend fun transaction(noEnclosing: Boolean = false, body: suspend SuspendingTransactionWithoutReturn.() -> Unit) =
        queries.transaction(noEnclosing, body)

    // ── Histogram writes ──────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertHistogramBucketIfAbsent(operation_name: String, bucket_ms: Long, recorded_at: Long): Long =
        queries.insertHistogramBucketIfAbsent(operation_name, bucket_ms, recorded_at)

    @DirectSqlWrite
    suspend fun incrementHistogramBucketCount(recorded_at: Long, operation_name: String, bucket_ms: Long): Long =
        queries.incrementHistogramBucketCount(recorded_at, operation_name, bucket_ms)

    @DirectSqlWrite
    suspend fun deleteOldHistogramRows(recorded_at: Long): Long =
        queries.deleteOldHistogramRows(recorded_at)

    // ── Debug flag writes ─────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun upsertDebugFlag(key: String, value_: Long, updated_at: Long): Long =
        queries.upsertDebugFlag(key, value_, updated_at)

    // ── Span writes ───────────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertSpan(
        trace_id: String,
        span_id: String,
        parent_span_id: String,
        name: String,
        start_epoch_ms: Long,
        end_epoch_ms: Long,
        duration_ms: Long,
        attributes_json: String,
        status_code: String,
        app_version: String,
        commit_hash: String,
    ): Long = queries.insertSpan(
        trace_id, span_id, parent_span_id, name, start_epoch_ms, end_epoch_ms,
        duration_ms, attributes_json, status_code, app_version, commit_hash,
    )

    @DirectSqlWrite
    suspend fun deleteSpansOlderThan(end_epoch_ms: Long): Long =
        queries.deleteSpansOlderThan(end_epoch_ms)

    @DirectSqlWrite
    suspend fun deleteExcessSpans(limit: Long): Long =
        queries.deleteExcessSpans(limit)

    @DirectSqlWrite
    suspend fun deleteAllSpans(): Long =
        queries.deleteAllSpans()

    // ── Query stats writes ────────────────────────────────────────────────────

    @DirectSqlWrite
    suspend fun insertQueryStatIfAbsent(
        app_version: String,
        table_name: String,
        operation: String,
        first_seen: Long,
        last_seen: Long,
    ): Long = queries.insertQueryStatIfAbsent(app_version, table_name, operation, first_seen, last_seen)

    @DirectSqlWrite
    suspend fun mergeQueryStat(
        calls: Long, errors: Long, total_ms: Long,
        min_ms: Long, max_ms: Long,
        b1: Long, b5: Long, b16: Long, b50: Long, b100: Long, b500: Long, b_inf: Long,
        last_seen: Long,
        app_version: String, table_name: String, operation: String,
    ): Long = queries.mergeQueryStat(
        calls, errors, total_ms, min_ms, max_ms,
        b1, b5, b16, b50, b100, b500, b_inf,
        last_seen, app_version, table_name, operation,
    )

    @DirectSqlWrite
    suspend fun deleteQueryStatsForVersion(app_version: String): Long =
        queries.deleteQueryStatsForVersion(app_version)
}
