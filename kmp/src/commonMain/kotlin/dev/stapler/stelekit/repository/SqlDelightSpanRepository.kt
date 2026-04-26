package dev.stapler.stelekit.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.performance.SerializedSpan
import dev.stapler.stelekit.performance.SpanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@OptIn(DirectRepositoryWrite::class)
class SqlDelightSpanRepository(
    private val database: SteleDatabase,
) : SpanRepository {
    private val queries = database.steleDatabaseQueries
    private val restricted = RestrictedDatabaseQueries(queries)
    private val json = Json { ignoreUnknownKeys = true }

    override fun getRecentSpans(limit: Int): Flow<List<SerializedSpan>> =
        queries.selectRecentSpans(limit.toLong())
            .asFlow()
            .mapToList(PlatformDispatcher.DB)
            .map { rows -> rows.map { row -> row.toSerializedSpan() } }

    override suspend fun insertSpan(span: SerializedSpan) {
        val attributesJson = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            span.attributes
        )
        withContext(PlatformDispatcher.DB) {
            @OptIn(DirectSqlWrite::class)
            restricted.insertSpan(
                trace_id = span.traceId,
                span_id = span.spanId,
                parent_span_id = span.parentSpanId,
                name = span.name,
                start_epoch_ms = span.startEpochMs,
                end_epoch_ms = span.endEpochMs,
                duration_ms = span.durationMs,
                attributes_json = attributesJson,
                status_code = span.statusCode,
            )
        }
    }

    override suspend fun deleteSpansOlderThan(cutoffEpochMs: Long) {
        withContext(PlatformDispatcher.DB) {
            @OptIn(DirectSqlWrite::class)
            restricted.deleteSpansOlderThan(cutoffEpochMs)
        }
    }

    override suspend fun deleteExcessSpans(maxCount: Int) {
        withContext(PlatformDispatcher.DB) {
            @OptIn(DirectSqlWrite::class)
            restricted.deleteExcessSpans(maxCount.toLong())
        }
    }

    override suspend fun clear() {
        withContext(PlatformDispatcher.DB) {
            @OptIn(DirectSqlWrite::class)
            restricted.deleteAllSpans()
        }
    }

    private fun dev.stapler.stelekit.db.Spans.toSerializedSpan(): SerializedSpan {
        val attrs = try {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                attributes_json
            )
        } catch (_: Exception) {
            emptyMap()
        }
        return SerializedSpan(
            name = name,
            traceId = trace_id,
            spanId = span_id,
            parentSpanId = parent_span_id,
            startEpochMs = start_epoch_ms,
            endEpochMs = end_epoch_ms,
            durationMs = duration_ms,
            attributes = attrs,
            statusCode = status_code,
        )
    }
}
