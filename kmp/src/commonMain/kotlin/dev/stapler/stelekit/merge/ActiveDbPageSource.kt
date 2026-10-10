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
 * [PageSource] over the active graph's repositories. Search is title FTS, paged
 * [SEARCH_HIT_PAGE] hits at a time and intersected with the filter by id (one chunked
 * query per hit page), so every repository call stays bounded however many pages match.
 * Deep offsets and counts cost O(matches / [SEARCH_HIT_PAGE]) calls, never O(graph) rows.
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
            scanMatches(filter, query, skip = offset.coerceAtLeast(0), take = size).bind()
        }
    }

    override suspend fun countPages(filter: SelectionFilter, search: String?): Either<DomainError, Long> {
        val query = search?.trim().orEmpty()
        if (query.isEmpty()) return pages.countPagesFiltered(filter)
        return scanMatches(filter, query, skip = 0, take = null).map { it.size.toLong() }
    }

    override suspend fun blockCounts(uuids: List<PageUuid>): Either<DomainError, Map<PageUuid, Int>> {
        require(uuids.size <= PageSource.MAX_PAGE_SIZE) { "blockCounts is bounded to ${PageSource.MAX_PAGE_SIZE} uuids" }
        return blocks.countBlocksForPages(uuids)
    }

    override suspend fun readPages(uuids: List<PageUuid>): Either<DomainError, List<SourcePage>> = either {
        require(uuids.size <= PageSource.MAX_PAGE_SIZE) { "readPages is bounded to ${PageSource.MAX_PAGE_SIZE} uuids" }
        uuids.mapNotNull { uuid ->
            val page = pages.getPageByUuid(uuid).first().bind() ?: return@mapNotNull null
            SourcePage(page, blocks.getBlocksForPage(uuid).first().bind())
        }
    }

    /**
     * Filter-passing hits in FTS rank order after dropping [skip]; stops after [take]
     * (null = all, used for counting). Each repository call returns <= [SEARCH_HIT_PAGE] rows.
     */
    private suspend fun scanMatches(
        filter: SelectionFilter,
        query: String,
        skip: Int,
        take: Int?,
    ): Either<DomainError, List<Page>> = either {
        val out = ArrayList<Page>()
        var toSkip = skip
        var hitOffset = 0
        while (take == null || out.size < take) {
            val hits = searchRepo.searchPagesByTitle(query, SEARCH_HIT_PAGE, hitOffset).first().bind()
            if (hits.isEmpty()) break
            val passing = pages.getPagesAmong(filter, hits.map { it.uuid }).bind().associateBy { it.uuid }
            for (hit in hits) {
                val page = passing[hit.uuid] ?: continue
                if (toSkip > 0) { toSkip--; continue }
                if (take != null && out.size >= take) break
                out.add(page)
            }
            if (hits.size < SEARCH_HIT_PAGE) break
            hitOffset += hits.size
        }
        out
    }

    companion object {
        const val SEARCH_HIT_PAGE = PageSource.MAX_PAGE_SIZE
    }
}
