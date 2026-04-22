package dev.stapler.stelekit.repository

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
import kotlin.Result.Companion.success

/**
 * SQLDelight implementation of SearchRepository.
 *
 * Block content is searched via FTS5 with BM25 ranking.
 * Page names are searched via FTS5 (pages_fts) with BM25 ranking.
 * Queries are built by [FtsQueryBuilder] which handles phrase search and multi-token OR.
 */
class SqlDelightSearchRepository(
    private val database: SteleDatabase,
    private val histogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = null,
    ringBuffer: RingBufferSpanExporter? = null,
) : SearchRepository {

    private val queries = database.steleDatabaseQueries
    private val spanEmitter = SpanEmitter(ringBuffer)

    override fun searchBlocksByContent(query: String, limit: Int, offset: Int): Flow<Result<List<Block>>> = flow {
        try {
            val ftsQuery = FtsQueryBuilder.build(query)
            if (ftsQuery.isEmpty()) { emit(success(emptyList())); return@flow }
            val startMs = HistogramWriter.epochMs()
            val traceId = UuidGenerator.generateV7()
            val spanId = UuidGenerator.generateV7()
            CurrentSpanContext.set(ActiveSpanContext(traceId, spanId))
            val results = try {
                queries.searchBlocksByContentFts(
                    query = ftsQuery,
                    limit = limit.toLong(),
                    offset = offset.toLong()
                ).executeAsList().map { it.toBlockModel() }
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
            emit(success(results))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun searchPagesByTitle(query: String, limit: Int): Flow<Result<List<Page>>> = flow {
        try {
            val ftsQuery = FtsQueryBuilder.build(query)
            if (ftsQuery.isEmpty()) { emit(success(emptyList())); return@flow }
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
            emit(success(results))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun findBlocksReferencing(blockUuid: String): Flow<Result<List<Block>>> = flow {
        try {
            val results = queries.selectBlocksReferencing(blockUuid)
                .executeAsList()
                .map { it.toBlockModel() }
            emit(success(results))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun searchWithFilters(searchRequest: SearchRequest): Flow<Result<SearchResult>> = flow {
        try {
            val rawQuery = searchRequest.query
            if (rawQuery.isNullOrBlank()) {
                emit(success(SearchResult(
                    blocks = emptyList(),
                    pages = emptyList(),
                    totalCount = 0,
                    hasMore = false
                )))
                return@flow
            }

            val ftsQuery = FtsQueryBuilder.build(rawQuery)
            if (ftsQuery.isEmpty()) {
                emit(success(SearchResult(
                    blocks = emptyList(),
                    pages = emptyList(),
                    totalCount = 0,
                    hasMore = false
                )))
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
                    queries.searchPagesByNameFts(
                        query = ftsQuery,
                        limit = searchRequest.limit.toLong()
                    ).executeAsList().map { row ->
                        SearchedPage(
                            page = row.toPageModel(),
                            snippet = row.highlight?.takeIf { it.isNotBlank() }
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
                                        snippet = row.highlight?.takeIf { it.isNotBlank() }
                                    )
                                }
                            } else emptyList()
                        }
                        else -> {
                            queries.searchBlocksByContentFts(
                                query = ftsQuery,
                                limit = searchRequest.limit.toLong(),
                                offset = searchRequest.offset.toLong()
                            ).executeAsList().map { row ->
                                SearchedBlock(
                                    block = row.toBlockModel(),
                                    snippet = row.highlight?.takeIf { it.isNotBlank() }
                                )
                            }.applyBlockScope(scope)
                        }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()

            emit(success(SearchResult(
                blocks = searchedBlocks.map { it.block },
                pages = searchedPages.map { it.page },
                searchedBlocks = searchedBlocks,
                searchedPages = searchedPages,
                totalCount = searchedBlocks.size + searchedPages.size,
                hasMore = false
            )))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.DB)

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
