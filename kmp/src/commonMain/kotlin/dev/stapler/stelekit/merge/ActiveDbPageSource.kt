package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.SearchRepository
import kotlinx.coroutines.flow.first

/**
 * [PageSource] over the active graph's repositories. Search is title FTS: the top
 * [SEARCH_HIT_CAP] hits are intersected with the filter by id (one chunked query), so a
 * search surfaces at most that many pages — a deliberate bound, not an O(graph) scan.
 */
class ActiveDbPageSource(
    private val pages: PageRepository,
    private val blocks: BlockRepository,
    private val searchRepo: SearchRepository,
) : PageSource {

    override suspend fun listPages(
        filter: SelectionFilter,
        search: String?,
        limit: Int,
        offset: Int,
    ): Either<DomainError, List<Page>> = either {
        val size = limit.coerceIn(0, PageSource.MAX_PAGE_SIZE)
        val query = search?.trim().orEmpty()
        if (query.isEmpty()) {
            pages.getPagesFiltered(filter, size, offset).first().bind()
        } else {
            // Bounded in memory: <= SEARCH_HIT_CAP FTS hits, not a SQL result set.
            val matches = searchMatches(filter, query).bind()
            val from = offset.coerceIn(0, matches.size)
            matches.subList(from, (from + size).coerceAtMost(matches.size))
        }
    }

    override suspend fun countPages(filter: SelectionFilter, search: String?): Either<DomainError, Long> {
        val query = search?.trim().orEmpty()
        if (query.isEmpty()) return pages.countPagesFiltered(filter)
        return searchMatches(filter, query).map { it.size.toLong() }
    }

    override suspend fun readPages(uuids: List<PageUuid>): Either<DomainError, List<SourcePage>> = either {
        require(uuids.size <= PageSource.MAX_PAGE_SIZE) { "readPages is bounded to ${PageSource.MAX_PAGE_SIZE} uuids" }
        uuids.mapNotNull { uuid ->
            val page = pages.getPageByUuid(uuid).first().bind() ?: return@mapNotNull null
            SourcePage(page, blocks.getBlocksForPage(uuid).first().bind())
        }
    }

    /** Filter-passing search hits in FTS rank order (<= [SEARCH_HIT_CAP]). */
    private suspend fun searchMatches(filter: SelectionFilter, query: String): Either<DomainError, List<Page>> = either {
        val hits = searchRepo.searchPagesByTitle(query, SEARCH_HIT_CAP).first().bind()
        if (hits.isEmpty()) return@either emptyList()
        val passing = pages.getPagesAmong(filter, hits.map { it.uuid }).bind().associateBy { it.uuid }
        hits.mapNotNull { passing[it.uuid] }
    }

    companion object {
        const val SEARCH_HIT_CAP = PageSource.MAX_PAGE_SIZE
    }
}
