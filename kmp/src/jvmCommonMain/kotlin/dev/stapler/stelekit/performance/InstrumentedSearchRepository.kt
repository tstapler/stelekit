package dev.stapler.stelekit.performance

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SearchRepository
import dev.stapler.stelekit.repository.SearchRequest
import dev.stapler.stelekit.repository.SearchResult
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

@OptIn(DirectRepositoryWrite::class)
class InstrumentedSearchRepository(
    private val delegate: SearchRepository,
    private val tracer: Tracer
) : SearchRepository {

    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> =
        delegate.searchBlocksByContent(query, limit, offset)

    override fun searchPagesByTitle(query: String, limit: Int): Flow<Either<DomainError, List<Page>>> =
        delegate.searchPagesByTitle(query, limit)

    override fun findBlocksReferencing(blockUuid: String): Flow<Either<DomainError, List<Block>>> =
        delegate.findBlocksReferencing(blockUuid)

    override fun searchWithFilters(searchRequest: SearchRequest): Flow<Either<DomainError, SearchResult>> {
        var startMs = 0L
        val span = tracer.spanBuilder("searchWithFilters").startSpan()
        return delegate.searchWithFilters(searchRequest)
            .onStart { startMs = HistogramWriter.epochMs() }
            .map { result ->
                span.setAttribute("ranking.visit_boost", "true")
                span.setAttribute("result.ranked.count", result.getOrNull()?.ranked?.size?.toLong() ?: 0L)
                result
            }
            .onCompletion {
                span.setAttribute("duration.ms", HistogramWriter.epochMs() - startMs)
                span.end()
            }
    }

    @DirectRepositoryWrite
    override suspend fun recordPageVisit(pageUuid: String): Either<DomainError, Unit> =
        delegate.recordPageVisit(pageUuid)

    @DirectRepositoryWrite
    override suspend fun rebuildFts(): Either<DomainError, Unit> =
        delegate.rebuildFts()

    @DirectRepositoryWrite
    override suspend fun integrityCheckFts(): Either<DomainError, Unit> =
        delegate.integrityCheckFts()
}
