package dev.stapler.stelekit.repository

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import arrow.core.Either
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.RestrictedTelemetryQueries
import dev.stapler.stelekit.db.TelemetryDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.performance.SerializedSpan
import dev.stapler.stelekit.performance.SpanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@OptIn(DirectRepositoryWrite::class)
class SqlDelightSpanRepository(
    private val database: TelemetryDatabase,
    writeMutex: Mutex = Mutex(),
) : SpanRepository {
    private val queries = database.telemetryQueries
    private val restricted = RestrictedTelemetryQueries(queries, writeMutex)
    private val json = Json { ignoreUnknownKeys = true }

    override fun getRecentSpans(limit: Int): Flow<Either<DomainError, List<SerializedSpan>>> =
        queries.selectRecentSpans(limit.toLong())
            .asDbFlowList(PlatformDispatcher.DB) { it.toSerializedSpan() }

    override suspend fun insertSpan(span: SerializedSpan) = insertSpans(listOf(span))

    override suspend fun insertSpans(spans: List<SerializedSpan>) {
        if (spans.isEmpty()) return
        withContext(PlatformDispatcher.DB) {
            restricted.withWriteLock {
                @OptIn(DirectSqlWrite::class)
                restricted.transaction {
                    for (span in spans) {
                        val attributesJson = json.encodeToString(
                            MapSerializer(String.serializer(), String.serializer()),
                            span.attributes
                        )
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
                            app_version = span.attributes["app.version"] ?: "",
                            commit_hash = span.attributes["app.commit"] ?: "",
                        )
                    }
                }
            }
        }
    }

    override suspend fun deleteSpansOlderThan(cutoffEpochMs: Long) {
        withContext(PlatformDispatcher.DB) {
            restricted.withWriteLock {
                @OptIn(DirectSqlWrite::class)
                restricted.deleteSpansOlderThan(cutoffEpochMs)
            }
        }
    }

    override suspend fun deleteExcessSpans(maxCount: Int) {
        withContext(PlatformDispatcher.DB) {
            restricted.withWriteLock {
                @OptIn(DirectSqlWrite::class)
                restricted.deleteExcessSpans(maxCount.toLong())
            }
        }
    }

    override suspend fun clear() {
        withContext(PlatformDispatcher.DB) {
            restricted.withWriteLock {
                @OptIn(DirectSqlWrite::class)
                restricted.deleteAllSpans()
            }
        }
    }

    private fun dev.stapler.stelekit.db.Spans.toSerializedSpan(): SerializedSpan {
        val attrs = try {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                attributes_json
            )
        } catch (e: CancellationException) {
            throw e
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

