package dev.stapler.stelekit.query

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockReadRepository
import dev.stapler.stelekit.repository.BlockSearchRepository
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/**
 * Turns a parsed [SimpleQuery] into a live `Flow` of matching blocks by composing the narrow
 * repository interfaces. Every returned Flow is driven by repository reads (no polling), so it
 * re-emits when the underlying data changes. Bound to one graph's repositories — a new
 * `RepositorySet` (graph switch) gets its own instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueryExecutor(
    private val blockSearchRepository: BlockSearchRepository,
    private val pageRepository: PageRepository,
    private val blockReadRepository: BlockReadRepository,
) {
    fun executeQuery(query: SimpleQuery): Flow<Either<DomainError, List<Block>>> =
        executeUnfiltered(query)
            // A query block would otherwise list itself (its own `[[Page]]`/`#tag` argument matches).
            .map { either -> either.map { blocks -> blocks.filterNot(::isQueryBlock).take(DEFAULT_LIMIT) } }
            // Re-emitting an equal list on every unrelated write would churn the UI.
            .distinctUntilChanged()
            // Merge/sort work shouldn't run on the collector's (UI) dispatcher.
            .flowOn(PlatformDispatcher.Default)
            // Throwable on purpose: an uncaught one here kills the process on Android.
            .catch { e ->
                if (e is CancellationException) throw e
                emit(DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left())
            }

    private fun isQueryBlock(block: Block): Boolean = queryMacroPrefix.containsMatchIn(block.content)

    private fun executeUnfiltered(query: SimpleQuery): Flow<Either<DomainError, List<Block>>> = when (query) {
        is QueryFilter -> executeFilter(query, FETCH_LIMIT)
        is Or -> combineBlocks(
            executeFilter(query.left, OPERAND_LIMIT),
            executeFilter(query.right, OPERAND_LIMIT),
        ) { a, b -> union(a, b) }.capped()
        is And -> executeAnd(query).capped()
    }

    // Cap is applied after the self-listing filter in executeQuery, so no early take here.
    private fun Flow<Either<DomainError, List<Block>>>.capped(): Flow<Either<DomainError, List<Block>>> =
        map { either -> either.map { it.take(FETCH_LIMIT) } }

    /** Combinator operands are fetched wider than the final cap so an intersection isn't starved. */
    private fun executeFilter(filter: QueryFilter, limit: Int): Flow<Either<DomainError, List<Block>>> = when (filter) {
        is QueryFilter.Task -> blockSearchRepository.findBlocksWithTaskMarker(filter.markers, limit, 0)
        is QueryFilter.PageRef -> blockSearchRepository.findReferencingBlocksReactive(filter.target, limit, 0)
        is QueryFilter.PageProperty -> pageRepository
            .getPagesWithProperty(filter.key, filter.value, limit, 0)
            .flatMapLatest { it.fold({ e -> flowOf(e.left()) }, { pages -> blocksOfPages(pages, limit) }) }
        is QueryFilter.Between -> executeBetween(filter, limit)
    }

    private fun executeAnd(query: And): Flow<Either<DomainError, List<Block>>> {
        val l = query.left
        val r = query.right
        return when {
            l is Not && r is Not -> flowOf(emptyList<Block>().right()) // no positive operand to subtract from
            l is Not -> combineBlocks(executeOperandFilter(r), executeFilter(l.filter, OPERAND_LIMIT)) { a, b -> difference(a, b) }
            r is Not -> combineBlocks(executeOperandFilter(l), executeFilter(r.filter, OPERAND_LIMIT)) { a, b -> difference(a, b) }
            else -> combineBlocks(executeOperandFilter(l), executeOperandFilter(r)) { a, b -> intersection(a, b) }
        }
    }

    private fun executeOperandFilter(operand: QueryOperand): Flow<Either<DomainError, List<Block>>> =
        when (operand) {
            is QueryFilter -> executeFilter(operand, OPERAND_LIMIT)
            is Not -> executeFilter(operand.filter, OPERAND_LIMIT) // unreachable: callers branch on Not first
        }

    private fun blocksOfPages(pages: List<Page>, limit: Int): Flow<Either<DomainError, List<Block>>> {
        if (pages.isEmpty()) return flowOf(emptyList<Block>().right())
        return combine(pages.map { blockReadRepository.getBlocksForPage(it.uuid) }) { results ->
            val merged = LinkedHashMap<String, Block>()
            for (r in results) {
                r.fold(
                    { return@combine it.left() },
                    // getOrPut, not putIfAbsent: the latter isn't in common stdlib (wasmJs).
                    { blocks -> blocks.forEach { b -> merged.getOrPut(b.uuid.value) { b } } },
                )
            }
            merged.values.take(limit).toList().right()
        }
    }

    private fun executeBetween(filter: QueryFilter.Between, limit: Int): Flow<Either<DomainError, List<Block>>> =
        // Boundary pages are looked up once; the journal-page scan below is what re-evaluates the range.
        combine(
            pageRepository.getPageByName(filter.startPage),
            pageRepository.getPageByName(filter.endPage),
        ) { start, end -> dateOf(filter.startPage, start) to dateOf(filter.endPage, end) }
            .flatMapLatest { (start, end) ->
                if (start == null || end == null) {
                    flowOf(emptyList<Block>().right())
                } else {
                    val from = if (start <= end) start else end
                    val to = if (start <= end) end else start
                    pageRepository.getJournalPages(JOURNAL_SCAN_LIMIT, 0).flatMapLatest { either ->
                        either.fold(
                            { e -> flowOf(e.left()) },
                            { pages ->
                                blocksOfPages(pages.filter { p -> p.journalDate?.let { it in from..to } == true }, limit)
                            },
                        )
                    }
                }
            }

    /** Journal date of a resolved page, or of an ISO-ish name (`2026_01_05` / `2026-01-05`) with no page yet. */
    private fun dateOf(name: String, page: Either<DomainError, Page?>): LocalDate? =
        page.getOrNull()?.journalDate
            ?: try {
                LocalDate.parse(name.replace('_', '-'))
            } catch (_: IllegalArgumentException) {
                null
            }

    private fun combineBlocks(
        a: Flow<Either<DomainError, List<Block>>>,
        b: Flow<Either<DomainError, List<Block>>>,
        merge: (List<Block>, List<Block>) -> List<Block>,
    ): Flow<Either<DomainError, List<Block>>> = combine(a, b) { ra, rb ->
        ra.flatMap { la -> rb.map { lb -> merge(la, lb) } }
    }

    private fun union(a: List<Block>, b: List<Block>): List<Block> =
        (a + b).distinctBy { it.uuid.value }.sortedByDescending { it.createdAt }

    private fun intersection(a: List<Block>, b: List<Block>): List<Block> {
        val keep = b.mapTo(HashSet()) { it.uuid.value }
        return a.filter { it.uuid.value in keep }
    }

    private fun difference(a: List<Block>, b: List<Block>): List<Block> {
        val drop = b.mapTo(HashSet()) { it.uuid.value }
        return a.filter { it.uuid.value !in drop }
    }

    companion object {
        private val queryMacroPrefix = Regex("""^\s*\{\{\s*query\s""", RegexOption.IGNORE_CASE)

        /** Repository-level fetch ceiling per filter. */
        const val DEFAULT_LIMIT = 200

        /** Fetched slightly above [DEFAULT_LIMIT] so dropping self-listing query blocks doesn't shorten a full page. */
        const val FETCH_LIMIT = DEFAULT_LIMIT + 16

        /** Per-operand fetch ceiling under and/or; the combined result is still capped at [DEFAULT_LIMIT]. */
        const val OPERAND_LIMIT = 1000

        /** Most-recent journal pages scanned per `between` query before the date-range filter runs. */
        const val JOURNAL_SCAN_LIMIT = 500
    }
}
