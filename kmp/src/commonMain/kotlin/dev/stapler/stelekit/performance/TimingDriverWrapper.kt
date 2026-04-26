package dev.stapler.stelekit.performance

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.stapler.stelekit.cache.PlatformLock
import dev.stapler.stelekit.cache.withLock
import dev.stapler.stelekit.util.UuidGenerator

/**
 * A [SqlDriver] decorator that records SQL query durations as child [SerializedSpan]s whenever
 * a [ActiveSpanContext] is active on the calling thread (via [CurrentSpanContext]).
 *
 * Stats collection via [statsCollector] is always-on (no span context required).
 * The span-to-ring-buffer path is opt-in: requires both a non-null [ringBuffer] and an active
 * [CurrentSpanContext].
 *
 * System tables (schema_migrations, perf_histogram_buckets, spans, debug_flags, query_stats) are
 * excluded from instrumentation to avoid noisy/circular self-instrumentation.
 */
class TimingDriverWrapper(
    private val delegate: SqlDriver,
    private val ringBuffer: RingBufferSpanExporter? = null,
    private val statsCollector: QueryStatsCollector? = null,
) : SqlDriver by delegate {

    // Cache parseSql results keyed by prepared-statement identifier.
    // Identifiers are stable (assigned at compile time), so after warm-up every call is a cache hit.
    private val parseCacheLock = PlatformLock()
    private val parseCache = HashMap<Int, Pair<String, String>>()

    private val excludedTables = setOf(
        "schema_migrations", "perf_histogram_buckets", "spans", "debug_flags",
        "histogram_buckets", "query_stats",
    )

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val ctx = CurrentSpanContext.get()
        if (ctx == null && statsCollector == null) return delegate.execute(identifier, sql, parameters, binders)
        val (operation, table) = parseSqlCached(identifier, sql)
        if (table in excludedTables)
            return delegate.execute(identifier, sql, parameters, binders)
        val startMs = HistogramWriter.epochMs()
        var isError = false
        return try {
            val result = delegate.execute(identifier, sql, parameters, binders)
            if (ctx != null && ringBuffer != null) recordSpan(ctx, "sql.$operation", table, startMs, "OK")
            result
        } catch (e: Exception) {
            isError = true
            if (ctx != null && ringBuffer != null) recordSpan(ctx, "sql.$operation", table, startMs, "ERROR", e.message)
            throw e
        } finally {
            statsCollector?.record(table, operation, HistogramWriter.epochMs() - startMs, isError)
        }
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val ctx = CurrentSpanContext.get()
        if (ctx == null && statsCollector == null) return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        val (_, table) = parseSqlCached(identifier, sql)
        if (table in excludedTables)
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        val startMs = HistogramWriter.epochMs()
        var isError = false
        return try {
            val result = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
            if (ctx != null && ringBuffer != null) recordSpan(ctx, "sql.select", table, startMs, "OK")
            result
        } catch (e: Exception) {
            isError = true
            if (ctx != null && ringBuffer != null) recordSpan(ctx, "sql.select", table, startMs, "ERROR", e.message)
            throw e
        } finally {
            statsCollector?.record(table, "select", HistogramWriter.epochMs() - startMs, isError)
        }
    }

    private fun recordSpan(
        ctx: ActiveSpanContext,
        name: String,
        table: String,
        startMs: Long,
        status: String,
        errorMsg: String? = null,
    ) {
        val endMs = HistogramWriter.epochMs()
        val attrs = buildMap {
            put("db.table", table)
            put("session.id", AppSession.id)
            if (errorMsg != null) put("error.message", errorMsg)
        }
        ringBuffer!!.record(
            SerializedSpan(
                name = name,
                startEpochMs = startMs,
                endEpochMs = endMs,
                durationMs = endMs - startMs,
                attributes = attrs,
                statusCode = status,
                traceId = ctx.traceId,
                spanId = UuidGenerator.generateV7(),
                parentSpanId = ctx.parentSpanId,
            )
        )
    }

    private fun parseSqlCached(identifier: Int?, sql: String): Pair<String, String> {
        if (identifier == null) return parseSql(sql)
        return parseCacheLock.withLock { parseCache[identifier] }
            ?: parseSql(sql).also { result -> parseCacheLock.withLock { parseCache[identifier] = result } }
    }

    private fun parseSql(sql: String): Pair<String, String> {
        val lower = sql.trimStart().lowercase()
        val operation = when {
            lower.startsWith("select") -> "select"
            lower.startsWith("insert") -> "insert"
            lower.startsWith("update") -> "update"
            lower.startsWith("delete") -> "delete"
            else -> "other"
        }
        return operation to extractTable(lower, operation)
    }

    private fun extractTable(sql: String, operation: String): String = try {
        when (operation) {
            "select" -> sql.substringAfter(" from ").trimStart().split(" ", "(").first()
            "insert" -> sql.substringAfter("into ").trimStart().split(" ", "(").first()
            "update" -> sql.substringAfter("update ").trimStart().split(" ", "(").first()
            "delete" -> sql.substringAfter(" from ").trimStart().split(" ", "(").first()
            else -> "unknown"
        }.trimEnd(',', ';', ')')
    } catch (_: Exception) {
        "unknown"
    }
}
