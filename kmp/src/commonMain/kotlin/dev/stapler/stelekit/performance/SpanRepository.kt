package dev.stapler.stelekit.performance

import kotlinx.coroutines.flow.Flow

interface SpanRepository {
    fun getRecentSpans(limit: Int = 500): Flow<List<SerializedSpan>>
    suspend fun insertSpan(span: SerializedSpan)
    suspend fun deleteSpansOlderThan(cutoffEpochMs: Long)
    suspend fun deleteExcessSpans(maxCount: Int = 10_000)
    suspend fun clear()
}
