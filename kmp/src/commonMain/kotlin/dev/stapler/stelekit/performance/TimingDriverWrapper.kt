package dev.stapler.stelekit.performance

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.stapler.stelekit.util.UuidGenerator

/**
 * A [SqlDriver] decorator that records SQL query durations as child [SerializedSpan]s whenever
 * a [ActiveSpanContext] is active on the calling thread (via [CurrentSpanContext]).
 *
 * When no context is active (the common case), every delegation call is a zero-cost
 * passthrough to [delegate].
 *
 * System tables (schema_migrations, perf_histogram_buckets, spans, debug_flags) are
 * excluded from tracing to avoid noisy/circular self-instrumentation.
 */
class TimingDriverWrapper(
    private val delegate: SqlDriver,
    private val ringBuffer: RingBufferSpanExporter,
) : SqlDriver by delegate {

    private val excludedTables = setOf(
        "schema_migrations", "perf_histogram_buckets", "spans", "debug_flags",
        "histogram_buckets",
    )

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val ctx = CurrentSpanContext.get()
            ?: return delegate.execute(identifier, sql, parameters, binders)
        val (operation, table) = parseSql(sql)
        if (table in excludedTables)
            return delegate.execute(identifier, sql, parameters, binders)
        val startMs = HistogramWriter.epochMs()
        return try {
            val result = delegate.execute(identifier, sql, parameters, binders)
            recordSpan(ctx, "sql.$operation", table, startMs, "OK")
            result
        } catch (e: Exception) {
            recordSpan(ctx, "sql.$operation", table, startMs, "ERROR", e.message)
            throw e
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
            ?: return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        val (_, table) = parseSql(sql)
        if (table in excludedTables)
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        val startMs = HistogramWriter.epochMs()
        return try {
            val result = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
            recordSpan(ctx, "sql.select", table, startMs, "OK")
            result
        } catch (e: Exception) {
            recordSpan(ctx, "sql.select", table, startMs, "ERROR", e.message)
            throw e
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
        ringBuffer.record(
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
