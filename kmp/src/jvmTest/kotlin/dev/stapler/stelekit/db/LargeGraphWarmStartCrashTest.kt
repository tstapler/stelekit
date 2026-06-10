package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.IndexingState
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * End-to-end reproduction of the field scenario that crashed Android: a warm start on a
 * graph with 8 030 pages already registered in the database ("Loading same graph — Keeping
 * persistent cache for incremental load", "Page count: 8030"), followed by the full warm
 * reconcile (Phase 2) and background indexing (Phase 3) over every page.
 *
 * The test drives the real GraphLoader + StelekitViewModel pipeline — including the
 * standing UI observers (favorites, PageNameIndex) that previously re-materialized the
 * whole pages table on every write burst — and asserts the run completes with no Throwable
 * reaching the JVM default uncaught-exception handler. On Android any such Throwable kills
 * the process ("SteleKit keeps stopping").
 */
class LargeGraphWarmStartCrashTest {

    private companion object {
        const val PAGE_COUNT = 8_030
        const val JOURNAL_COUNT = 10
        const val GRAPH_PATH = "/tmp/large-graph"
    }

    private class UncaughtRecorder : AutoCloseable {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        private val previous = Thread.getDefaultUncaughtExceptionHandler()

        init {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        }

        override fun close() {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    /**
     * Records query-boundedness during the run: the whole pipeline must never subscribe to
     * the unbounded getAllPages()/getUnloadedPages() reads, and every batch lookup must be
     * chunk-sized — that is what keeps peak memory O(chunk) instead of O(graph).
     */
    private class BoundedQueryRecordingRepository(
        initial: List<Page>,
    ) : FakePageRepository(initial) {
        val getAllPagesSubscriptions = AtomicInteger(0)
        val unboundedUnloadedSubscriptions = AtomicInteger(0)
        val maxNamesPerLookup = AtomicInteger(0)
        val maxUnloadedBatchLimit = AtomicInteger(0)
        val boundedUnloadedFetches = AtomicInteger(0)

        override fun getAllPages(): Flow<Either<DomainError, List<Page>>> {
            val base = super.getAllPages()
            return flow {
                getAllPagesSubscriptions.incrementAndGet()
                emitAll(base)
            }
        }

        override fun getUnloadedPages(): Flow<Either<DomainError, List<Page>>> {
            val base = super.getUnloadedPages()
            return flow {
                unboundedUnloadedSubscriptions.incrementAndGet()
                emitAll(base)
            }
        }

        override fun getUnloadedPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
            boundedUnloadedFetches.incrementAndGet()
            maxUnloadedBatchLimit.updateAndGet { max(it, limit) }
            return super.getUnloadedPages(limit, offset)
        }

        override suspend fun getPagesByNames(names: Collection<String>): Either<DomainError, List<Page>> {
            maxNamesPerLookup.updateAndGet { max(it, names.size) }
            return super.getPagesByNames(names)
        }
    }

    /** Serves [PAGE_COUNT] synthetic markdown pages from the pages/ directory. */
    private class LargeGraphFileSystem : FakeFileSystem() {
        private val pageFiles = (1..PAGE_COUNT).map { "Test Page $it.md" }

        override fun listFiles(path: String): List<String> = when (path) {
            "$GRAPH_PATH/pages" -> pageFiles
            else -> emptyList()
        }

        override fun directoryExists(path: String): Boolean = true

        override fun readFile(path: String): String? {
            val name = path.substringAfterLast("/").removeSuffix(".md")
            return "- first block of $name\n- second block with [[Test Page 1]] link"
        }

        override fun getLastModifiedTime(path: String): Long = 1_000L
    }

    @Test
    fun warm_start_with_8030_pages_completes_without_uncaught_throwables() = runBlocking {
        UncaughtRecorder().use { recorder ->
            val now = Clock.System.now()
            val initialPages = buildList {
                // 8 030 regular pages already known to the DB but not content-loaded —
                // forces the warm reconcile to re-parse every file (the heavy path) and
                // Phase 3 to fully index all of them, as on a real first warm start.
                for (i in 1..PAGE_COUNT) {
                    add(
                        Page(
                            uuid = PageUuid("page-$i"),
                            name = "Test Page $i",
                            filePath = "$GRAPH_PATH/pages/Test Page $i.md",
                            createdAt = Instant.fromEpochMilliseconds(0),
                            updatedAt = Instant.fromEpochMilliseconds(0),
                            isContentLoaded = false,
                        )
                    )
                }
                // Journals in the DB put GraphLoader on the warm-start fast path
                // ("Warm start: N journals in DB — skipping blocking Phase 1").
                for (i in 1..JOURNAL_COUNT) {
                    add(
                        Page(
                            uuid = PageUuid("journal-$i"),
                            name = "2026-06-${i.toString().padStart(2, '0')}",
                            createdAt = now,
                            updatedAt = now,
                            isJournal = true,
                            journalDate = LocalDate(2026, 6, i),
                            isContentLoaded = true,
                        )
                    )
                }
            }

            val pageRepo = BoundedQueryRecordingRepository(initialPages)
            val blockRepo = FakeBlockRepository()
            val fileSystem = LargeGraphFileSystem()
            val settings = InMemorySettings().apply {
                putBoolean("onboardingCompleted", true)
                putString("lastGraphPath", GRAPH_PATH)
                // Same cached path ⇒ "Keeping persistent cache for incremental load",
                // exactly as in the field logs.
                putString("cached_graph_path", GRAPH_PATH)
            }

            val vm = StelekitViewModel(
                StelekitViewModelDependencies(
                    fileSystem = fileSystem,
                    pageRepository = pageRepo,
                    blockRepository = blockRepo,
                    searchRepository = InMemorySearchRepository(),
                    graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo),
                    graphWriter = GraphWriter(PlatformFileSystem()),
                    platformSettings = settings,
                    // Production-shaped scope (App.kt): no exception handler of its own.
                    scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                )
            )

            // Phase 1 (warm) must release the UI quickly.
            withTimeout(60_000) { vm.uiState.first { !it.isLoading } }

            // Phase 2 warm reconcile parses all 8 030 files.
            withTimeout(300_000) { vm.uiState.first { it.isFullyLoaded } }

            // Phase 3 background indexing fully loads all pages.
            withTimeout(300_000) {
                vm.indexingProgress.first { it is IndexingState.Complete }
            }

            // The page-name suggestion index must eventually build at this scale.
            withTimeout(60_000) {
                while (vm.suggestionMatcher.value == null) delay(100)
            }

            assertTrue(
                recorder.uncaught.isEmpty(),
                "Throwable(s) reached the default uncaught-exception handler during the " +
                    "8030-page warm start — on Android this kills the process " +
                    "(\"SteleKit keeps stopping\"). " +
                    "Uncaught: ${recorder.uncaught.map { it::class.simpleName + ": " + it.message }}"
            )
            assertTrue(
                vm.uiState.value.fatalError == null,
                "Warm start must complete cleanly; fatalError=${vm.uiState.value.fatalError}"
            )

            val pageCount = pageRepo.countPages().first().getOrNull() ?: 0L
            assertTrue(
                pageCount >= PAGE_COUNT,
                "All $PAGE_COUNT pages must survive the warm reconcile; found $pageCount"
            )

            // ── Query boundedness: peak memory must stay O(chunk), not O(graph) ─────
            assertTrue(
                pageRepo.getAllPagesSubscriptions.get() == 0,
                "getAllPages() must never be subscribed during load — found " +
                    "${pageRepo.getAllPagesSubscriptions.get()} subscription(s); full-table " +
                    "reads re-materialize all $PAGE_COUNT pages and OOM Android"
            )
            assertTrue(
                pageRepo.unboundedUnloadedSubscriptions.get() == 0,
                "Unbounded getUnloadedPages() must not be used — Phase 3 must drain in batches"
            )
            assertTrue(
                pageRepo.maxNamesPerLookup.get() in 1..100,
                "Reconcile name lookups must be chunk-sized (≤100); max was " +
                    "${pageRepo.maxNamesPerLookup.get()}"
            )
            assertTrue(
                pageRepo.maxUnloadedBatchLimit.get() in 1..100,
                "Phase 3 must fetch unloaded pages in bounded batches (≤100); max limit was " +
                    "${pageRepo.maxUnloadedBatchLimit.get()}"
            )
            assertTrue(
                pageRepo.boundedUnloadedFetches.get() > 1,
                "Phase 3 over $PAGE_COUNT pages must take multiple bounded fetches; " +
                    "got ${pageRepo.boundedUnloadedFetches.get()}"
            )
        }
    }
}
