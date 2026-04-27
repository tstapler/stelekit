package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.performance.ActiveSpanContext
import dev.stapler.stelekit.performance.AppSession
import dev.stapler.stelekit.performance.CurrentSpanContext
import dev.stapler.stelekit.performance.HistogramWriter
import dev.stapler.stelekit.performance.RingBufferSpanExporter
import dev.stapler.stelekit.performance.SpanEmitter
import dev.stapler.stelekit.search.FtsQueryBuilder
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.abs
import kotlin.math.exp

/**
 * SQLDelight implementation of SearchRepository.
 *
 * Block content is searched via FTS5 with BM25 ranking.
 * Page names are searched via FTS5 (pages_fts) with BM25 ranking.
 * Queries are built by [FtsQueryBuilder] which handles phrase search and multi-token AND (with OR fallback).
 * Page-title hits are boosted by [PAGE_BOOST] relative to block-content hits in [ranked] results.
 */
class SqlDelightSearchRepository(
    private val database: SteleDatabase,
    private val histogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = null,
    ringBuffer: RingBufferSpanExporter? = null,
) : SearchRepository {

    private val queries = database.steleDatabaseQueries
    private val spanEmitter = SpanEmitter(ringBuffer)

    companion object {
        /** Page-title hits are multiplied by this factor before ranking against block hits. */
        const val PAGE_BOOST = 5.0
        /** Results on a page directly linked to/from the current page get this multiplier. */
        const val GRAPH_BOOST = 3.0
        /** Recency half-life in days: a result edited this many days ago gets half the recency bonus. */
        const val RECENCY_HALFLIFE_DAYS = 14.0
    }

    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            val ftsQuery = FtsQueryBuilder.build(query)
            if (ftsQuery.isEmpty()) { emit(emptyList<Block>().right()); return@flow }
            val startMs = HistogramWriter.epochMs()
            val traceId = UuidGenerator.generateV7()
            val spanId = UuidGenerator.generateV7()
            CurrentSpanContext.set(ActiveSpanContext(traceId, spanId))
            val results = try {
                val andResults = queries.searchBlocksByContentFts(
                    query = ftsQuery,
                    limit = limit.toLong(),
                    offset = offset.toLong()
                ).executeAsList()
                if (andResults.isNotEmpty()) {
                    andResults.map { it.toBlockModel() }
                } else {
                    val orQuery = FtsQueryBuilder.buildOr(query)
                    if (orQuery.isEmpty()) emptyList()
                    else queries.searchBlocksByContentFts(
                        query = orQuery,
                        limit = limit.toLong(),
                        offset = offset.toLong()
                    ).executeAsList().map { it.toBlockModel() }
                }
            } finally {
                CurrentSpanContext.set(null)
            }
            val durationMs = HistogramWriter.epochMs() - startMs
            histogramWriter?.record("search", durationMs)
            spanEmitter.emit(
                name = "search.blocks",
                startMs = startMs,
                traceId = traceId,
                parentSpanId = spanId,
                attrs = mapOf(
                    "query.terms" to ftsQuery.length.toString(),
                    "result.count" to results.size.toString(),
                    "duration.ms" to durationMs.toString(),
                )
            )
            emit(results.right())
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun searchPagesByTitle(query: String, limit: Int): Flow<Either<DomainError, List<Page>>> = flow {
        try {
            val ftsQuery = FtsQueryBuilder.build(query)
            if (ftsQuery.isEmpty()) { emit(emptyList<Page>().right()); return@flow }
            val startMs = HistogramWriter.epochMs()
            val traceId = UuidGenerator.generateV7()
            val spanId = UuidGenerator.generateV7()
            CurrentSpanContext.set(ActiveSpanContext(traceId, spanId))
            val results = try {
                try {
                    queries.searchPagesByNameFts(query = ftsQuery, limit = limit.toLong())
                        .executeAsList().map { it.toPageModel() }
                } catch (_: Exception) {
                    queries.selectPagesByNameLike("%$query%").executeAsList().take(limit).map { it.toPageModel() }
                }
            } finally {
                CurrentSpanContext.set(null)
            }
            val durationMs = HistogramWriter.epochMs() - startMs
            histogramWriter?.record("search", durationMs)
            spanEmitter.emit(
                name = "search.pages",
                startMs = startMs,
                traceId = traceId,
                parentSpanId = spanId,
                attrs = mapOf(
                    "result.count" to results.size.toString(),
                    "duration.ms" to durationMs.toString(),
                )
            )
            emit(results.right())
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun findBlocksReferencing(blockUuid: String): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            val results = queries.selectBlocksReferencing(blockUuid)
                .executeAsList()
                .map { it.toBlockModel() }
            emit(results.right())
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun searchWithFilters(searchRequest: SearchRequest): Flow<Either<DomainError, SearchResult>> = flow {
        try {
            val rawQuery = searchRequest.query
            if (rawQuery.isNullOrBlank()) {
                emit(SearchResult(
                    blocks = emptyList(),
                    pages = emptyList(),
                    totalCount = 0,
                    hasMore = false
                ).right())
                return@flow
            }

            val ftsQuery = FtsQueryBuilder.build(rawQuery)
            if (ftsQuery.isEmpty()) {
                emit(SearchResult(
                    blocks = emptyList(),
                    pages = emptyList(),
                    totalCount = 0,
                    hasMore = false
                ).right())
                return@flow
            }

            val scope = searchRequest.scope
            val dataTypes = searchRequest.dataTypes

            // ── Page search ────────────────────────────────────────────────
            val searchedPages: List<SearchedPage> = if (
                scope != SearchScope.BLOCKS_ONLY &&
                DataType.TITLES in dataTypes
            ) {
                try {
                    val andPages = queries.searchPagesByNameFts(
                        query = ftsQuery,
                        limit = searchRequest.limit.toLong()
                    ).executeAsList()
                    val pageRows = if (andPages.isNotEmpty()) {
                        andPages
                    } else {
                        val orQuery = FtsQueryBuilder.buildOr(rawQuery)
                        if (orQuery.isEmpty()) emptyList()
                        else queries.searchPagesByNameFts(
                            query = orQuery,
                            limit = searchRequest.limit.toLong()
                        ).executeAsList()
                    }
                    pageRows.map { row ->
                        SearchedPage(
                            page = row.toPageModel(),
                            snippet = row.highlight?.takeIf { it.isNotBlank() },
                            bm25Score = row.bm25_score
                        )
                    }.applyPageScope(scope, searchRequest.pageUuid)
                } catch (_: Exception) {
                    // pages_fts not yet available — fall back to LIKE
                    queries.selectPagesByNameLike("%$rawQuery%")
                        .executeAsList()
                        .take(searchRequest.limit)
                        .map { SearchedPage(page = it.toPageModel()) }
                        .applyPageScope(scope, searchRequest.pageUuid)
                }
            } else emptyList()

            // ── Block search ───────────────────────────────────────────────
            val searchedBlocks: List<SearchedBlock> = if (
                scope != SearchScope.PAGES_ONLY &&
                DataType.CONTENT in dataTypes
            ) {
                try {
                    when (scope) {
                        SearchScope.CURRENT_PAGE -> {
                            val pageUuid = searchRequest.pageUuid
                            if (pageUuid != null) {
                                queries.searchBlocksByContentFtsInPage(
                                    query = ftsQuery,
                                    pageUuid = pageUuid,
                                    limit = searchRequest.limit.toLong(),
                                    offset = searchRequest.offset.toLong()
                                ).executeAsList().map { row ->
                                    SearchedBlock(
                                        block = row.toBlockModel(),
                                        snippet = row.highlight?.takeIf { it.isNotBlank() },
                                        bm25Score = row.bm25_score
                                    )
                                }
                            } else emptyList()
                        }
                        else -> {
                            val andBlocks = queries.searchBlocksByContentFts(
                                query = ftsQuery,
                                limit = searchRequest.limit.toLong(),
                                offset = searchRequest.offset.toLong()
                            ).executeAsList()
                            val blockRows = if (andBlocks.isNotEmpty()) {
                                andBlocks
                            } else {
                                val orQuery = FtsQueryBuilder.buildOr(rawQuery)
                                if (orQuery.isEmpty()) emptyList()
                                else queries.searchBlocksByContentFts(
                                    query = orQuery,
                                    limit = searchRequest.limit.toLong(),
                                    offset = searchRequest.offset.toLong()
                                ).executeAsList()
                            }
                            blockRows.map { row ->
                                SearchedBlock(
                                    block = row.toBlockModel(),
                                    snippet = row.highlight?.takeIf { it.isNotBlank() },
                                    bm25Score = row.bm25_score
                                )
                            }.applyBlockScope(scope)
                        }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()

            val neighbourPageUuids = searchRequest.pageUuid
                ?.let { runCatching { queries.selectNeighbourPageUuids(it).executeAsList().toSet() }.getOrDefault(emptySet()) }
                ?: emptySet()
            val nowMs = HistogramWriter.epochMs()
            val ranked = buildRankedList(searchedPages, searchedBlocks, neighbourPageUuids, nowMs)
            emit(SearchResult(
                blocks = searchedBlocks.map { it.block },
                pages = searchedPages.map { it.page },
                searchedBlocks = searchedBlocks,
                searchedPages = searchedPages,
                ranked = ranked,
                totalCount = ranked.size,
                hasMore = false
            ).right())
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    // ── Ranking helpers ────────────────────────────────────────────────────

    private fun buildRankedList(
        pages: List<SearchedPage>,
        blocks: List<SearchedBlock>,
        neighbourPageUuids: Set<String>,
        nowMs: Long,
    ): List<RankedSearchHit> {
        val pageHits = pages.map { sp ->
            val bm25 = abs(sp.bm25Score)
            val score = bm25 * PAGE_BOOST *
                recencyMultiplier(sp.page.updatedAt.toEpochMilliseconds(), nowMs) *
                graphMultiplier(sp.page.uuid, neighbourPageUuids)
            RankedSearchHit.PageHit(sp.page, sp.snippet, score)
        }
        val blockHits = blocks.map { sb ->
            val bm25 = abs(sb.bm25Score)
            val score = bm25 *
                recencyMultiplier(sb.block.updatedAt.toEpochMilliseconds(), nowMs) *
                graphMultiplier(sb.block.pageUuid, neighbourPageUuids)
            RankedSearchHit.BlockHit(sb.block, sb.snippet, score)
        }
        return (pageHits + blockHits).sortedByDescending { it.score }
    }

    /** Returns 1.0 + exp(-daysSinceEdit / halfLife) — between ~2.0 (today) and ~1.0 (old). */
    private fun recencyMultiplier(updatedAtMs: Long, nowMs: Long): Double {
        if (updatedAtMs <= 0) return 1.0
        val daysSince = (nowMs - updatedAtMs).coerceAtLeast(0L) / 86_400_000.0
        return 1.0 + exp(-daysSince / RECENCY_HALFLIFE_DAYS)
    }

    /** Returns GRAPH_BOOST if the page is a 1-hop neighbour of the current page, else 1.0. */
    private fun graphMultiplier(pageUuid: String, neighbourPageUuids: Set<String>): Double =
        if (pageUuid in neighbourPageUuids) GRAPH_BOOST else 1.0

    // ── Scope helpers ──────────────────────────────────────────────────────

    private fun List<SearchedPage>.applyPageScope(
        scope: SearchScope,
        pageUuid: String?
    ): List<SearchedPage> = when (scope) {
        SearchScope.JOURNAL -> filter { it.page.isJournal }
        SearchScope.FAVORITES -> filter { it.page.isFavorite }
        SearchScope.CURRENT_PAGE -> if (pageUuid != null) filter { it.page.uuid == pageUuid } else emptyList()
        else -> this
    }

    private fun List<SearchedBlock>.applyBlockScope(scope: SearchScope): List<SearchedBlock> = when (scope) {
        SearchScope.JOURNAL, SearchScope.FAVORITES -> this // requires page join; filtered at page level
        else -> this
    }

    // ── Type mappers ───────────────────────────────────────────────────────

    private fun dev.stapler.stelekit.db.SearchBlocksByContentFts.toBlockModel(): Block =
        Block(
            uuid = uuid,
            pageUuid = page_uuid,
            parentUuid = parent_uuid,
            leftUuid = left_uuid,
            content = content,
            level = level.toInt(),
            position = position.toInt(),
            createdAt = Instant.fromEpochMilliseconds(created_at),
            updatedAt = Instant.fromEpochMilliseconds(updated_at),
            version = version,
            properties = parseProperties(properties)
        )

    private fun dev.stapler.stelekit.db.SearchBlocksByContentFtsInPage.toBlockModel(): Block =
        Block(
            uuid = uuid,
            pageUuid = page_uuid,
            parentUuid = parent_uuid,
            leftUuid = left_uuid,
            content = content,
            level = level.toInt(),
            position = position.toInt(),
            createdAt = Instant.fromEpochMilliseconds(created_at),
            updatedAt = Instant.fromEpochMilliseconds(updated_at),
            version = version,
            properties = parseProperties(properties)
        )

    private fun dev.stapler.stelekit.db.Blocks.toBlockModel(): Block =
        Block(
            uuid = uuid,
            pageUuid = page_uuid,
            parentUuid = parent_uuid,
            leftUuid = left_uuid,
            content = content,
            level = level.toInt(),
            position = position.toInt(),
            createdAt = Instant.fromEpochMilliseconds(created_at),
            updatedAt = Instant.fromEpochMilliseconds(updated_at),
            version = version,
            properties = parseProperties(properties)
        )

    private fun dev.stapler.stelekit.db.SearchPagesByNameFts.toPageModel(): Page =
        Page(
            uuid = uuid,
            name = name,
            namespace = namespace,
            filePath = file_path,
            createdAt = Instant.fromEpochMilliseconds(created_at),
            updatedAt = Instant.fromEpochMilliseconds(updated_at),
            version = version,
            properties = emptyMap(),
            isFavorite = is_favorite == 1L,
            isJournal = is_journal == 1L,
            journalDate = journal_date?.let { kotlinx.datetime.LocalDate.parse(it) }
        )

    private fun dev.stapler.stelekit.db.Pages.toPageModel(): Page =
        Page(
            uuid = uuid,
            name = name,
            namespace = namespace,
            filePath = file_path,
            createdAt = Instant.fromEpochMilliseconds(created_at),
            updatedAt = Instant.fromEpochMilliseconds(updated_at),
            version = version,
            properties = emptyMap(),
            isFavorite = is_favorite == 1L,
            isJournal = is_journal == 1L,
            journalDate = journal_date?.let { kotlinx.datetime.LocalDate.parse(it) }
        )

    private fun parseProperties(raw: String?): Map<String, String> =
        raw?.split(",")?.mapNotNull {
            val parts = it.split(":", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }?.toMap() ?: emptyMap()
}
