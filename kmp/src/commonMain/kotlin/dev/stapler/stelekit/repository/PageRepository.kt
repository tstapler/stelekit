package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Lightweight (name, isJournal) projection of a page row. Used by consumers that need
 * every page name (e.g. the suggestion index) without materializing full [Page] objects —
 * properties maps, file paths and timestamps stay in the database.
 */
data class PageNameEntry(val name: String, val isJournal: Boolean)

/**
 * There is deliberately NO `getAllPages()` on this interface. A reactive full-table flow
 * is re-materialized in its entirety on every write that invalidates it; standing
 * collectors of such a flow caused GC thrash and OutOfMemoryError on Android during
 * 8 000+ page graph imports. All reads are paginated, projected, or chunked:
 * - whole-graph one-shots (export, migration, tests): [getAllPagesSnapshot] — bounded batches
 * - whole-graph standing observers: [getPageNameEntries] — names-only projection
 * - UI lists: [getPages] / [getJournalPages] / [getFavoritePages] / point lookups
 * - reconcile existence checks: [getPagesByNames] / [getJournalPagesByDates] — chunked IN
 * - background indexing: [getUnloadedPages] (limit, offset) drain + [countUnloadedPages]
 */
interface PageRepository {
    fun getPageByUuid(uuid: PageUuid): Flow<Either<DomainError, Page?>>
    fun getPageByName(name: String): Flow<Either<DomainError, Page?>>
    fun getPagesInNamespace(namespace: String): Flow<Either<DomainError, List<Page>>>
    fun getPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>>
    fun searchPages(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>>
    fun getJournalPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>>

    /**
     * Find a journal page by its date. Format-agnostic — always use this instead of
     * getPageByName for journal pages to avoid duplicate creation from name format differences
     * (e.g. "2026_04_11" on disk vs "2026-04-11" created in-app).
     */
    fun getJournalPageByDate(date: kotlinx.datetime.LocalDate): Flow<Either<DomainError, Page?>>

    /** Section-scoped journal page lookup; defaults to global lookup. */
    fun getJournalPageByDateAndSection(
        date: kotlinx.datetime.LocalDate,
        sectionId: String,
    ): Flow<Either<DomainError, Page?>> = getJournalPageByDate(date)

    fun getRecentPages(limit: Int = 50): Flow<Either<DomainError, List<Page>>>

    /**
     * Favorite pages only — the bounded query standing UI observers (sidebar) must use.
     * SQL-backed repositories implement this with a dedicated `WHERE is_favorite = 1` query.
     */
    fun getFavoritePages(): Flow<Either<DomainError, List<Page>>>

    /**
     * Bounded batch of not-yet-content-loaded pages in stable (uuid) order — used by the
     * background-indexing drain loop. Callers re-fetch at a fixed limit and advance
     * [offset] only past rows that permanently fail, so peak memory is O(limit) rather
     * than O(graph).
     */
    fun getUnloadedPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>>

    /**
     * Section-scoped variant of [getUnloadedPages]. Only returns pages whose section_id is in
     * [sectionIds]. Always include "" in [sectionIds] to pick up global (no-section) pages.
     * Default delegates to [getUnloadedPages] (all sections) for non-SQL backends.
     */
    fun getUnloadedPagesBySection(
        sectionIds: Collection<String>,
        limit: Int,
        offset: Int,
    ): Flow<Either<DomainError, List<Page>>> = getUnloadedPages(limit, offset)

    /**
     * Count of not-yet-content-loaded pages — O(1) progress denominator for indexing.
     * Default drains [getUnloadedPages] in bounded batches; SQL-backed repositories
     * override with `SELECT COUNT(*)`.
     *
     * Suspend function — returns the current count. Not reactive; call again to re-check.
     */
    suspend fun countUnloadedPages(): Either<DomainError, Long> {
        var count = 0L
        var offset = 0
        while (true) {
            when (val batch = getUnloadedPages(SNAPSHOT_BATCH_SIZE, offset).first()) {
                is Either.Left -> return batch
                is Either.Right -> {
                    count += batch.value.size
                    if (batch.value.size < SNAPSHOT_BATCH_SIZE) return Either.Right(count)
                    offset += batch.value.size
                }
            }
        }
    }

    /**
     * Names-only projection of all pages for the suggestion index. Unlike a full-table
     * read, a standing observer of this flow materializes only (name, isJournal) pairs —
     * a few hundred KB on an 8 000-page graph instead of tens of MB of full Page objects.
     */
    fun getPageNameEntries(): Flow<Either<DomainError, List<PageNameEntry>>>

    /**
     * Pages whose names match [names] (case-insensitive). Bounded existence lookup used
     * by graph reconcile: one query per file chunk instead of preloading the whole table.
     * SQL implementations chunk the IN list to ≤500 to respect Android's
     * SQLITE_MAX_VARIABLE_NUMBER=999 on API < 30. Default scans in bounded batches.
     *
     * **Warning**: the default fallback calls [getAllPagesSnapshot], which materializes
     * the entire graph in memory. Implementations MUST override this with a bounded IN-clause
     * query — never rely on the default in production code.
     */
    suspend fun getPagesByNames(names: Collection<String>): Either<DomainError, List<Page>> {
        if (names.isEmpty()) return Either.Right(emptyList())
        val lower = names.mapTo(HashSet()) { it.lowercase() }
        return getAllPagesSnapshot().map { pages -> pages.filter { it.name.lowercase() in lower } }
    }

    /** Journal pages whose dates match [dates]. Bounded chunk lookup for reconcile. */
    suspend fun getJournalPagesByDates(
        dates: Collection<kotlinx.datetime.LocalDate>,
    ): Either<DomainError, List<Page>> {
        if (dates.isEmpty()) return Either.Right(emptyList())
        val dateSet = dates.toHashSet()
        return getAllPagesSnapshot().map { pages -> pages.filter { it.journalDate in dateSet } }
    }

    /**
     * One-shot whole-graph snapshot for export, migration tooling, benchmarks, and tests.
     * Reads in bounded batches of [batchSize] via [getPages] — never a single unbounded
     * query, and never a reactive flow that re-materializes per write. Callers that hold
     * the returned list accept O(graph) memory knowingly (whole-graph export/migration);
     * UI code must use the paginated/projected reads instead.
     */
    suspend fun getAllPagesSnapshot(batchSize: Int = SNAPSHOT_BATCH_SIZE): Either<DomainError, List<Page>> {
        val all = mutableListOf<Page>()
        var offset = 0
        while (true) {
            when (val batch = getPages(batchSize, offset).first()) {
                is Either.Left -> return batch
                is Either.Right -> {
                    all += batch.value
                    if (batch.value.size < batchSize) return Either.Right(all)
                    offset += batch.value.size
                }
            }
        }
    }

    @DirectRepositoryWrite
    suspend fun savePage(page: Page): Either<DomainError, Unit>

    /**
     * Save multiple pages in a single transaction.
     * Significantly faster than calling [savePage] in a loop during bulk loads.
     */
    @DirectRepositoryWrite
    suspend fun savePages(pages: List<Page>): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun toggleFavorite(pageUuid: PageUuid): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun renamePage(pageUuid: PageUuid, newName: String): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deletePage(pageUuid: PageUuid): Either<DomainError, Unit>

    fun countPages(): Flow<Either<DomainError, Long>>

    @DirectRepositoryWrite
    suspend fun clear()

    suspend fun cacheEvictAll() {}

    companion object {
        /** Batch size for snapshot/drain defaults — bounds per-fetch memory. */
        const val SNAPSHOT_BATCH_SIZE = 500
    }
}
