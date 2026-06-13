package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.flow.Flow

interface SearchRepository {
    fun searchBlocksByContent(query: String, limit: Int = 50, offset: Int = 0): Flow<Either<DomainError, List<Block>>>
    fun searchPagesByTitle(query: String, limit: Int = 20): Flow<Either<DomainError, List<Page>>>
    fun findBlocksReferencing(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>>
    fun searchWithFilters(searchRequest: SearchRequest): Flow<Either<DomainError, SearchResult>>

    /**
     * Records a navigation to [pageUuid]. Fire-and-forget from the ViewModel.
     * Used to build visit-recency ranking signal.
     * Must execute on [PlatformDispatcher.DB].
     */
    @DirectRepositoryWrite
    suspend fun recordPageVisit(pageUuid: PageUuid): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun rebuildFts(): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun integrityCheckFts(): Either<DomainError, Unit>
}

enum class SearchScope {
    ALL,
    PAGES_ONLY,
    BLOCKS_ONLY,
    CURRENT_PAGE,
    JOURNAL,
    FAVORITES
}

enum class DataType {
    TITLES,
    CONTENT,
    PROPERTIES,
    BACKLINKS
}

data class SearchRequest(
    val query: String? = null,
    val pageUuid: PageUuid? = null,
    val scope: SearchScope = SearchScope.ALL,
    val dataTypes: Set<DataType> = setOf(DataType.TITLES, DataType.CONTENT),
    val propertyFilters: Map<String, String> = emptyMap(),
    val dateRange: DateRange? = null,
    val limit: Int = 50,
    val offset: Int = 0
)

data class DateRange(
    val startDate: kotlin.time.Instant? = null,
    val endDate: kotlin.time.Instant? = null
)

data class SearchedPage(
    val page: Page,
    val snippet: String? = null,
    val bm25Score: Double = 0.0,
    val backlinkCount: Int = 0
)

data class SearchedBlock(
    val block: Block,
    val snippet: String? = null,
    val bm25Score: Double = 0.0
)

/**
 * A search hit carrying an absolute relevance score suitable for cross-type ranking.
 *
 * BM25 returns negative values (more negative = more relevant). [score] is the
 * absolute value, optionally multiplied by a page-title boost so they sort above body-text hits.
 */
sealed class RankedSearchHit {
    abstract val score: Double
    data class PageHit(val page: Page, val snippet: String?, override val score: Double) : RankedSearchHit()
    data class BlockHit(val block: Block, val snippet: String?, override val score: Double) : RankedSearchHit()
}

data class SearchResult(
    val blocks: List<Block>,
    val pages: List<Page>,
    val searchedBlocks: List<SearchedBlock> = emptyList(),
    val searchedPages: List<SearchedPage> = emptyList(),
    val ranked: List<RankedSearchHit> = emptyList(),
    val totalCount: Int,
    val hasMore: Boolean
)
