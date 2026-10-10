package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid

/** A source page with its flat block list, as stored. */
class SourcePage(val page: Page, val blocks: List<Block>) {
    fun toMergePage(): MergePage = MergeConverters.toMergePage(page, blocks)
}

/** A selected source page [PageSource.readPages] could not return (file gone, unparsable, too large). */
data class UnreadablePage(val name: String, val error: DomainError)

/**
 * Where the copy picker and merge read source pages from. Every call is bounded: listing
 * returns at most [MAX_PAGE_SIZE] rows, counts come from a count query, and page reads
 * touch at most [MAX_PAGE_SIZE] pages.
 */
interface PageSource {
    /** One page of the pages matching [filter] and (optional) title [search], in name order. */
    suspend fun listPages(
        filter: SelectionFilter,
        search: String?,
        limit: Int,
        offset: Int,
    ): Either<DomainError, List<Page>>

    /** Total number of pages [listPages] can return for the same [filter] and [search]. */
    suspend fun countPages(filter: SelectionFilter, search: String?): Either<DomainError, Long>

    /** Pages (in [uuids] order, missing ones omitted) with their blocks; <= [MAX_PAGE_SIZE] uuids. */
    suspend fun readPages(uuids: List<PageUuid>): Either<DomainError, List<SourcePage>>

    /**
     * Selected pages the last [readPages] calls skipped as unreadable, cleared by this call. DB sources
     * never have any; the file-backed pull source reports them so the plan counts them instead of dropping them.
     */
    fun takeUnreadable(): List<UnreadablePage> = emptyList()

    companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
