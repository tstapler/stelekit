package dev.stapler.stelekit.benchmarks

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SearchRequest
import dev.stapler.stelekit.repository.SearchedBlock
import dev.stapler.stelekit.repository.SearchedPage
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import dev.stapler.stelekit.repository.SqlDelightSearchRepository
import dev.stapler.stelekit.search.FtsQueryBuilder
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import kotlin.time.Clock

/**
 * JMH benchmarks for the search layer.
 *
 * Setup: 2 000-page in-memory SQLite DB with 5 blocks/page = 10 000 blocks total.
 * The DB is created once per trial via [setup] so all @Benchmark methods share a warm DB.
 *
 * Run via: ./gradlew :kmp:jvmBenchmark
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
open class SearchBenchmark {

    private lateinit var repo: SqlDelightSearchRepository

    // Pre-built inputs for buildRankedList micro-benchmark
    private lateinit var benchPages: List<SearchedPage>
    private lateinit var benchBlocks: List<SearchedBlock>
    private val emptyNeighbours: Set<String> = emptySet()
    private val emptyVisitMap: Map<String, Long> = emptyMap()
    private val nowMs: Long = System.currentTimeMillis()

    @Setup(Level.Trial)
    fun setup(): Unit = runBlocking {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        populateDb(db, pageCount = 2_000, blocksPerPage = 5)
        repo = SqlDelightSearchRepository(db)

        // Pre-build inputs for buildRankedList so we measure only ranking logic
        val now = Clock.System.now()
        benchPages = (0 until 50).map { i ->
            SearchedPage(
                page = Page(
                    uuid = "page-$i",
                    name = "Page $i",
                    createdAt = now,
                    updatedAt = now,
                ),
                snippet = "snippet $i",
                bm25Score = -(i + 1.0),
            )
        }
        benchBlocks = (0 until 50).map { i ->
            SearchedBlock(
                block = Block(
                    uuid = "block-$i",
                    pageUuid = "page-${i % 50}",
                    content = "block content $i",
                    position = i,
                    createdAt = now,
                    updatedAt = now,
                ),
                snippet = "snippet $i",
                bm25Score = -(i + 1.0),
            )
        }
    }

    // ── T5.3 benchmarks ────────────────────────────────────────────────────

    @Benchmark
    fun searchWithFiltersSingleToken(): Any = runBlocking {
        repo.searchWithFilters(SearchRequest(query = "programming", limit = 50)).first()
    }

    @Benchmark
    fun searchWithFiltersMultiToken(): Any = runBlocking {
        repo.searchWithFilters(SearchRequest(query = "programming notes kotlin", limit = 50)).first()
    }

    @Benchmark
    fun searchWithFiltersPhraseQuery(): Any = runBlocking {
        repo.searchWithFilters(SearchRequest(query = "\"programming notes\"", limit = 50)).first()
    }

    @Benchmark
    fun searchPagesByTitleSingleToken(): Any = runBlocking {
        repo.searchPagesByTitle(query = "design", limit = 50).first()
    }

    @Benchmark
    fun buildRankedList50Results(): Any {
        return repo.buildRankedList(
            pages = benchPages,
            blocks = benchBlocks,
            neighbourPageUuids = emptyNeighbours,
            visitMap = emptyVisitMap,
            nowMs = nowMs,
        )
    }

    @Benchmark
    fun ftsQueryBuilderComplexQuery(): String {
        return FtsQueryBuilder.build("kotlin programming notes architecture performance testing")
    }

    // ── Inline DB population (mirrors SyntheticGraphDbBuilder from jvmTest) ─

    private val wordList = listOf(
        "programming", "notes", "project", "meeting", "tax", "philosophy",
        "economics", "history", "design", "learning", "kotlin", "database",
        "algorithm", "architecture", "performance", "testing", "refactoring",
        "documentation", "review", "planning", "research", "analysis",
    )

    private fun generateUuid(index: Int): String {
        val hex = index.toString().padStart(8, '0')
        return "00000000-0000-0000-0000-$hex"
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun populateDb(db: SteleDatabase, pageCount: Int, blocksPerPage: Int) {
        val pageRepo = SqlDelightPageRepository(db)
        val blockRepo = SqlDelightBlockRepository(db)
        val now = Clock.System.now()

        val pages = (0 until pageCount).map { i ->
            val word1 = wordList[i % wordList.size]
            val word2 = wordList[(i + 7) % wordList.size]
            Page(
                uuid = generateUuid(i),
                name = "$word1 $word2 page $i",
                namespace = null,
                filePath = null,
                createdAt = now,
                updatedAt = now,
                properties = emptyMap(),
                isFavorite = false,
                isJournal = false,
            )
        }
        pageRepo.savePages(pages)

        val blocks = pages.flatMapIndexed { pi, page ->
            (0 until blocksPerPage).map { bi ->
                val blockIdx = pi * blocksPerPage + bi
                val word1 = wordList[blockIdx % wordList.size]
                val word2 = wordList[(blockIdx + 3) % wordList.size]
                val word3 = wordList[(blockIdx + 11) % wordList.size]
                Block(
                    uuid = generateUuid(pageCount + blockIdx),
                    pageUuid = page.uuid,
                    parentUuid = null,
                    leftUuid = null,
                    content = "$word1 $word2 $word3 notes from block $bi on page $pi",
                    level = 0,
                    position = bi,
                    createdAt = now,
                    updatedAt = now,
                    properties = emptyMap(),
                )
            }
        }
        blocks.chunked(500).forEach { batch ->
            blockRepo.saveBlocks(batch)
        }
    }
}
