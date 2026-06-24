package dev.stapler.stelekit.performance

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.flow.Flow

interface SpanRepository {
    fun getRecentSpans(limit: Int = 500): Flow<Either<DomainError, List<SerializedSpan>>>
    @DirectRepositoryWrite
    suspend fun insertSpan(span: SerializedSpan)
    @DirectRepositoryWrite
    suspend fun insertSpans(spans: List<SerializedSpan>)
    @DirectRepositoryWrite
    suspend fun deleteSpansOlderThan(cutoffEpochMs: Long)
    @DirectRepositoryWrite
    suspend fun deleteExcessSpans(maxCount: Int = 10_000)
    @DirectRepositoryWrite
    suspend fun clear()
}
