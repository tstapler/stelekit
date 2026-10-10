package dev.stapler.stelekit.db

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Phase 3 background indexing must drain unloaded pages in bounded batches (backpressure)
 * instead of materializing the entire unloaded set up front — on a first warm start of an
 * 8 000+ page graph the old snapshot read held every unloaded Page in memory at once.
 *
 * The drain loop must also terminate when some pages can never be indexed (missing file,
 * unreadable content): the offset advances past permanent failures, so a batch can never
 * consist solely of already-failed rows fetched forever.
 */
class GraphLoaderIndexBatchingTest {

    private companion object {
        const val GRAPH_PATH = "/tmp/index-batch-graph"
    }

    private class RecordingRepository(initial: List<Page>) : FakePageRepository(initial) {
        val maxBatchLimit = AtomicInteger(0)
        val boundedFetches = AtomicInteger(0)

        override fun getUnloadedPages(limit: Int, offset: Int): Flow<Either<DomainError, List<Page>>> {
            boundedFetches.incrementAndGet()
            maxBatchLimit.updateAndGet { max(it, limit) }
            return super.getUnloadedPages(limit, offset)
        }
    }

    /** Files whose name contains "broken" are unreadable (readFile returns null). */
    private class PartiallyBrokenFileSystem : FakeFileSystem() {
        override fun directoryExists(path: String): Boolean = true
        override fun getLastModifiedTime(path: String): Long = 1_000L
        override fun readFile(path: String): String? {
            if (path.contains("broken")) return null
            val name = path.substringAfterLast("/").removeSuffix(".md")
            return "- content of $name"
        }
    }

    private fun unloadedPage(name: String): Page = Page(
        uuid = PageUuid("uuid-$name"),
        name = name,
        filePath = "$GRAPH_PATH/pages/$name.md",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        isContentLoaded = false,
    )

    @Test
    fun indexing_drains_in_bounded_batches_and_loads_everything() = runBlocking {
        val pages = (1..250).map { unloadedPage("note$it") }
        val pageRepo = RecordingRepository(pages)
        val loader = GraphLoader(PartiallyBrokenFileSystem(), pageRepo, FakeBlockRepository())
        loader.setGraphPath(GRAPH_PATH)

        withTimeout(120_000) { loader.indexRemainingPages { } }

        val remaining = pageRepo.countUnloadedPages().getOrNull() ?: -1L
        assertEquals(0L, remaining, "all 250 pages must be indexed")
        assertTrue(
            pageRepo.maxBatchLimit.get() in 1..100,
            "unloaded pages must be fetched in bounded batches (≤100); max limit was " +
                "${pageRepo.maxBatchLimit.get()}"
        )
        assertTrue(
            pageRepo.boundedFetches.get() >= 3,
            "250 pages at ≤100 per batch needs ≥3 fetches; got ${pageRepo.boundedFetches.get()}"
        )
    }

    @Test
    fun indexing_terminates_when_some_pages_permanently_fail() = runBlocking {
        val pages = (1..90).map { unloadedPage("note$it") } +
            (1..30).map { unloadedPage("broken$it") }
        val pageRepo = RecordingRepository(pages)
        val loader = GraphLoader(PartiallyBrokenFileSystem(), pageRepo, FakeBlockRepository())
        loader.setGraphPath(GRAPH_PATH)

        // Must terminate despite 30 pages that can never leave the unloaded set —
        // the drain offset has to advance past them instead of refetching them forever.
        withTimeout(60_000) { loader.indexRemainingPages { } }

        val remaining = pageRepo.countUnloadedPages().getOrNull() ?: -1L
        assertEquals(30L, remaining, "exactly the 30 unreadable pages must remain unloaded")
    }
}
