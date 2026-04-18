package dev.stapler.stelekit.performance

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.SearchRepository
import dev.stapler.stelekit.repository.SearchRequest
import dev.stapler.stelekit.repository.SearchResult
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.flow.Flow

class InstrumentedSearchRepository(
    private val delegate: SearchRepository,
    private val tracer: Tracer
) : SearchRepository {

    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Result<List<Block>>> =
        delegate.searchBlocksByContent(query, limit, offset)

    override fun searchPagesByTitle(query: String, limit: Int): Flow<Result<List<Page>>> =
        delegate.searchPagesByTitle(query, limit)

    override fun findBlocksReferencing(blockUuid: String): Flow<Result<List<Block>>> =
        delegate.findBlocksReferencing(blockUuid)

    override fun searchWithFilters(searchRequest: SearchRequest): Flow<Result<SearchResult>> =
        delegate.searchWithFilters(searchRequest)
}
