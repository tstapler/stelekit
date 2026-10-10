package dev.stapler.stelekit.query

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockReadRepository
import dev.stapler.stelekit.repository.BlockSearchRepository
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    fun executeQuery(query: SimpleQuery): Flow<Either<DomainError, List<Block>>> = when (query) {
        is QueryFilter -> executeFilter(query)
        is Or -> combineBlocks(executeFilter(query.left), executeFilter(query.right)) { a, b -> union(a, b) }
        is And -> executeAnd(query)
    }

    private fun executeFilter(filter: QueryFilter): Flow<Either<DomainError, List<Block>>> = when (filter) {
        is QueryFilter.Task -> blockSearchRepository.findBlocksWithTaskMarker(filter.markers, DEFAULT_LIMIT, 0)
        is QueryFilter.PageRef -> blockSearchRepository.findReferencingBlocksReactive(filter.target, DEFAULT_LIMIT, 0)
        is QueryFilter.PageProperty -> pageRepository
            .getPagesWithProperty(filter.key, filter.value, DEFAULT_LIMIT, 0)
            .flatMapLatest { it.fold({ e -> flowOf(e.left()) }, ::blocksOfPages) }
        is QueryFilter.Between -> executeBetween(filter)
    }

    private fun executeAnd(query: And): Flow<Either<DomainError, List<Block>>> {
        val l = query.left
        val r = query.right
        return when {
            l is Not && r is Not -> flowOf(emptyList<Block>().right()) // no positive operand to subtract from
            l is Not -> combineBlocks(executeOperandFilter(r), executeFilter(l.filter)) { a, b -> difference(a, b) }
            r is Not -> combineBlocks(executeOperandFilter(l), executeFilter(r.filter)) { a, b -> difference(a, b) }
            else -> combineBlocks(executeOperandFilter(l), executeOperandFilter(r)) { a, b -> intersection(a, b) }
        }
    }

    private fun executeOperandFilter(operand: QueryOperand): Flow<Either<DomainError, List<Block>>> =
        when (operand) {
            is QueryFilter -> executeFilter(operand)
            is Not -> executeFilter(operand.filter) // unreachable: callers branch on Not first
        }

    private fun blocksOfPages(pages: List<Page>): Flow<Either<DomainError, List<Block>>> {
        if (pages.isEmpty()) return flowOf(emptyList<Block>().right())
        return combine(pages.map { blockReadRepository.getBlocksForPage(it.uuid) }) { results ->
            val merged = LinkedHashMap<String, Block>()
            for (r in results) {
                val blocks = r.getOrNull() ?: return@combine (r.swap().getOrNull()!!).left()
                blocks.forEach { merged.putIfAbsent(it.uuid.value, it) }
            }
            merged.values.toList().right()
        }
    }

    private fun executeBetween(filter: QueryFilter.Between): Flow<Either<DomainError, List<Block>>> = flow {
        val start = resolveJournalDate(filter.startPage)
        val end = resolveJournalDate(filter.endPage)
        if (start == null || end == null) {
            emit(emptyList<Block>().right())
            return@flow
        }
        val from = if (start <= end) start else end
        val to = if (start <= end) end else start
        emitAll(
            pageRepository.getJournalPages(JOURNAL_SCAN_LIMIT, 0).flatMapLatest { either ->
                either.fold(
                    { e -> flowOf(e.left()) },
                    { pages ->
                        blocksOfPages(pages.filter { p -> p.journalDate?.let { it in from..to } == true })
                    },
                )
            }
        )
    }

    private suspend fun resolveJournalDate(name: String): LocalDate? {
        pageRepository.getPageByName(name).first().getOrNull()?.let { page ->
            return page.journalDate
        }
        return runCatching { LocalDate.parse(name.replace('_', '-')) }.getOrNull()
    }

    private fun combineBlocks(
        a: Flow<Either<DomainError, List<Block>>>,
        b: Flow<Either<DomainError, List<Block>>>,
        merge: (List<Block>, List<Block>) -> List<Block>,
    ): Flow<Either<DomainError, List<Block>>> = combine(a, b) { ra, rb ->
        ra.flatMap { la -> rb.map { lb -> merge(la, lb) } }
    }

    private fun union(a: List<Block>, b: List<Block>): List<Block> = (a + b).distinctBy { it.uuid.value }

    private fun intersection(a: List<Block>, b: List<Block>): List<Block> {
        val keep = b.mapTo(HashSet()) { it.uuid.value }
        return a.filter { it.uuid.value in keep }
    }

    private fun difference(a: List<Block>, b: List<Block>): List<Block> {
        val drop = b.mapTo(HashSet()) { it.uuid.value }
        return a.filter { it.uuid.value !in drop }
    }

    companion object {
        /** Repository-level fetch ceiling per filter. */
        const val DEFAULT_LIMIT = 200

        /** Most-recent journal pages scanned per `between` query before the date-range filter runs. */
        const val JOURNAL_SCAN_LIMIT = 500
    }
}
